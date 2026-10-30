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

package me.ahoo.wow.ops.api.parked

import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType

/*
 * The records an owning service publishes of its failure ledger, read from their JSON:
 * the ops service defines its own types of them, as any operations tool would, rather than build on the runtime's
 * internal ones. A field the service adds later is ignored until these add it.
 */

/** A parked aggregate as a ledger record names it: the aggregate [name] of [context], its [tenantId] and [id]. */
data class EntryAggregate(val context: String, val name: String, val tenantId: String, val id: String) {
    /** The aggregate this names. */
    fun toRef(): AggregateRef = AggregateType(context, name).ref(tenantId, id)
}

/**
 * The retry spec a parked entry is retried by: at most [maxRetries] automatic retries, the first [initialBackoff] after the
 * failure, each claiming the entry for [claimTtl] (durations); [applied] when an operator applied it.
 */
data class EntryRetrySpec(
    val maxRetries: Int,
    val initialBackoff: String,
    val claimTtl: String,
    val applied: Boolean = false,
)

/**
 * [subscription] of [service] parks [aggregate], as its ledger holds it now: where ([fromVersion], [fromPosition]),
 * why and when it last failed ([reason], [failedAt]), its [retries] and the last one's start ([retryAt]), the
 * [retrySpec] it is retried by and its [nextRetryAt], what an operator marked it ([recoverable]) or asked of it
 * ([request]), its [status] (`scheduled`, `exhausted` or `unrecoverable`) and when the service published it
 * ([recordedAt]); the entry's command topic is [context]'s.
 */
data class ParkedEntry(
    val service: String,
    val context: String,
    val subscription: String,
    val aggregate: EntryAggregate,
    val fromVersion: Int,
    val fromPosition: Long? = null,
    val reason: String,
    val failedAt: Long,
    val retries: Int = 0,
    val retryAt: Long? = null,
    val retrySpec: EntryRetrySpec,
    val recoverable: String = UNKNOWN,
    val request: String? = null,
    val status: String,
    val nextRetryAt: Long? = null,
    val recordedAt: Long,
) {
    companion object {
        /** What an entry nobody marked is. */
        const val UNKNOWN: String = "unknown"
    }
}

/**
 * [subscription] of [service] no longer parks [aggregate], as of [resolvedAt]: the parking that failed at [failedAt]
 * came to [outcome] ([RETRIED], [SKIPPED], [RESOLVED] or [NOT_PARKED]).
 */
data class ParkedResolution(
    val service: String,
    val context: String,
    val subscription: String,
    val aggregate: EntryAggregate,
    val failedAt: Long? = null,
    val outcome: String,
    val resolvedAt: Long,
) {
    companion object {
        const val RETRIED: String = "retried"
        const val SKIPPED: String = "skipped"
        const val RESOLVED: String = "resolved"
        const val NOT_PARKED: String = "not-parked"
    }
}
