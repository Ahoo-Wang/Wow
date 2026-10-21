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
import me.ahoo.wow.spi.observation.Observation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * Sees the outcome of every query a gateway runs: the hook a host's metrics (9.x `QueryMetricsObserver`, wired by the
 * host in 6.2c), logs ([QueryLogObserver]) and audit trail implement. A query ends in exactly one of [onComplete],
 * [onError] and [onCancel]; when [audits] is set, [onAudit] follows with its [QueryAudit]. A cross-tenant query
 * (decision D254 ⑦) is audited whatever the observers: its audit is logged at INFO
 * ([QueryAudit.CROSS_TENANT_LOGGER]).
 *
 * [start] observes the query as a stage of work (decision D174): its span and its time, from its admission to its
 * end; the gateway ends the [Observation] it returns once the query ends, telling it the failure it ended with.
 */
interface QueryObserver {
    fun start(aggregateType: AggregateType, queryType: QueryType): Observation = Observation.NONE

    fun onComplete(aggregateType: AggregateType, queryType: QueryType) = Unit
    fun onError(aggregateType: AggregateType, queryType: QueryType, error: Throwable) = Unit
    fun onCancel(aggregateType: AggregateType, queryType: QueryType) = Unit

    /** Whether this observer wants [onAudit]; the gateway builds audits only when one is wanted or must be logged. */
    val audits: Boolean
        get() = false

    /** One [QueryAudit] per query, when it ends. */
    fun onAudit(audit: QueryAudit) = Unit

    /**
     * The aggregates one events query of [aggregateType] named and a [pinned][EventsMode.PINNED] policy had checked
     * (decisions D254, D259): [batched] of them by the one query of the type's snapshots, [pointReads] one by one (a
     * type whose snapshots are not queried, or an aggregate whose first snapshot lags). A high share of point reads
     * means slow events queries.
     */
    fun onEventsPinned(aggregateType: AggregateType, batched: Int, pointReads: Int) = Unit

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

    override fun start(aggregateType: AggregateType, queryType: QueryType): Observation {
        val started = observers.map { it.started(aggregateType, queryType) }.filter { it !== Observation.NONE }
        return when (started.size) {
            0 -> Observation.NONE
            1 -> started.single()
            else -> CompositeObservation(started)
        }
    }

    override fun onComplete(aggregateType: AggregateType, queryType: QueryType) =
        observers.forEach { it.isolated { onComplete(aggregateType, queryType) } }

    override fun onError(aggregateType: AggregateType, queryType: QueryType, error: Throwable) =
        observers.forEach { it.isolated { onError(aggregateType, queryType, error) } }

    override fun onCancel(aggregateType: AggregateType, queryType: QueryType) =
        observers.forEach { it.isolated { onCancel(aggregateType, queryType) } }

    override fun onAudit(audit: QueryAudit) =
        observers.filter { it.audits }.forEach { it.isolated { onAudit(audit) } }

    override fun onEventsPinned(aggregateType: AggregateType, batched: Int, pointReads: Int) =
        observers.forEach { it.isolated { onEventsPinned(aggregateType, batched, pointReads) } }
}

/** Every observation of [observations]: the first one's context and trace, each one told the outcome and the end. */
private class CompositeObservation(private val observations: List<Observation>) : Observation {
    override val context: CoroutineContext = observations.first().context

    override fun outcome(outcome: String) = observations.forEach { it.outcome(outcome) }

    override fun failed(error: Throwable) = observations.forEach { it.failed(error) }

    override fun end() = observations.forEach { it.end() }
}

private val log = KotlinLogging.logger { }

private val crossTenantAudit = KotlinLogging.logger(QueryAudit.CROSS_TENANT_LOGGER)

/** Whether a query of [caller] builds its audit: an observer audits, or it reads across tenants and is logged. */
internal fun QueryObserver.audits(caller: QueryCaller): Boolean = audits || caller.crossTenant != null

/**
 * Tells this observer how a query or a state read of [aggregateType] ended, then audits it on [trail]: a completion,
 * a cancellation (the collector stopped early) or an error.
 */
internal fun QueryObserver.finished(
    aggregateType: AggregateType,
    queryType: QueryType,
    trail: QueryAuditTrail?,
    error: Throwable?,
) {
    val outcome = when (error) {
        null -> {
            isolated { onComplete(aggregateType, queryType) }
            QueryAudit.Outcome.COMPLETE
        }
        is CancellationException -> {
            isolated { onCancel(aggregateType, queryType) }
            QueryAudit.Outcome.CANCEL
        }
        else -> {
            trail?.error(error)
            isolated { onError(aggregateType, queryType, error) }
            QueryAudit.Outcome.ERROR
        }
    }
    val audit = trail?.audit(outcome) ?: return
    if (audit.crossTenant != null) {
        val caller = audit.caller.caller
        crossTenantAudit.info { "Cross-tenant query by ${caller?.subject} of tenant ${caller?.tenantId}: $audit" }
    }
    if (audits) {
        isolated { onAudit(audit) }
    }
}

/** The observation [start] returns, or none when the observer fails to start one: an observer never fails a query. */
@Suppress("TooGenericExceptionCaught")
internal fun QueryObserver.started(aggregateType: AggregateType, queryType: QueryType): Observation = try {
    start(aggregateType, queryType)
} catch (failure: VirtualMachineError) {
    throw failure
} catch (failure: Throwable) {
    log.error(failure) { "Query observer [${javaClass.name}] failed to start." }
    Observation.NONE
}

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
