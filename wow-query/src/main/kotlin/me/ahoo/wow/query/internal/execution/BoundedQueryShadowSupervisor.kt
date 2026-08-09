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

@file:OptIn(me.ahoo.wow.query.gateway.ExperimentalQueryGatewayApi::class)

package me.ahoo.wow.query.internal.execution

import me.ahoo.wow.query.gateway.QueryDocumentKind
import me.ahoo.wow.query.gateway.QueryShadowConfiguration
import me.ahoo.wow.query.gateway.QueryShadowObservation
import me.ahoo.wow.query.gateway.QueryShadowObserver
import me.ahoo.wow.query.gateway.QueryShadowOutcome
import me.ahoo.wow.query.gateway.QueryTarget
import me.ahoo.wow.query.internal.rejection.QueryRejectedException
import me.ahoo.wow.query.internal.rejection.QueryRejection
import me.ahoo.wow.query.internal.rejection.QueryRejectionCategory
import me.ahoo.wow.query.internal.rejection.QueryRejectionCode
import me.ahoo.wow.query.internal.rejection.QueryRejectionPath
import reactor.core.Disposable
import reactor.core.publisher.Flux
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Bounded runtime owner for cold shadow publishers. Result values never leave this internal comparison boundary. */
internal class BoundedQueryShadowSupervisor(
    private val configuration: QueryShadowConfiguration,
    private val observer: QueryShadowObserver,
) : QueryShadowSupervisor {
    private val active = AtomicInteger()

    @Suppress("TooGenericExceptionCaught")
    override fun submit(task: QueryShadowTask): QueryShadowSubmission {
        if (active.incrementAndGet() > configuration.maxConcurrentProbes) {
            active.decrementAndGet()
            val issue = saturatedIssue()
            observe(task.observation(QueryShadowOutcome.SATURATED, issue.code.name))
            return QueryShadowSubmission.Rejected(issue)
        }
        val comparison = ShadowComparison(
            descriptor = task.descriptor(),
            maxComparedRecords = configuration.maxComparedRecords,
            observer = observer,
            release = { active.decrementAndGet() },
        )
        return try {
            comparison.subscribe(task)
            QueryShadowSubmission.Accepted(comparison)
        } catch (error: RuntimeException) {
            comparison.failSubscription(error)
            QueryShadowSubmission.Rejected(supervisorUnavailableIssue())
        }
    }

    override fun onSkipped(skip: QueryShadowSkip) {
        val issue = skip.issues.values.first()
        observe(
            QueryShadowObservation(
                skip.target.toPublic(),
                skip.operation,
                null,
                QueryShadowOutcome.SKIPPED,
                issue.code.name,
            ),
        )
    }

    private fun observe(observation: QueryShadowObservation) {
        try {
            observer.onObservation(observation)
        } catch (_: RuntimeException) {
            // Shadow observability cannot alter the primary result.
        }
    }
}

private class ShadowComparison(
    private val descriptor: QueryShadowDescriptor,
    private val maxComparedRecords: Int,
    private val observer: QueryShadowObserver,
    private val release: () -> Unit,
) : QueryShadowHandle {
    private val finished = AtomicBoolean()
    private val primaryValues = mutableListOf<Any>()
    private val probeValues = mutableListOf<Any>()
    private var primaryTerminal: PrimaryTerminal? = null
    private var probeTerminal: ProbeTerminal? = null
    private var primaryLimitExceeded = false
    private var subscription: Disposable? = null

    fun subscribe(task: QueryShadowTask) {
        subscription = Flux.from(task.publisher)
            .take(maxComparedRecords.toLong() + 1)
            .subscribe(::onProbeValue, ::onProbeError, ::onProbeComplete)
    }

    fun failSubscription(error: RuntimeException) {
        synchronized(this) {
            probeTerminal = ProbeTerminal.Error(error)
            primaryTerminal = PrimaryTerminal.Cancelled
        }
        finishIfReady()
    }

    override fun onPrimary(signal: QueryShadowPrimarySignal) {
        var cancelProbe = false
        synchronized(this) {
            when (signal) {
                is QueryShadowPrimarySignal.RecordValue -> cancelProbe = addPrimary(signal.value.comparable())
                is QueryShadowPrimarySignal.PageValue -> cancelProbe = addPrimary(signal.value.comparable())
                is QueryShadowPrimarySignal.CountValue -> cancelProbe = addPrimary(signal.value)
                QueryShadowPrimarySignal.Complete -> primaryTerminal = PrimaryTerminal.Complete
                is QueryShadowPrimarySignal.Error -> primaryTerminal = PrimaryTerminal.Error(signal.error)
                QueryShadowPrimarySignal.Cancelled -> primaryTerminal = PrimaryTerminal.Cancelled
            }
        }
        if (cancelProbe) subscription?.dispose()
        finishIfReady()
    }

    private fun addPrimary(value: Any): Boolean {
        if (primaryValues.size < maxComparedRecords) {
            primaryValues += value
            return false
        }
        primaryLimitExceeded = true
        if (probeTerminal == null) {
            probeTerminal = ProbeTerminal.LimitExceeded
            return true
        }
        return false
    }

    override fun cancelProbe() {
        subscription?.dispose()
        synchronized(this) {
            if (probeTerminal == null) {
                probeTerminal = ProbeTerminal.Cancelled
            }
        }
        finishIfReady()
    }

    private fun onProbeValue(value: Any) {
        synchronized(this) {
            if (probeValues.size >= maxComparedRecords) {
                probeTerminal = ProbeTerminal.LimitExceeded
            } else {
                probeValues += value.comparable()
            }
        }
    }

    private fun onProbeError(error: Throwable) {
        synchronized(this) {
            if (probeTerminal == null) {
                probeTerminal = ProbeTerminal.Error(error)
            }
        }
        finishIfReady()
    }

    private fun onProbeComplete() {
        synchronized(this) {
            if (probeTerminal == null) {
                probeTerminal = ProbeTerminal.Complete
            }
        }
        finishIfReady()
    }

    private fun finishIfReady() {
        val observation = synchronized(this) {
            val primary = primaryTerminal ?: return
            val probe = probeTerminal ?: return
            if (!finished.compareAndSet(false, true)) return
            observation(primary, probe)
        }
        release()
        try {
            observer.onObservation(observation)
        } catch (_: RuntimeException) {
            // Shadow observability cannot alter the primary result.
        }
    }

    private fun observation(primary: PrimaryTerminal, probe: ProbeTerminal): QueryShadowObservation {
        val (outcome, reason) = when {
            primary is PrimaryTerminal.Error -> QueryShadowOutcome.PRIMARY_ERROR to primary.error.rejection.code.name
            primary == PrimaryTerminal.Cancelled -> QueryShadowOutcome.CANCELLED to null
            probe is ProbeTerminal.Error -> QueryShadowOutcome.PROBE_ERROR to probe.error.reasonCode()
            probe == ProbeTerminal.Cancelled -> QueryShadowOutcome.CANCELLED to null
            primaryLimitExceeded || probe == ProbeTerminal.LimitExceeded ->
                QueryShadowOutcome.PROBE_ERROR to QueryRejectionCode.RESULT_LIMIT_EXCEEDED.name

            primaryValues == probeValues -> QueryShadowOutcome.MATCH to null
            else -> QueryShadowOutcome.VALUE_MISMATCH to null
        }
        return descriptor.observation(outcome, reason)
    }
}

