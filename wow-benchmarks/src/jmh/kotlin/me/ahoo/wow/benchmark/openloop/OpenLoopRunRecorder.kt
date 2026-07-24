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

package me.ahoo.wow.benchmark.openloop

import me.ahoo.wow.benchmark.workload.OpenLoopDeadlineRegistration
import me.ahoo.wow.benchmark.workload.OpenLoopDeadlineSweep
import me.ahoo.wow.benchmark.workload.OpenLoopDeadlineWheel
import me.ahoo.wow.benchmark.workload.OpenLoopObservationMode
import me.ahoo.wow.benchmark.workload.OpenLoopOutcomeSnapshot
import me.ahoo.wow.benchmark.workload.OpenLoopServerSnapshot
import me.ahoo.wow.benchmark.workload.OpenLoopServerTicket
import me.ahoo.wow.benchmark.workload.OpenLoopServerTracker
import me.ahoo.wow.benchmark.workload.generatorFidelityViolations
import me.ahoo.wow.command.CommandResultException
import org.openjdk.jmh.util.SampleBuffer
import reactor.core.Disposable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.LongAdder

internal enum class ArrivalCohort {
    WARMUP,
    MEASUREMENT,
}

internal enum class TerminalOutcome {
    PROCESSED_BY_DEADLINE,
    COMMAND_FAILED,
    GATEWAY_FAILED,
    TIMED_OUT,
    FORCED_CANCELLED,
}

