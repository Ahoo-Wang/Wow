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

import me.ahoo.wow.api.ledger.LedgerAggregate
import me.ahoo.wow.api.ledger.LedgerEntry
import me.ahoo.wow.api.ledger.LedgerRetrySpec
import me.ahoo.wow.api.query.annotation.QueryTemporal
import me.ahoo.wow.ops.api.parked.ApplyRetrySpecRequested
import me.ahoo.wow.ops.api.parked.MarkRecoverableRequested
import me.ahoo.wow.ops.api.parked.ParkedRecorded
import me.ahoo.wow.ops.api.parked.ParkedResolved
import me.ahoo.wow.ops.api.parked.ParkedStatus
import me.ahoo.wow.ops.api.parked.ResumeRequested
import me.ahoo.wow.ops.api.parked.SkipRequested

/**
 * One service's entry of one parked aggregate, as its ledger last published it (decision D141): the ops view, whose
 * snapshot is the read model an operator queries. While parked, it is the entry's [LedgerEntry]; once resolved,
 * [status] is [ParkedStatus.RESOLVED] with the [outcome]. [requested] is the last action an operator asked of the
 * owning service (`RESUME`, `SKIP`, `MARK_RECOVERABLE` or `APPLY_RETRY_SPEC`), until the service publishes the entry
 * again. [errorType] is what [reason] says failed, which an operator groups the entries by (roadmap 7.1c).
 *
 * Its times are epoch milliseconds, declared so (`@QueryTemporal`): a query counts the entries by day or hour of them.
 */
class ParkedState {
    var service: String = ""
        private set
    var context: String = ""
        private set
    var subscription: String = ""
        private set
    var aggregate: LedgerAggregate = LedgerAggregate("", "", "", "")
        private set
    var fromVersion: Int = 0
        private set
    var fromPosition: Long? = null
        private set
    var reason: String = ""
        private set

    /**
     * The type of the failure [reason] records: what it names before its first `": "`, the exception's class as the
     * runtime parks an aggregate with it (`<class>: <message>`), or `wow.subscription.unrecoverable` for an entry its
     * source no longer holds; the whole reason when it names none.
     */
    var errorType: String = ""
        private set

    @field:QueryTemporal
    var failedAt: Long = 0
        private set

    var retries: Int = 0
        private set

    @field:QueryTemporal
    var retryAt: Long? = null
        private set

    var retrySpec: LedgerRetrySpec = LedgerRetrySpec.of(0, "PT0S", "PT0S")
        private set
    var recoverable: String = LedgerEntry.UNKNOWN
        private set
    var request: String? = null
        private set
    var status: String = ParkedStatus.SCHEDULED
        private set

    @field:QueryTemporal
    var nextRetryAt: Long? = null
        private set

    @field:QueryTemporal
    var recordedAt: Long = 0
        private set

    var outcome: String? = null
        private set

    @field:QueryTemporal
    var resolvedAt: Long? = null
        private set

    var requested: String? = null
        private set

    /** Whether the owning service no longer parks the entry. */
    val resolved: Boolean get() = status == ParkedStatus.RESOLVED

    /** Whether [record] says what this state holds already, whenever it was published. */
    fun holds(record: LedgerEntry): Boolean = !resolved && heldFields(record) == listOf(
        service, context, subscription, aggregate, fromVersion, fromPosition, reason, failedAt, retries, retryAt,
        retrySpec, recoverable, request, status, nextRetryAt,
    )

    /** The fields of [record] this state keeps, in the order [holds] compares them; it never builds an entry. */
    private fun heldFields(record: LedgerEntry): List<Any?> = with(record) {
        listOf(
            service, context, subscription, aggregate, fromVersion, fromPosition, reason, failedAt, retries, retryAt,
            retrySpec, recoverable, request, status, nextRetryAt,
        )
    }

    fun onSourcing(event: ParkedRecorded) {
        val entry = event.entry
        service = entry.service
        context = entry.context
        subscription = entry.subscription
        aggregate = entry.aggregate
        fromVersion = entry.fromVersion
        fromPosition = entry.fromPosition
        reason = entry.reason
        errorType = errorTypeOf(entry.reason)
        failedAt = entry.failedAt
        retries = entry.retries
        retryAt = entry.retryAt
        retrySpec = entry.retrySpec
        recoverable = entry.recoverable
        request = entry.request
        status = entry.status
        nextRetryAt = entry.nextRetryAt
        recordedAt = entry.recordedAt
        outcome = null
        resolvedAt = null
        requested = null
    }

    fun onSourcing(event: ParkedResolved) {
        status = ParkedStatus.RESOLVED
        outcome = event.outcome
        resolvedAt = event.resolvedAt
        request = null
        nextRetryAt = null
        requested = null
    }

    fun onSourcing(@Suppress("UNUSED_PARAMETER") event: ResumeRequested) {
        requested = RESUME
    }

    fun onSourcing(@Suppress("UNUSED_PARAMETER") event: SkipRequested) {
        requested = SKIP
    }

    fun onSourcing(@Suppress("UNUSED_PARAMETER") event: MarkRecoverableRequested) {
        requested = MARK_RECOVERABLE
    }

    fun onSourcing(@Suppress("UNUSED_PARAMETER") event: ApplyRetrySpecRequested) {
        requested = APPLY_RETRY_SPEC
    }

    companion object {
        /** The type of the failure [reason] records; see [errorType]. */
        fun errorTypeOf(reason: String): String = reason.substringBefore(": ").trim()

        const val RESUME: String = "RESUME"
        const val SKIP: String = "SKIP"
        const val MARK_RECOVERABLE: String = "MARK_RECOVERABLE"
        const val APPLY_RETRY_SPEC: String = "APPLY_RETRY_SPEC"
    }
}
