/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.ahoo.wow.ops.domain.parked

import me.ahoo.wow.api.annotation.AggregateRoot
import me.ahoo.wow.api.annotation.Description
import me.ahoo.wow.api.annotation.OnCommand
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.ledger.RecordParked
import me.ahoo.wow.api.ledger.RecordResolved
import me.ahoo.wow.ops.api.parked.ApplyRetrySpec
import me.ahoo.wow.ops.api.parked.ApplyRetrySpecRequested
import me.ahoo.wow.ops.api.parked.MarkRecoverable
import me.ahoo.wow.ops.api.parked.MarkRecoverableRequested
import me.ahoo.wow.ops.api.parked.ParkedRecorded
import me.ahoo.wow.ops.api.parked.ParkedResolved
import me.ahoo.wow.ops.api.parked.Resume
import me.ahoo.wow.ops.api.parked.ResumeRequested
import me.ahoo.wow.ops.api.parked.Skip
import me.ahoo.wow.ops.api.parked.SkipRequested
import kotlin.time.Duration

/**
 * One service's entry of one parked aggregate (decision D141), its id `<service>:<subscription>:<aggregate>`
 * (`Ledger.parkedId`): the read model of the owning service's failure ledger, and where an operator acts on it.
 *
 * - **Records**: the owning service publishes the entry as it stands ([RecordParked]), again on every scan, and that
 *   it is resolved ([RecordResolved]). A record that says what the entry holds already changes nothing, so the scans
 *   leave the log as it is; a record of a parking older than the one resolved (one a scan sent before the resolution,
 *   delivered after it), and one published before the record the entry holds, change nothing either.
 * - **Actions**: [Resume], [Skip], [MarkRecoverable] and [ApplyRetrySpec] record the operator's request
 *   ([ResumeRequested], ...), which [ParkedOperationSaga] sends to the owning service; what came of it arrives as the
 *   service's next record. A resolved entry takes no action: there is nothing parked to act on.
 */
@AggregateRoot
@Description("One service's parked entry, as its failure ledger publishes it, and an operator's actions on it")
class Parked(private val state: ParkedState) {
    @OnCommand(produces = [ParkedRecorded::class])
    fun onCommand(command: RecordParked): Decision = when {
        state.holds(command) -> Decision.NoOp
        // A parking at or before the one resolved: a record sent before the resolution, delivered after it.
        state.resolved && command.failedAt <= state.failedAt -> Decision.NoOp
        !state.resolved && command.recordedAt < state.recordedAt -> Decision.NoOp
        else -> Decision.events(ParkedRecorded(command))
    }

    @OnCommand(produces = [ParkedResolved::class])
    fun onCommand(command: RecordResolved): Decision {
        val failedAt = command.failedAt
        // Resolved already, or the resolution of an older parking than the one recorded.
        if (state.resolved || (failedAt != null && failedAt < state.failedAt)) {
            return Decision.NoOp
        }
        return Decision.events(ParkedResolved(command.outcome, failedAt, command.resolvedAt))
    }

    @OnCommand(produces = [ResumeRequested::class])
    fun onCommand(command: Resume): Decision = acting { ResumeRequested(command.reason) }

    @OnCommand(produces = [SkipRequested::class])
    fun onCommand(command: Skip): Decision = acting { SkipRequested(command.reason) }

    @OnCommand(produces = [MarkRecoverableRequested::class])
    fun onCommand(command: MarkRecoverable): Decision = acting {
        val recoverable = command.recoverable.uppercase()
        if (recoverable !in RECOVERABLES) {
            return invalid("Not a recoverable: '${command.recoverable}'; one of ${RECOVERABLES.joinToString()}.")
        }
        MarkRecoverableRequested(recoverable)
    }

    @OnCommand(produces = [ApplyRetrySpecRequested::class])
    fun onCommand(command: ApplyRetrySpec): Decision = acting {
        if (command.maxRetries != null && command.maxRetries < 0) {
            return invalid("maxRetries is never negative: ${command.maxRetries}.")
        }
        listOfNotNull(command.minBackoff, command.executionTimeout).firstOrNull { !isDuration(it) }?.let {
            return invalid("Not a duration (PT3M, 3m): '$it'.")
        }
        ApplyRetrySpecRequested(command.maxRetries, command.minBackoff, command.executionTimeout)
    }

    /** The event [request] gives, unless the entry is resolved. */
    private inline fun acting(request: () -> Any): Decision = if (state.resolved) {
        Decision.reject(ParkedRejections.RESOLVED, "${state.subscription} no longer parks ${state.aggregate.id}.")
    } else {
        Decision.events(request())
    }

    private fun invalid(message: String): Decision = Decision.reject(ParkedRejections.INVALID, message)

    private companion object {
        val RECOVERABLES = listOf("RECOVERABLE", "UNRECOVERABLE", RecordParked.UNKNOWN)

        fun isDuration(value: String): Boolean = Duration.parseOrNull(value.trim())?.let {
            it.isFinite() && !it.isNegative()
        } ?: false
    }
}