internal class OpenLoopRunRecorder(
    private val maxInFlight: Int,
    private val requestTimeoutNanos: Long,
    private val observationMode: OpenLoopObservationMode,
    timeoutResolutionNanos: Long,
    initialTimeNanos: Long,
    private val measurementStartNanos: Long,
    private val measurementEndNanos: Long,
    warmupPlanned: Long,
    measurementPlanned: Long,
) {
    private val warmup = MutableCohortCounters(warmupPlanned)
    private val measurement = MutableCohortCounters(measurementPlanned)
    private val active = ConcurrentHashMap<String, OpenLoopRequestState>()
    private val deadlineWheel =
        if (observationMode.deadlineWheelEnabled) {
            OpenLoopDeadlineWheel(
                resolutionNanos = timeoutResolutionNanos,
                maximumTimeoutNanos = requestTimeoutNanos,
                initialTimeNanos = initialTimeNanos,
                deadlineNanos = OpenLoopRequestState::deadlineNanos,
            )
        } else {
            null
        }
    private val deadlineSweepStats = DeadlineSweepStats(timeoutResolutionNanos)
    private val serverTracker =
        if (observationMode.serverTrackerEnabled) {
            OpenLoopServerTracker()
        } else {
            null
        }
    private val currentClientInFlight = AtomicInteger()
    private val clientRunPeakInFlight = AtomicInteger()
    private val inFlightSamples = InFlightSamples(
        measurementStartNanos = measurementStartNanos,
        measurementEndNanos = measurementEndNanos,
    )
    private val generatorLag = ConcurrentLatencyRecorder(
        enabled = observationMode.generatorLatencyEnabled,
    )
    private val scheduledToSent = ConcurrentLatencyRecorder(
        enabled = observationMode.serviceLatencyEnabled,
    )
    private val scheduledToProcessed = ConcurrentLatencyRecorder(
        enabled = observationMode.serviceLatencyEnabled,
    )
    private val admittedToProcessed = ConcurrentLatencyRecorder(
        enabled = observationMode.serviceLatencyEnabled,
    )
    private val sentToProcessed = ConcurrentLatencyRecorder(
        enabled = observationMode.serviceLatencyEnabled,
    )
    private val sentObservedAfterProcessed = LongAdder()
    private val commandFailureStages = ConcurrentHashMap<String, LongAdder>()

    fun recordGeneratorMissed(
        cohort: ArrivalCohort,
        count: Long = 1,
    ) {
        require(count > 0) {
            "Generator missed count must be positive."
        }
        counters(cohort).generatorMissed.add(count)
    }

    fun recordGeneratorExpired(cohort: ArrivalCohort) {
        counters(cohort).generatorExpired.increment()
    }

    fun recordShedAtMaxInFlight(cohort: ArrivalCohort) {
        counters(cohort).shedAtMaxInFlight.increment()
    }

    fun tryAdmit(
        commandId: String,
        cohort: ArrivalCohort,
        scheduledAtNanos: Long,
        admittedAtNanos: Long,
    ): OpenLoopRequestState? {
        if (!tryAcquireInFlight()) {
            return null
        }
        val deadlineNanos = Math.addExact(scheduledAtNanos, requestTimeoutNanos)
        val request = OpenLoopRequestState(
            commandId = commandId,
            cohort = cohort,
            scheduledAtNanos = scheduledAtNanos,
            admittedAtNanos = admittedAtNanos,
            deadlineNanos = deadlineNanos,
            owner = this,
        )
        check(active.putIfAbsent(commandId, request) == null) {
            "Duplicate active command ID: $commandId"
        }
        deadlineWheel?.add(
            entry = request,
            nowNanos = admittedAtNanos,
            onExpired = { expired ->
                expired.timeoutIfExpired(System.nanoTime())
            },
        )?.let(request::setDeadlineRegistration)
        counters(cohort).admitted.increment()
        if (cohort == ArrivalCohort.MEASUREMENT) {
            generatorLag.record(admittedAtNanos - scheduledAtNanos)
        }
        return request
    }

    fun captureForSentProbe(commandId: String): OpenLoopRequestState? =
        active[commandId]

    fun serverSendStarted(commandId: String): OpenLoopServerTicket? =
        serverTracker?.sendStarted(commandId)

    fun serverHandlerStarted(commandId: String): OpenLoopServerTicket? =
        serverTracker?.handlerStarted(commandId)

    fun sampleClientInFlight(nowNanos: Long) {
        inFlightSamples.record(nowNanos, currentClientInFlight.get())
    }

    fun expireDue(nowNanos: Long) {
        val activeDeadlineWheel = deadlineWheel ?: return
        val startedAtNanos = System.nanoTime()
        val sweep = activeDeadlineWheel.expireDue(nowNanos) { request ->
            request.timeoutIfExpired(nowNanos)
        }
        deadlineSweepStats.record(
            sweep = sweep,
            durationNanos = System.nanoTime() - startedAtNanos,
        )
    }

    fun forceCancelRemaining(nowNanos: Long) {
        active.values.forEach { request ->
            request.forceCancel(nowNanos)
        }
    }

    fun currentClientInFlight(): Int = currentClientInFlight.get()

    fun activeClientRequestCount(): Int = active.size

    fun currentServerOutstanding(): Int = serverTracker?.currentOutstanding() ?: 0

    fun activeServerTicketCount(): Int = serverTracker?.activeTicketCount() ?: 0

    fun forceCloseServerRemaining(): Int = serverTracker?.forceCloseRemaining() ?: 0

    fun warmupSnapshot(): OpenLoopOutcomeSnapshot = warmup.snapshot()

    fun measurementSnapshot(): OpenLoopOutcomeSnapshot = measurement.snapshot()

    fun generatorFidelityViolations(
        maxMissedRatio: Double,
        maxLagP99Micros: Double,
    ): List<String> =
        measurementSnapshot().generatorFidelityViolations(
            generatorLagP99Micros = generatorLag.summary().p99,
            maxMissedRatio = maxMissedRatio,
            maxLagP99Micros = maxLagP99Micros,
            requireLagSample = observationMode.generatorLatencyEnabled,
        )

    fun invariantViolations(): List<String> =
        buildList {
            warmupSnapshot().invariantViolations().forEach { violation ->
                add("warmup: $violation")
            }
            measurementSnapshot().invariantViolations().forEach { violation ->
                add("measurement: $violation")
            }
            val clientInFlight = currentClientInFlight()
            if (clientInFlight != 0) {
                add("final clientInFlight[$clientInFlight] != 0")
            }
            val activeClientCount = activeClientRequestCount()
            if (activeClientCount != 0) {
                add("final activeClientRequestCount[$activeClientCount] != 0")
            }
            val deadlineWheelSize = deadlineWheel?.size ?: 0
            if (deadlineWheelSize != 0) {
                add("final deadlineWheelSize[$deadlineWheelSize] != 0")
            }
            val measurementSnapshot = measurementSnapshot()
            if (observationMode.generatorLatencyEnabled) {
                requireSampleCount(
                    metric = "generatorLag",
                    actual = generatorLag.summary().count,
                    expected = measurementSnapshot.admitted,
                )
            }
            if (observationMode.serviceLatencyEnabled) {
                requireSampleCount(
                    metric = "scheduledToSent",
                    actual = scheduledToSent.summary().count,
                    expected = measurementSnapshot.sent,
                )
                requireSampleCount(
                    metric = "scheduledToProcessed",
                    actual = scheduledToProcessed.summary().count,
                    expected = measurementSnapshot.processedByDeadline,
                )
                requireSampleCount(
                    metric = "admittedToProcessed",
                    actual = admittedToProcessed.summary().count,
                    expected = measurementSnapshot.processedByDeadline,
                )
                requireSampleCount(
                    metric = "sentToProcessedSettled",
                    actual = sentToProcessed.summary().count +
                        sentObservedAfterProcessed.sum(),
                    expected = measurementSnapshot.processedByDeadline,
                )
            }
            serverTracker?.snapshot()?.invariantViolations()?.forEach { violation ->
                add("server: $violation")
            }
        }

    private fun MutableList<String>.requireSampleCount(
        metric: String,
        actual: Long,
        expected: Long,
    ) {
        if (actual != expected) {
            add("$metric sampleCount[$actual] != expected[$expected]")
        }
    }

    fun reportMap(measurementDurationNanos: Long): Map<String, Any?> {
        val warmupSnapshot = warmupSnapshot()
        val measurementSnapshot = measurementSnapshot()
        val measurementSeconds = measurementDurationNanos.toDouble() / NANOS_PER_SECOND
        val processedYield =
            ratio(measurementSnapshot.processedByDeadline, measurementSnapshot.planned)
        val allOfferedConditionalRank =
            if (
                observationMode.serviceLatencyEnabled &&
                processedYield > ALL_OFFERED_P99_YIELD_THRESHOLD
            ) {
                ALL_OFFERED_P99_PERCENTILE / processedYield
            } else {
                null
            }
        return linkedMapOf(
            "observation" to linkedMapOf(
                "mode" to observationMode.name,
                "fullObservationCoverage" to observationMode.fullObservationCoverage,
                "validityScope" to if (observationMode.fullObservationCoverage) {
                    "full-observation-protocol"
                } else {
                    "mode-local-diagnostic"
                },
                "deadlineWheelEnabled" to observationMode.deadlineWheelEnabled,
                "serverTrackerEnabled" to observationMode.serverTrackerEnabled,
                "generatorLatencyEnabled" to observationMode.generatorLatencyEnabled,
                "serviceLatencyEnabled" to observationMode.serviceLatencyEnabled,
                "limitations" to observationLimitations(),
            ),
            "cohorts" to linkedMapOf(
                "warmup" to warmupSnapshot.toMap(),
                "measurement" to measurementSnapshot.toMap(),
            ),
            "measurementWindow" to linkedMapOf(
                "sent" to warmup.sentInMeasurementWindow.sum() +
                    measurement.sentInMeasurementWindow.sum(),
                "processedByDeadline" to warmup.processedInMeasurementWindow.sum() +
                    measurement.processedInMeasurementWindow.sum(),
                "terminal" to warmup.terminalInMeasurementWindow.sum() +
                    measurement.terminalInMeasurementWindow.sum(),
                "sentPerSecond" to (
                    warmup.sentInMeasurementWindow.sum() +
                        measurement.sentInMeasurementWindow.sum()
                    ) / measurementSeconds,
                "processedByDeadlinePerSecond" to
                    (
                        warmup.processedInMeasurementWindow.sum() +
                            measurement.processedInMeasurementWindow.sum()
                        ) / measurementSeconds,
                "terminalPerSecond" to (
                    warmup.terminalInMeasurementWindow.sum() +
                        measurement.terminalInMeasurementWindow.sum()
                    ) / measurementSeconds,
                "byArrivalCohort" to linkedMapOf(
                    "warmup" to warmup.measurementWindowMap(),
                    "measurement" to measurement.measurementWindowMap(),
                ),
            ),
            "measurementYield" to linkedMapOf(
                "admittedPerPlanned" to ratio(
                    measurementSnapshot.admitted,
                    measurementSnapshot.planned,
                ),
                "sentByDeadlinePerPlanned" to ratio(
                    measurementSnapshot.sentByDeadline,
                    measurementSnapshot.planned,
                ),
                "processedByDeadlinePerPlanned" to processedYield,
                "shedPerPlanned" to ratio(
                    measurementSnapshot.shedAtMaxInFlight,
                    measurementSnapshot.planned,
                ),
                "timeoutPerPlanned" to ratio(
                    measurementSnapshot.timedOut,
                    measurementSnapshot.planned,
                ),
                "allOfferedScheduledToProcessedP99Micros" to
                    allOfferedConditionalRank?.let(scheduledToProcessed::percentile),
                "allOfferedP99ConditionalPercentile" to allOfferedConditionalRank,
                "allOfferedP99Status" to when {
                    !observationMode.serviceLatencyEnabled ->
                        "disabled-by-diagnostic-observation-mode"

                    allOfferedConditionalRank == null ->
                        "not-finite-failure-mass-at-or-above-one-percent"

                    else -> "failure-mass-adjusted-empirical-order-statistic"
                },
            ),
            "measurementLatencyMicros" to linkedMapOf(
                "generatorLagForAdmitted" to generatorLag.summary().toMap(),
                "scheduledToSentForSent" to scheduledToSent.summary().toMap(),
                "scheduledToProcessedForProcessedByDeadline" to
                    scheduledToProcessed.summary().toMap(),
                "admittedToProcessedForProcessedByDeadline" to
                    admittedToProcessed.summary().toMap(),
                "sentToProcessedForCausallyOrderedObservations" to
                    sentToProcessed.summary().toMap(),
                "sentObservedAfterProcessed" to sentObservedAfterProcessed.sum(),
            ),
            "clientInFlight" to inFlightSamples.toMap(clientRunPeakInFlight.get()),
            "serverOutstanding" to (
                serverTracker?.snapshot()?.toMap()
                    ?: linkedMapOf("enabled" to false)
                ),
            "timeoutObserver" to deadlineSweepStats.toMap(
                enabled = observationMode.deadlineWheelEnabled,
                finalWheelSize = deadlineWheel?.size ?: 0,
            ),
            "commandFailureStages" to commandFailureStages.entries
                .sortedBy { it.key }
                .associate { (stage, count) -> stage to count.sum() },
            "invariants" to linkedMapOf(
                "valid" to invariantViolations().isEmpty(),
                "violations" to invariantViolations(),
                "finalClientInFlight" to currentClientInFlight(),
                "finalActiveClientRequests" to activeClientRequestCount(),
                "finalDeadlineRegistrations" to (deadlineWheel?.size ?: 0),
                "finalServerOutstanding" to currentServerOutstanding(),
                "finalActiveServerTickets" to activeServerTicketCount(),
            ),
        )
    }

    private fun observationLimitations(): List<String> =
        buildList {
            if (!observationMode.deadlineWheelEnabled) {
                add(
                    "deadline expiry no longer releases client in-flight capacity; " +
                        "admission and residence semantics can differ when requests cross deadlines"
                )
                add(
                    "NO_DEADLINE_WHEEL is a semantic ablation and is not guaranteed " +
                        "to be a throughput upper bound"
                )
            }
            if (!observationMode.serverTrackerEnabled) {
                add(
                    "server ticket state, terminal drain, and ticket invariants are disabled; " +
                        "bus/handler wrappers and terminal hooks remain installed"
                )
            }
            if (!observationMode.generatorLatencyEnabled) {
                add(
                    "generator lag SampleBuffer writes, percentiles, and lag validity gate " +
                        "are disabled; arrival clocks and request state remain"
                )
            }
            if (!observationMode.serviceLatencyEnabled) {
                add(
                    "service latency SampleBuffer writes and percentiles are disabled; " +
                        "surrounding probes, hooks, timestamps, and request state remain"
                )
            }
        }

    private fun tryAcquireInFlight(): Boolean {
        while (true) {
            val current = currentClientInFlight.get()
            if (current >= maxInFlight) {
                return false
            }
            val updated = current + 1
            if (currentClientInFlight.compareAndSet(current, updated)) {
                clientRunPeakInFlight.accumulateAndGet(updated, ::maxOf)
                return true
            }
        }
    }

    private fun counters(cohort: ArrivalCohort): MutableCohortCounters =
        when (cohort) {
            ArrivalCohort.WARMUP -> warmup
            ArrivalCohort.MEASUREMENT -> measurement
        }

    internal fun recordSent(
        request: OpenLoopRequestState,
        nowNanos: Long,
    ) {
        val counters = counters(request.cohort)
        counters.sent.increment()
        if (nowNanos < request.deadlineNanos) {
            counters.sentByDeadline.increment()
        }
        if (isInMeasurementWindow(nowNanos)) {
            counters.sentInMeasurementWindow.increment()
        }
        if (request.cohort == ArrivalCohort.MEASUREMENT) {
            scheduledToSent.record(nowNanos - request.scheduledAtNanos)
        }
    }

    internal fun removeDeadlineRegistration(
        registration: OpenLoopDeadlineRegistration<OpenLoopRequestState>,
    ) {
        deadlineWheel?.remove(registration)
    }

    internal fun finish(
        request: OpenLoopRequestState,
        outcome: TerminalOutcome,
        terminalAtNanos: Long,
        commandFailureStage: String? = null,
    ) {
        val counters = counters(request.cohort)
        when (outcome) {
            TerminalOutcome.PROCESSED_BY_DEADLINE -> {
                counters.processedByDeadline.increment()
                if (isInMeasurementWindow(terminalAtNanos)) {
                    counters.processedInMeasurementWindow.increment()
                }
                if (request.cohort == ArrivalCohort.MEASUREMENT) {
                    scheduledToProcessed.record(terminalAtNanos - request.scheduledAtNanos)
                    admittedToProcessed.record(terminalAtNanos - request.admittedAtNanos)
                }
            }

            TerminalOutcome.COMMAND_FAILED -> {
                counters.commandFailed.increment()
                commandFailureStage?.let { stage ->
                    commandFailureStages.computeIfAbsent(stage) { LongAdder() }.increment()
                }
            }

            TerminalOutcome.GATEWAY_FAILED -> counters.gatewayFailed.increment()
            TerminalOutcome.TIMED_OUT -> counters.timedOut.increment()
            TerminalOutcome.FORCED_CANCELLED -> counters.forcedCancelled.increment()
        }
        if (isInMeasurementWindow(terminalAtNanos)) {
            counters.terminalInMeasurementWindow.increment()
        }
        request.deadlineRegistration()?.let { registration ->
            deadlineWheel?.remove(registration)
        }
        check(active.remove(request.commandId, request)) {
            "Terminal request was not active: ${request.commandId}"
        }
        val remaining = currentClientInFlight.decrementAndGet()
        check(remaining >= 0) {
            "clientInFlight became negative after ${request.commandId}: $remaining"
        }
    }

    internal fun recordSentToProcessed(sentToProcessedNanos: Long) {
        if (!observationMode.serviceLatencyEnabled) {
            return
        }
        if (sentToProcessedNanos < 0) {
            sentObservedAfterProcessed.increment()
            return
        }
        sentToProcessed.record(sentToProcessedNanos)
    }

    private fun isInMeasurementWindow(nowNanos: Long): Boolean =
        nowNanos >= measurementStartNanos && nowNanos < measurementEndNanos

    private class MutableCohortCounters(
        private val planned: Long,
    ) {
        val generatorMissed = LongAdder()
        val generatorExpired = LongAdder()
        val shedAtMaxInFlight = LongAdder()
        val admitted = LongAdder()
        val sent = LongAdder()
        val sentByDeadline = LongAdder()
        val processedByDeadline = LongAdder()
        val commandFailed = LongAdder()
        val gatewayFailed = LongAdder()
        val timedOut = LongAdder()
        val forcedCancelled = LongAdder()
        val sentInMeasurementWindow = LongAdder()
        val processedInMeasurementWindow = LongAdder()
        val terminalInMeasurementWindow = LongAdder()

        fun snapshot(): OpenLoopOutcomeSnapshot =
            OpenLoopOutcomeSnapshot(
                planned = planned,
                generatorMissed = generatorMissed.sum(),
                generatorExpired = generatorExpired.sum(),
                shedAtMaxInFlight = shedAtMaxInFlight.sum(),
                admitted = admitted.sum(),
                sent = sent.sum(),
                sentByDeadline = sentByDeadline.sum(),
                processedByDeadline = processedByDeadline.sum(),
                commandFailed = commandFailed.sum(),
                gatewayFailed = gatewayFailed.sum(),
                timedOut = timedOut.sum(),
                forcedCancelled = forcedCancelled.sum(),
            )

        fun measurementWindowMap(): Map<String, Long> =
            linkedMapOf(
                "sent" to sentInMeasurementWindow.sum(),
                "processedByDeadline" to processedInMeasurementWindow.sum(),
                "terminal" to terminalInMeasurementWindow.sum(),
            )
    }

    private class InFlightSamples(
        private val measurementStartNanos: Long,
        private val measurementEndNanos: Long,
    ) {
        private val total = LongAdder()
        private val count = LongAdder()
        private val firstDecileTotal = LongAdder()
        private val firstDecileCount = LongAdder()
        private val lastDecileTotal = LongAdder()
        private val lastDecileCount = LongAdder()
        private val measurementPeak = AtomicInteger()
        private val decileNanos = (measurementEndNanos - measurementStartNanos) / 10

        fun record(
            nowNanos: Long,
            inFlight: Int,
        ) {
            if (nowNanos < measurementStartNanos || nowNanos >= measurementEndNanos) {
                return
            }
            total.add(inFlight.toLong())
            count.increment()
            measurementPeak.accumulateAndGet(inFlight, ::maxOf)
            if (nowNanos < measurementStartNanos + decileNanos) {
                firstDecileTotal.add(inFlight.toLong())
                firstDecileCount.increment()
            }
            if (nowNanos >= measurementEndNanos - decileNanos) {
                lastDecileTotal.add(inFlight.toLong())
                lastDecileCount.increment()
            }
        }

        fun toMap(runPeak: Int): Map<String, Any?> =
            linkedMapOf(
                "runPeak" to runPeak,
                "measurementPeak" to measurementPeak.get(),
                "sampleCount" to count.sum(),
                "mean" to mean(total.sum(), count.sum()),
                "firstDecileMean" to mean(firstDecileTotal.sum(), firstDecileCount.sum()),
                "lastDecileMean" to mean(lastDecileTotal.sum(), lastDecileCount.sum()),
                "lastMinusFirstDecileMean" to differenceOfMeans(
                    lastTotal = lastDecileTotal.sum(),
                    lastCount = lastDecileCount.sum(),
                    firstTotal = firstDecileTotal.sum(),
                    firstCount = firstDecileCount.sum(),
                ),
            )
    }
}

