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

package me.ahoo.wow.query

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.query.filter.QueryType
import kotlin.coroutines.cancellation.CancellationException

/**
 * Sees the outcome of every query a gateway runs: the hook a host's metrics (9.x `QueryMetricsObserver`, wired by the
 * host in 6.2c), logs ([QueryLogObserver]) and audit trail implement. A query ends in exactly one of [onComplete],
 * [onError] and [onCancel]; when [audits] is set, [onAudit] follows with its [QueryAudit].
 */
interface QueryObserver {
    fun onComplete(aggregateType: AggregateType, queryType: QueryType) = Unit
    fun onError(aggregateType: AggregateType, queryType: QueryType, error: Throwable) = Unit
    fun onCancel(aggregateType: AggregateType, queryType: QueryType) = Unit

    /** Whether this observer wants [onAudit]; the gateway builds audits only when one is wanted. */
    val audits: Boolean
        get() = false

    /** One [QueryAudit] per query, when it ends. */
    fun onAudit(audit: QueryAudit) = Unit

    companion object {
        /** Sees nothing. */
        @JvmField
        val NONE: QueryObserver = object : QueryObserver {}

        /** Every observer of [observers], each isolated from the others' failures. */
        @JvmStatic
        fun of(observers: List<QueryObserver>): QueryObserver = when (observers.size) {
            0 -> NONE
            1 -> observers.single()
            else -> CompositeQueryObserver(observers.toList())
        }
    }
}

private class CompositeQueryObserver(private val observers: List<QueryObserver>) : QueryObserver {
    override val audits: Boolean = observers.any { it.audits }

    override fun onComplete(aggregateType: AggregateType, queryType: QueryType) =
        observers.forEach { it.isolated { onComplete(aggregateType, queryType) } }

    override fun onError(aggregateType: AggregateType, queryType: QueryType, error: Throwable) =
        observers.forEach { it.isolated { onError(aggregateType, queryType, error) } }

    override fun onCancel(aggregateType: AggregateType, queryType: QueryType) =
        observers.forEach { it.isolated { onCancel(aggregateType, queryType) } }

    override fun onAudit(audit: QueryAudit) =
        observers.filter { it.audits }.forEach { it.isolated { onAudit(audit) } }
}

private val log = KotlinLogging.logger { }

/**
 * Runs [callback] on this observer so that its failure, any non-fatal [Throwable] (an [AssertionError] or a Kotlin
 * [NotImplementedError] included), is logged and goes no further: an observer never fails the query or stops another
 * observer. A virtual machine error and a cancellation still propagate.
 */
@Suppress("TooGenericExceptionCaught")
internal fun QueryObserver.isolated(callback: QueryObserver.() -> Unit) {
    try {
        callback()
    } catch (failure: VirtualMachineError) {
        throw failure
    } catch (failure: CancellationException) {
        throw failure
    } catch (failure: Throwable) {
        log.error(failure) { "Query observer [${javaClass.name}] failed." }
    }
}
