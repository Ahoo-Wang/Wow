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
import me.ahoo.wow.api.decide.reject
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
import me.ahoo.wow.ops.api.parked.Retry
import me.ahoo.wow.ops.api.parked.RetryRequested
import me.ahoo.wow.ops.api.parked.Skip
import me.ahoo.wow.ops.api.parked.SkipRequested
import java.time.Instant

/**
 * One service's entry of one parked aggregate, its id `<service>:<subscription>:<aggregate>`
 * (`Ledger.parkedId`): the read model of the owning service's failure ledger, and where an operator acts on it.
 *
 * - **Records**: the owning service publishes the entry as it stands ([RecordParked]), again on every scan, and that
 *   it is resolved ([RecordResolved]). A record that says what the entry holds already changes nothing, so the scans
 *   leave the log as it is; a record of a parking at or before the latest one resolved (one a scan sent before the
 *   resolution, delivered after it), and one published before the record the entry holds, change nothing either.
 * - **Actions**: [Retry], [Skip], [MarkRecoverable] and [ApplyRetrySpec] record the operator's request
 *   ([RetryRequested], ...), which [ParkedOperationSaga] sends to the owning service; what came of it arrives as the
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
    fun onCommand(command: RecordParked): ParkedRecorded? = recorded(command.entry)

    private fun recorded(entry: ParkedEntry): ParkedRecorded? = when {
        state.holds(entry) -> null
        // A parking at or before the one resolved: a record sent before the resolution, delivered after it.
        state.resolved && entry.failedAt <= state.failedAt -> null
        !state.resolved && entry.recordedAt < state.recordedAt -> null
        else -> ParkedRecorded(entry)
    }

    /**
     * Resolves the entry: the parking the resolution names ([command]`.resolution.failedAt`), or, undated, the one it
     * holds. A resolution of a parking older than the one recorded, or of one resolved already, changes nothing; one of
     * a later parking than the one resolved (its record never arrived, or not yet) is recorded, so that parking's late
     * record changes nothing either.
     */
    fun onCommand(command: RecordResolved): ParkedResolved? {
        val resolution = command.resolution
        val failedAt = resolution.failedAt
        val stale = if (failedAt == null) {
            state.resolved
        } else {
            failedAt < state.failedAt || state.resolved && failedAt == state.failedAt
        }
        return if (stale) null else ParkedResolved(resolution.outcome, failedAt, resolution.resolvedAt)
    }

    /** The owning service refused the last request; a resolved entry has none to refuse. */
    fun onCommand(command: RecordActionRefused): ActionRefused? =
        if (state.resolved) null else ActionRefused(command.action, command.code, command.reason)

    fun onCommand(command: Retry): RetryRequested {
        rejectResolved()
        rejectStale(command.failedAt)
        return RetryRequested(command.reason, state.failedAt)
    }

    fun onCommand(command: Skip): SkipRequested {
        rejectResolved()
        rejectStale(command.failedAt)
        return SkipRequested(command.reason, state.failedAt)
    }

    /**
     * Refuses an action on the parking at [failedAt], the one the operator saw, when the entry now records
     * another: a retry or a skip acts on the parking the operator looked at, or not at all.
     */
    private fun rejectStale(failedAt: Long?) {
        if (failedAt != null && failedAt != state.failedAt) {
            reject(
                ParkedRejections.STALE,
                "${state.subscription} parked ${state.aggregate.id} again at ${Instant.ofEpochMilli(state.failedAt)}, " +
                    "after the parking at ${Instant.ofEpochMilli(failedAt)} the action names; read the entry again.",
            )
        }
    }

    fun onCommand(command: MarkRecoverable): MarkRecoverableRequested {
        rejectResolved()
        val recoverable = command.recoverable
        if (recoverable !in RECOVERABLES) {
            reject(
                ParkedRejections.INVALID,
                "Not a recoverable: '${command.recoverable}'; one of ${RECOVERABLES.joinToString()}."
            )
        }
        return MarkRecoverableRequested(recoverable)
    }

    /** Its count and durations are the command's input, checked at admission (`ApplyRetrySpec`'s constraints). */
    fun onCommand(command: ApplyRetrySpec): ApplyRetrySpecRequested {
        rejectResolved()
        return ApplyRetrySpecRequested(command.maxRetries, command.initialBackoff, command.claimTtl)
    }

    /** Refuses an operator's action on a resolved entry. */
    private fun rejectResolved() {
        if (state.resolved) {
            reject(ParkedRejections.RESOLVED, "${state.subscription} no longer parks ${state.aggregate.id}.")
        }
    }

    private companion object {
        val RECOVERABLES = listOf("recoverable", "unrecoverable", ParkedEntry.UNKNOWN)
    }
}