internal class OpenLoopRequestState(
    val commandId: String,
    val cohort: ArrivalCohort,
    val scheduledAtNanos: Long,
    val admittedAtNanos: Long,
    val deadlineNanos: Long,
    private val owner: OpenLoopRunRecorder,
) {
    private val sentAt = AtomicLong(UNRECORDED_NANOS)
    private val processedAt = AtomicLong(UNRECORDED_NANOS)
    private val sentToProcessedSettled = java.util.concurrent.atomic.AtomicBoolean()
    private val terminalOutcome = AtomicReference<TerminalOutcome?>()
    private val subscription = AtomicReference<Disposable?>()
    private val deadlineRegistration =
        AtomicReference<OpenLoopDeadlineRegistration<OpenLoopRequestState>?>()

    fun setDeadlineRegistration(
        registration: OpenLoopDeadlineRegistration<OpenLoopRequestState>,
    ) {
        check(deadlineRegistration.compareAndSet(null, registration)) {
            "Deadline registration was already set for $commandId."
        }
        if (terminalOutcome.get() != null) {
            owner.removeDeadlineRegistration(registration)
        }
    }

    fun setSubscription(disposable: Disposable) {
        check(subscription.compareAndSet(null, disposable)) {
            "Subscription was already set for $commandId."
        }
        if (terminalOutcome.get() != null) {
            disposable.dispose()
        }
    }

    fun recordSent(nowNanos: Long) {
        if (sentAt.compareAndSet(UNRECORDED_NANOS, nowNanos)) {
            owner.recordSent(this, nowNanos)
            settleSentToProcessed()
        }
    }

    fun processed(nowNanos: Long) {
        if (nowNanos >= deadlineNanos) {
            finish(TerminalOutcome.TIMED_OUT, nowNanos, cancelSubscription = true)
            return
        }
        finish(TerminalOutcome.PROCESSED_BY_DEADLINE, nowNanos)
    }

    fun failed(
        throwable: Throwable,
        nowNanos: Long,
    ) {
        if (nowNanos >= deadlineNanos) {
            finish(TerminalOutcome.TIMED_OUT, nowNanos, cancelSubscription = true)
            return
        }
        if (throwable is CommandResultException) {
            finish(
                outcome = TerminalOutcome.COMMAND_FAILED,
                terminalAtNanos = nowNanos,
                commandFailureStage = throwable.commandResult.stage.name,
            )
        } else {
            finish(TerminalOutcome.GATEWAY_FAILED, nowNanos)
        }
    }

    fun completedWithoutResult(nowNanos: Long) {
        if (nowNanos >= deadlineNanos) {
            finish(TerminalOutcome.TIMED_OUT, nowNanos, cancelSubscription = true)
        } else {
            finish(TerminalOutcome.GATEWAY_FAILED, nowNanos)
        }
    }

    fun timeoutIfExpired(nowNanos: Long) {
        if (nowNanos >= deadlineNanos) {
            finish(TerminalOutcome.TIMED_OUT, nowNanos, cancelSubscription = true)
        }
    }

    fun forceCancel(nowNanos: Long) {
        finish(TerminalOutcome.FORCED_CANCELLED, nowNanos, cancelSubscription = true)
    }

    fun isTerminal(): Boolean = terminalOutcome.get() != null

    fun deadlineRegistration(): OpenLoopDeadlineRegistration<OpenLoopRequestState>? =
        deadlineRegistration.get()

    private fun finish(
        outcome: TerminalOutcome,
        terminalAtNanos: Long,
        commandFailureStage: String? = null,
        cancelSubscription: Boolean = false,
    ): Boolean {
        if (!terminalOutcome.compareAndSet(null, outcome)) {
            return false
        }
        if (outcome == TerminalOutcome.PROCESSED_BY_DEADLINE) {
            check(processedAt.compareAndSet(UNRECORDED_NANOS, terminalAtNanos)) {
                "Processed timestamp was already set for $commandId."
            }
        }
        owner.finish(
            request = this,
            outcome = outcome,
            terminalAtNanos = terminalAtNanos,
            commandFailureStage = commandFailureStage,
        )
        if (cancelSubscription) {
            subscription.get()?.dispose()
        }
        if (outcome == TerminalOutcome.PROCESSED_BY_DEADLINE) {
            settleSentToProcessed()
        }
        return true
    }

    private fun settleSentToProcessed() {
        if (cohort != ArrivalCohort.MEASUREMENT) {
            return
        }
        val sentAtNanos = sentAt.get()
        val processedAtNanos = processedAt.get()
        if (sentAtNanos == UNRECORDED_NANOS || processedAtNanos == UNRECORDED_NANOS) {
            return
        }
        if (sentToProcessedSettled.compareAndSet(false, true)) {
            owner.recordSentToProcessed(processedAtNanos - sentAtNanos)
        }
    }

    private companion object {
        const val UNRECORDED_NANOS: Long = -1
    }
}

