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

package me.ahoo.wow.spi.ledger

import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.InternalWowApi

/*
 * The records a service publishes of its failure ledger, in the wire format of a command body:
 * the ops context's `parked` aggregate takes a [LedgerEntry] as the member [Ledger.ENTRY] of its command
 * [Ledger.RECORD_PARKED], and a [LedgerResolution] as the member [Ledger.RESOLUTION] of [Ledger.RECORD_RESOLVED].
 * Fields are only ever added; a reader ignores the ones it does not know. Enumerated values travel as their names, so
 * a reader of an older version reads a value a newer writer added. They are the publication form of the ledger's
 * [ParkedAggregate], internal to Wow: a reader such as the ops service reads them from their JSON with
 * types of its own.
 */

/**
 * [subscription] of [service] parks [aggregate], as its ledger holds it now (`FailureLedger`'s entry with the retry
 * spec it is retried by); the entry's command topic is [context]'s, the context of the subscription.
 *
 * - [fromVersion] and [fromPosition]: where the aggregate is parked; [reason] and [failedAt] (epoch milliseconds): why
 *   and when it last failed, which also tells one parking of the aggregate from the next.
 * - [retries] made so far, the last started at [retryAt]; [retrySpec], the spec it is retried by; [nextRetryAt] the next
 *   automatic retry, null when there is none.
 * - [recoverable]: `UNKNOWN`, `RECOVERABLE` or `UNRECOVERABLE`, what an operator marked it; [request]: `RESUME` or
 *   `SKIP` while an operator's request waits for the owner of its partition, else null.
 * - [status]: `SCHEDULED` (retried automatically), `EXHAUSTED` (its retries spent) or `UNRECOVERABLE`.
 * - [recordedAt]: when the service published it (epoch milliseconds).
 *
 * Fields may be added: the runtime's ledger publisher builds it, and a reader reads it from its JSON; the
 * constructor is internal to Wow.
 */
@InternalWowApi
class LedgerEntry @InternalWowApi constructor(
    val service: String,
    val context: String,
    val subscription: String,
    val aggregate: LedgerAggregate,
    val fromVersion: Int,
    val fromPosition: Long? = null,
    val reason: String,
    val failedAt: Long,
    val retries: Int = 0,
    val retryAt: Long? = null,
    val retrySpec: LedgerRetrySpec,
    val recoverable: String = UNKNOWN,
    val request: String? = null,
    val status: String,
    val nextRetryAt: Long? = null,
    val recordedAt: Long,
) {
    /** This LedgerEntry with the given fields replaced: Wow's own copy, left out of the ABI. */
    @InternalWowApi
    fun copyWith(
        service: String = this.service,
        context: String = this.context,
        subscription: String = this.subscription,
        aggregate: LedgerAggregate = this.aggregate,
        fromVersion: Int = this.fromVersion,
        fromPosition: Long? = this.fromPosition,
        reason: String = this.reason,
        failedAt: Long = this.failedAt,
        retries: Int = this.retries,
        retryAt: Long? = this.retryAt,
        retrySpec: LedgerRetrySpec = this.retrySpec,
        recoverable: String = this.recoverable,
        request: String? = this.request,
        status: String = this.status,
        nextRetryAt: Long? = this.nextRetryAt,
        recordedAt: Long = this.recordedAt,
    ): LedgerEntry = LedgerEntry(
        service,
        context,
        subscription,
        aggregate,
        fromVersion,
        fromPosition,
        reason,
        failedAt,
        retries,
        retryAt,
        retrySpec,
        recoverable,
        request,
        status,
        nextRetryAt,
        recordedAt,
    )

    override fun equals(other: Any?): Boolean =
        this === other || other is LedgerEntry && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String =
        "LedgerEntry(service=$service, context=$context, subscription=$subscription, aggregate=$aggregate, " +
            "fromVersion=$fromVersion, fromPosition=$fromPosition, reason=$reason, failedAt=$failedAt, " +
            "retries=$retries, retryAt=$retryAt, retrySpec=$retrySpec, recoverable=$recoverable, " +
            "request=$request, status=$status, nextRetryAt=$nextRetryAt, recordedAt=$recordedAt)"

    private fun valueFields(): List<Any?> = listOf(
        service, context, subscription, aggregate, fromVersion, fromPosition, reason, failedAt, retries, retryAt,
        retrySpec, recoverable, request, status, nextRetryAt, recordedAt
    )

    @InternalWowApi
    companion object {
        const val UNKNOWN: String = "UNKNOWN"
    }
}

/**
 * The retry spec a parked entry is retried by: at most [maxRetries] automatic retries, the first [initialBackoff] after the
 * failure, each allowed [timeout] (durations); [applied] when an operator applied it, rather than
 * the subscription's.
 *
 * Fields may be added: construct it with [of]; the constructor is internal to Wow.
 */
