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

import me.ahoo.wow.api.aggregate.Ownership
import me.ahoo.wow.api.annotation.AggregateRoot
import me.ahoo.wow.api.annotation.Description
import me.ahoo.wow.api.annotation.OnCommand
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.ops.api.parked.ActionRefused
import me.ahoo.wow.ops.api.parked.ApplyRetrySpec
import me.ahoo.wow.ops.api.parked.ApplyRetrySpecRequested
import me.ahoo.wow.ops.api.parked.MarkRecoverable
import me.ahoo.wow.ops.api.parked.MarkRecoverableRequested
import me.ahoo.wow.ops.api.parked.ParkedEntry
import me.ahoo.wow.ops.api.parked.ParkedRecorded
import me.ahoo.wow.ops.api.parked.ParkedResolved
import me.ahoo.wow.ops.api.parked.RecordActionRefused
import me.ahoo.wow.ops.api.parked.RecordParked
import me.ahoo.wow.ops.api.parked.RecordResolved
import me.ahoo.wow.ops.api.parked.Resume
import me.ahoo.wow.ops.api.parked.ResumeRequested
import me.ahoo.wow.ops.api.parked.Skip
import me.ahoo.wow.ops.api.parked.SkipRequested
import kotlin.time.Duration

/**
 * One service's entry of one parked aggregate, its id `<service>:<subscription>:<aggregate>`
 * (`Ledger.parkedId`): the read model of the owning service's failure ledger, and where an operator acts on it.
 *
 * - **Records**: the owning service publishes the entry as it stands ([RecordParked]), again on every scan, and that
 *   it is resolved ([RecordResolved]). A record that says what the entry holds already changes nothing, so the scans
 *   leave the log as it is; a record of a parking older than the one resolved (one a scan sent before the resolution,
 *   delivered after it), and one published before the record the entry holds, change nothing either.
 * - **Actions**: [Resume], [Skip], [MarkRecoverable] and [ApplyRetrySpec] record the operator's request
 *   ([ResumeRequested], ...), which [ParkedOperationSaga] sends to the owning service; what came of it arrives as the
 *   service's next record, or, when the service refuses the request, as the saga's [RecordActionRefused]
 *   ([ActionRefused]). A resolved entry takes no action: there is nothing parked to act on.
 *
 * An entry is a resource of its tenant ([Ownership.TENANT]): the ledger records it without an owner,
 * and every operator of the tenant, a tenant-level caller, reads it; each action is authorized as the ledger's system
 * command of its name. It has no lifecycle command: it mirrors the owning service's ledger, which alone
 * resolves it.
 */
@AggregateRoot(lifecycle = [], ownership = Ownership.TENANT)
@Description("One service's parked entry, as its failure ledger publishes it, and an operator's actions on it")
class Parked(private val state: ParkedState) {
    @OnCommand(produces = [ParkedRecorded::class])
    fun onCommand(command: RecordParked): Decision = recorded(command.entry)

    private fun recorded(entry: ParkedEntry): Decision = when {
        state.holds(entry) -> Decision.NoOp
        // A parking at or before the one resolved: a record sent before the resolution, delivered after it.
        state.resolved && entry.failedAt <= state.failedAt -> Decision.NoOp
        !state.resolved && entry.recordedAt < state.recordedAt -> Decision.NoOp
        else -> Decision.events(ParkedRecorded(entry))
    }

    @OnCommand(produces = [ParkedResolved::class])
    fun onCommand(command: RecordResolved): Decision {
        val resolution = command.resolution
        val failedAt = resolution.failedAt
        // Resolved already, or the resolution of an older parking than the one recorded.
        if (state.resolved || (failedAt != null && failedAt < state.failedAt)) {
            return Decision.NoOp
        }
        return Decision.events(ParkedResolved(resolution.outcome, failedAt, resolution.resolvedAt))
    }

    /** The owning service refused the last request; a resolved entry has none to refuse. */
    @OnCommand(produces = [ActionRefused::class])
    fun onCommand(command: RecordActionRefused): Decision = if (state.resolved) {
        Decision.NoOp
    } else {
        Decision.events(ActionRefused(command.action, command.code, command.reason))
    }

    @OnCommand(produces = [ResumeRequested::class])
    fun onCommand(command: Resume): Decision = acting {
        stale(command.failedAt)?.let { return it }
        ResumeRequested(command.reason, state.failedAt)
    }

    @OnCommand(produces = [SkipRequested::class])
    fun onCommand(command: Skip): Decision = acting {
        stale(command.failedAt)?.let { return it }
        SkipRequested(command.reason, state.failedAt)
    }

    /**
     * The refusal of an action on the parking at [failedAt], the one the operator saw, when the entry now records
     * another: a resume or a skip acts on the parking the operator looked at, or not at all.
     */
    private fun stale(failedAt: Long?): Decision? = failedAt?.takeIf { it != state.failedAt }?.let {
        Decision.reject(
            ParkedRejections.STALE,
            "${state.subscription} parked ${state.aggregate.id} again at ${state.failedAt}, after the parking at $it " +
                "the action names; read the entry again.",
        )
    }

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
        command.maxRetries?.takeIf { it < 0 }?.let { return invalid("maxRetries is never negative: $it.") }
        listOfNotNull(command.initialBackoff, command.executionTimeout).firstOrNull { !isDuration(it) }?.let {
            return invalid("Not a duration (PT3M, 3m): '$it'.")
        }
        ApplyRetrySpecRequested(command.maxRetries, command.initialBackoff, command.executionTimeout)
    }

    /** The event [request] gives, unless the entry is resolved. */
    private inline fun acting(request: () -> Any): Decision = if (state.resolved) {
        Decision.reject(ParkedRejections.RESOLVED, "${state.subscription} no longer parks ${state.aggregate.id}.")
    } else {
        Decision.events(request())
    }

    private fun invalid(message: String): Decision = Decision.reject(ParkedRejections.INVALID, message)

    private companion object {
        val RECOVERABLES = listOf("RECOVERABLE", "UNRECOVERABLE", ParkedEntry.UNKNOWN)

        fun isDuration(value: String): Boolean = Duration.parseOrNull(value.trim())?.let {
            it.isFinite() && !it.isNegative()
        } ?: false
    }
}