internal class ConcurrentLatencyRecorder(
    private val enabled: Boolean,
) {
    private val buffers =
        if (enabled) {
            ConcurrentLinkedQueue<SampleBuffer>()
        } else {
            null
        }
    private val threadLocal =
        if (enabled) {
            ThreadLocal.withInitial {
                SampleBuffer().also(requireNotNull(buffers)::add)
            }
        } else {
            null
        }

    fun record(nanos: Long) {
        require(nanos >= 0) {
            "Latency must not be negative: $nanos"
        }
        threadLocal?.get()?.add(nanos)
    }

    fun summary(): LatencySummary {
        if (!enabled) {
            return LatencySummary.DISABLED
        }
        val statistics = statistics() ?: return LatencySummary.EMPTY
        return LatencySummary(
            enabled = true,
            count = statistics.n,
            mean = statistics.mean,
            minimum = statistics.min,
            p50 = statistics.getPercentile(50.0),
            p95 = statistics.getPercentile(95.0),
            p99 = statistics.getPercentile(99.0),
            maximum = statistics.max,
        )
    }

    fun percentile(percentile: Double): Double? {
        require(percentile in 0.0..100.0) {
            "Percentile must be between 0 and 100: $percentile"
        }
        if (!enabled) {
            return null
        }
        return statistics()?.getPercentile(percentile)
    }

    private fun statistics(): org.openjdk.jmh.util.Statistics? {
        val merged = SampleBuffer()
        requireNotNull(buffers).forEach(merged::addAll)
        if (merged.count() == 0) {
            return null
        }
        return merged.getStatistics(NANOS_TO_MICROS)
    }
}