private sealed interface PrimaryTerminal {
    data object Complete : PrimaryTerminal

    data class Error(val error: QueryRejectedException) : PrimaryTerminal

    data object Cancelled : PrimaryTerminal
}

private sealed interface ProbeTerminal {
    data object Complete : ProbeTerminal

    data class Error(val error: Throwable) : ProbeTerminal

    data object LimitExceeded : ProbeTerminal

    data object Cancelled : ProbeTerminal
}

private data class ComparableRecord(
    val identity: String,
    val document: me.ahoo.wow.query.backend.NormalizedValue.ObjectValue,
)

private data class ComparablePage(
    val records: List<ComparableRecord>,
    val total: Long,
)

private fun Any.comparable(): Any =
    when (this) {
        is BackendRecord -> comparable()
        is BackendPage -> comparable()
        else -> this
    }

private fun BackendRecord.comparable(): ComparableRecord = ComparableRecord(identity, document)

private fun BackendPage.comparable(): ComparablePage = ComparablePage(records.map(BackendRecord::comparable), total)

private fun Throwable.reasonCode(): String =
    (this as? QueryRejectedException)?.rejection?.code?.name ?: QueryRejectionCode.UNEXPECTED_QUERY_FAILURE.name

private fun QueryShadowTask.descriptor(): QueryShadowDescriptor = QueryShadowDescriptor(
    target,
    fingerprint,
    semanticTier,
    operation,
)

private fun QueryShadowTask.observation(outcome: QueryShadowOutcome, reasonCode: String?): QueryShadowObservation =
    descriptor().observation(outcome, reasonCode)

private fun QueryShadowDescriptor.observation(
    outcome: QueryShadowOutcome,
    reasonCode: String?,
): QueryShadowObservation = QueryShadowObservation(
    target.toPublic(),
    operation,
    fingerprint.value,
    outcome,
    reasonCode,
)

private fun me.ahoo.wow.query.internal.model.QueryTarget.toPublic(): QueryTarget = QueryTarget(
    namedAggregate,
    when (documentKind) {
        me.ahoo.wow.query.internal.model.QueryDocumentKind.SNAPSHOT -> QueryDocumentKind.SNAPSHOT
        me.ahoo.wow.query.internal.model.QueryDocumentKind.EVENT_STREAM -> QueryDocumentKind.EVENT_STREAM
    },
)

private fun saturatedIssue(): QueryRejection = QueryRejection(
    QueryRejectionCategory.BACKEND_UNAVAILABLE,
    QueryRejectionPath.ROOT.property("shadow").property("supervisor"),
    QueryRejectionCode.SHADOW_SUPERVISOR_SATURATED,
)

private fun supervisorUnavailableIssue(): QueryRejection = QueryRejection(
    QueryRejectionCategory.BACKEND_UNAVAILABLE,
    QueryRejectionPath.ROOT.property("shadow").property("supervisor"),
    QueryRejectionCode.SHADOW_SUPERVISOR_UNAVAILABLE,
)