@InternalWowApi
class LedgerRetrySpec @InternalWowApi constructor(
    val maxRetries: Int,
    val initialBackoff: String,
    val timeout: String,
    val applied: Boolean = false,
) {
    override fun equals(other: Any?): Boolean =
        this === other || other is LedgerRetrySpec && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String =
        "LedgerRetrySpec(maxRetries=$maxRetries, initialBackoff=$initialBackoff, timeout=$timeout, " +
            "applied=$applied)"

    private fun valueFields(): List<Any?> = listOf(maxRetries, initialBackoff, timeout, applied)

    @InternalWowApi
    companion object {
        /** At most [maxRetries] retries, the first [initialBackoff] after the failure, each allowed [timeout]. */
        @JvmStatic
        @JvmOverloads
        fun of(
            maxRetries: Int,
            initialBackoff: String,
            timeout: String,
            applied: Boolean = false,
        ): LedgerRetrySpec = LedgerRetrySpec(maxRetries, initialBackoff, timeout, applied)
    }
}

/**
 * [subscription] of [service] no longer parks [aggregate], as of [resolvedAt] (epoch milliseconds): the parking that
 * failed at [failedAt] (null when the service did not know it) came to [outcome]: [RESUMED] (handled again),
 * [SKIPPED], [RESOLVED] (gone from the ledger, by an automatic retry or another node's action) or [NOT_PARKED] (an
 * operator's action found nothing parked).
 *
 * Fields may be added: construct it with [of]; the constructor is internal to Wow.
 */
@InternalWowApi
class LedgerResolution @InternalWowApi constructor(
    val service: String,
    val context: String,
    val subscription: String,
    val aggregate: LedgerAggregate,
    val failedAt: Long? = null,
    val outcome: String,
    val resolvedAt: Long,
) {
    override fun equals(other: Any?): Boolean =
        this === other || other is LedgerResolution && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String =
        "LedgerResolution(service=$service, context=$context, subscription=$subscription, aggregate=$aggregate, " +
            "failedAt=$failedAt, outcome=$outcome, resolvedAt=$resolvedAt)"

    private fun valueFields(): List<Any?> =
        listOf(service, context, subscription, aggregate, failedAt, outcome, resolvedAt)

    @InternalWowApi
    companion object {
        const val RESUMED: String = "RESUMED"
        const val SKIPPED: String = "SKIPPED"
        const val RESOLVED: String = "RESOLVED"
        const val NOT_PARKED: String = "NOT_PARKED"

        /**
         * [subscription] of [service] in [context] no longer parks [aggregate], as of [resolvedAt]: the parking that
         * failed at [failedAt] (null when unknown) came to [outcome].
         */
        @JvmStatic
        fun of(
            service: String,
            context: String,
            subscription: String,
            aggregate: LedgerAggregate,
            failedAt: Long?,
            outcome: String,
            resolvedAt: Long,
        ): LedgerResolution = LedgerResolution(service, context, subscription, aggregate, failedAt, outcome, resolvedAt)
    }
}

/**
 * A parked aggregate as the ledger's commands name it: the aggregate [name] of [context], its [tenantId] and [id]
 * (the shape of an entry on the operator routes, `/wow/ops/parked`).
 *
 * Fields may be added: construct one with [of]; the constructor is internal to Wow.
 */
@InternalWowApi
class LedgerAggregate @InternalWowApi constructor(
    val context: String,
    val name: String,
    val tenantId: String,
    val id: String,
) {
    /** The aggregate this names. */
    fun toRef(): AggregateRef = AggregateType(context, name).ref(tenantId, id)

    /** This LedgerAggregate with the given fields replaced: Wow's own copy, left out of the ABI. */
    @InternalWowApi
    fun copyWith(
        context: String = this.context,
        name: String = this.name,
        tenantId: String = this.tenantId,
        id: String = this.id,
    ): LedgerAggregate = LedgerAggregate(context, name, tenantId, id)

    override fun equals(other: Any?): Boolean =
        this === other || other is LedgerAggregate && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "LedgerAggregate(context=$context, name=$name, tenantId=$tenantId, id=$id)"

    private fun valueFields(): List<Any?> = listOf(context, name, tenantId, id)

    @InternalWowApi
    companion object {
        /** [ref] as the ledger's commands name it. */
        @JvmStatic
        fun of(ref: AggregateRef): LedgerAggregate = LedgerAggregate(
            ref.type.context,
            ref.type.name,
            ref.tenantId,
            ref.id
        )

        /** A LedgerAggregate of the given fields; parameters added later come last, with a default. */
        @JvmStatic
        fun of(context: String, name: String, tenantId: String, id: String): LedgerAggregate =
            LedgerAggregate(context, name, tenantId, id)
    }
}