private class DeadlineSweepStats(
    private val resolutionNanos: Long,
) {
    private val sweepCount = LongAdder()
    private val elapsedTicks = LongAdder()
    private val bucketsVisited = LongAdder()
    private val candidatesVisited = LongAdder()
    private val expired = LongAdder()
    private val totalDurationNanos = LongAdder()
    private val maxDurationNanos = AtomicLong()

    fun record(
        sweep: OpenLoopDeadlineSweep,
        durationNanos: Long,
    ) {
        sweepCount.increment()
        elapsedTicks.add(sweep.elapsedTicks)
        bucketsVisited.add(sweep.bucketsVisited)
        candidatesVisited.add(sweep.candidatesVisited)
        expired.add(sweep.expired)
        totalDurationNanos.add(durationNanos)
        maxDurationNanos.accumulateAndGet(durationNanos, ::maxOf)
    }

    fun toMap(
        enabled: Boolean,
        finalWheelSize: Int,
    ): Map<String, Any?> =
        linkedMapOf(
            "enabled" to enabled,
            "resolutionNanos" to resolutionNanos,
            "sweepCount" to sweepCount.sum(),
            "elapsedTicks" to elapsedTicks.sum(),
            "bucketsVisited" to bucketsVisited.sum(),
            "candidatesVisited" to candidatesVisited.sum(),
            "expired" to expired.sum(),
            "meanSweepMicros" to mean(totalDurationNanos.sum(), sweepCount.sum())
                ?.div(NANOS_PER_MICRO),
            "maxSweepMicros" to maxDurationNanos.get().toDouble() / NANOS_PER_MICRO,
            "finalWheelSize" to finalWheelSize,
        )
}

internal data class LatencySummary(
    val enabled: Boolean,
    val count: Long,
    val mean: Double?,
    val minimum: Double?,
    val p50: Double?,
    val p95: Double?,
    val p99: Double?,
    val maximum: Double?,
) {
    fun toMap(): Map<String, Any?> =
        linkedMapOf(
            "enabled" to enabled,
            "count" to count,
            "mean" to mean,
            "min" to minimum,
            "p50" to p50,
            "p95" to p95,
            "p99" to p99,
            "max" to maximum,
        )

    companion object {
        val EMPTY = LatencySummary(
            enabled = true,
            count = 0,
            mean = null,
            minimum = null,
            p50 = null,
            p95 = null,
            p99 = null,
            maximum = null,
        )
        val DISABLED = EMPTY.copy(enabled = false)
    }
}

private fun OpenLoopOutcomeSnapshot.toMap(): Map<String, Long> =
    linkedMapOf(
        "planned" to planned,
        "generatorMissed" to generatorMissed,
        "generatorExpired" to generatorExpired,
        "shedAtMaxInFlight" to shedAtMaxInFlight,
        "admitted" to admitted,
        "sent" to sent,
        "sentByDeadline" to sentByDeadline,
        "processedByDeadline" to processedByDeadline,
        "commandFailed" to commandFailed,
        "gatewayFailed" to gatewayFailed,
        "timedOut" to timedOut,
        "forcedCancelled" to forcedCancelled,
        "terminal" to terminal,
    )

private fun OpenLoopServerSnapshot.toMap(): Map<String, Any?> =
    linkedMapOf(
        "enabled" to true,
        "created" to created,
        "sendSucceeded" to sendSucceeded,
        "sendFailed" to sendFailed,
        "sendCancelled" to sendCancelled,
        "handlerStarted" to handlerStarted,
        "handlerTerminated" to handlerTerminated,
        "handlerFailed" to handlerFailed,
        "handlerCompleted" to handlerCompleted,
        "handlerErrored" to handlerErrored,
        "handlerCancelled" to handlerCancelled,
        "naturallyClosed" to naturallyClosed,
        "forcedClosed" to forcedClosed,
        "runPeak" to peakOutstanding,
        "finalOutstanding" to currentOutstanding,
        "finalActiveTickets" to activeTickets,
        "missingHandlerTicket" to missingHandlerTicket,
        "duplicateTransitions" to duplicateTransitions,
        "lateTransitions" to lateTransitions,
        "handlerTerminalSignals" to handlerTerminalSignals,
    )

private fun ratio(
    numerator: Long,
    denominator: Long,
): Double =
    if (denominator == 0L) {
        0.0
    } else {
        numerator.toDouble() / denominator
    }

private fun mean(
    total: Long,
    count: Long,
): Double? =
    if (count == 0L) {
        null
    } else {
        total.toDouble() / count
    }

private fun differenceOfMeans(
    lastTotal: Long,
    lastCount: Long,
    firstTotal: Long,
    firstCount: Long,
): Double? {
    val lastMean = mean(lastTotal, lastCount) ?: return null
    val firstMean = mean(firstTotal, firstCount) ?: return null
    return lastMean - firstMean
}

private const val NANOS_PER_SECOND: Double = 1_000_000_000.0
private const val NANOS_PER_MICRO: Double = 1_000.0
private const val NANOS_TO_MICROS: Double = 1.0 / 1_000.0
private const val ALL_OFFERED_P99_PERCENTILE: Double = 99.0
private const val ALL_OFFERED_P99_YIELD_THRESHOLD: Double = 0.99
