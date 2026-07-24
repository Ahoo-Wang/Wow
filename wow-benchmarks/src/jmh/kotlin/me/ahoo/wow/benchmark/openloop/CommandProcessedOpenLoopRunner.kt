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

import me.ahoo.wow.BenchmarkAggregateSchedulerSupplier
import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.benchmark.fixture.BenchmarkAggregateIdPlacement
import me.ahoo.wow.benchmark.fixture.BenchmarkAggregateIdPlan
import me.ahoo.wow.benchmark.fixture.BenchmarkAggregates
import me.ahoo.wow.benchmark.fixture.BenchmarkCommands
import me.ahoo.wow.benchmark.fixture.BenchmarkIds
import me.ahoo.wow.benchmark.scenario.CommandDispatcherScenario
import me.ahoo.wow.command.CommandBus
import me.ahoo.wow.command.CommandResultException
import me.ahoo.wow.command.InMemoryCommandBus
import me.ahoo.wow.command.ServerCommandExchange
import me.ahoo.wow.command.validation.NoOpValidator
import me.ahoo.wow.command.wait.CommandStage
import me.ahoo.wow.command.wait.CommandWait
import me.ahoo.wow.command.wait.DefaultWaitCoordinator
import me.ahoo.wow.command.wait.LocalCommandWaitNotifier
import me.ahoo.wow.event.InMemoryDomainEventBus
import me.ahoo.wow.eventsourcing.NoopEventStore
import me.ahoo.wow.eventsourcing.snapshot.InMemorySnapshotStore
import me.ahoo.wow.eventsourcing.state.InMemoryStateEventBus
import me.ahoo.wow.infra.idempotency.DefaultAggregateIdempotencyCheckerProvider
import me.ahoo.wow.infra.idempotency.NoOpIdempotencyChecker
import me.ahoo.wow.modeling.command.dispatcher.CommandHandler
import me.ahoo.wow.modeling.materialize
import me.ahoo.wow.serialization.toPrettyJson
import reactor.core.publisher.Mono
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import kotlin.math.roundToLong

/**
 * Independent bounded-open-loop runner for the command PROCESSED path.
 *
 * This intentionally does not run inside a JMH benchmark method. Arrivals are
 * scheduled from absolute time, so request completion cannot pace the producer.
 */
object CommandProcessedOpenLoopRunner {
    @JvmStatic
    fun main(arguments: Array<String>) {
        val config = CommandProcessedOpenLoopConfig.parse(arguments)
        BenchmarkIds.installDeterministicGlobalIdGenerator()
        val aggregateIdPlan = BenchmarkAggregateIdPlan.create(
            cardinality = config.aggregateCardinality,
            stripeCount = config.stripeCount,
            placement = BenchmarkAggregateIdPlacement.BALANCED,
        )
        val startedAt = Instant.now()
        val totalPlan = OpenLoopPlans.createTotal(config)
        val warmupArrivalCount = OpenLoopPlans.warmupArrivalCount(config)
        val measurementArrivalCount = totalPlan.arrivalCount - warmupArrivalCount

        val rawCommandBus = InMemoryCommandBus()
        val recorderReference = AtomicReference<OpenLoopRunRecorder?>()
        val probedCommandBus = SentProbingCommandBus(rawCommandBus, recorderReference::get)
        val waitCoordinator = DefaultWaitCoordinator()
        val scenario = CommandDispatcherScenario.create(
            commandBus = probedCommandBus,
            eventStore = NoopEventStore,
            snapshotRepository = InMemorySnapshotStore(),
            domainEventBus = InMemoryDomainEventBus(),
            stateEventBus = InMemoryStateEventBus(),
            schedulerSupplier = BenchmarkAggregateSchedulerSupplier(config.schedulerPoolSize),
            stripeCount = config.stripeCount,
            validator = NoOpValidator,
            idempotencyCheckerProvider = DefaultAggregateIdempotencyCheckerProvider {
                NoOpIdempotencyChecker
            },
            namedAggregate = BenchmarkAggregates.cartMetadata.namedAggregate.materialize(),
            waitCoordinator = waitCoordinator,
            commandWaitNotifier = LocalCommandWaitNotifier(waitCoordinator),
            commandHandlerDecorator = { delegate ->
                ServerCompletionTrackingCommandHandler(
                    delegate = delegate,
                    recorder = recorderReference::get,
                )
            },
        )

        val run = try {
            awaitCommandSubscriber(rawCommandBus)
            runSmokeCommand(scenario)

            val benchmarkStartNanos = Math.addExact(System.nanoTime(), config.startLeadNanos)
            val measurementStartNanos = Math.addExact(benchmarkStartNanos, config.warmupNanos)
            val measurementEndNanos = Math.addExact(
                measurementStartNanos,
                config.measurementNanos,
            )
            val recorder = OpenLoopRunRecorder(
                maxInFlight = config.maxInFlight,
                requestTimeoutNanos = config.requestTimeoutNanos,
                observationMode = config.observationMode,
                timeoutResolutionNanos = config.watchdogIntervalNanos,
                initialTimeNanos = benchmarkStartNanos,
                measurementStartNanos = measurementStartNanos,
                measurementEndNanos = measurementEndNanos,
                warmupPlanned = warmupArrivalCount,
                measurementPlanned = measurementArrivalCount,
            )
            check(recorderReference.compareAndSet(null, recorder)) {
                "Open-loop recorder was already installed."
            }
            executeLoad(
                config = config,
                totalPlan = totalPlan,
                warmupArrivalCount = warmupArrivalCount,
                benchmarkStartNanos = benchmarkStartNanos,
                measurementStartNanos = measurementStartNanos,
                measurementEndNanos = measurementEndNanos,
                aggregateIdPlan = aggregateIdPlan,
                scenario = scenario,
                recorder = recorder,
            )
        } finally {
            scenario.close()
        }
        run.recorder.forceCloseServerRemaining()

        val completedAt = Instant.now()
        val invariantViolations = buildList {
            addAll(run.recorder.invariantViolations())
            if (run.serverOutstandingBeforeClose != 0) {
                add(
                    "serverOutstandingBeforeScenarioClose" +
                        "[${run.serverOutstandingBeforeClose}] != 0"
                )
            }
            addAll(
                run.recorder.generatorFidelityViolations(
                    maxMissedRatio = config.maxGeneratorMissedRatio,
                    maxLagP99Micros =
                        TimeUnit.NANOSECONDS.toMicros(config.maxGeneratorLagP99Nanos).toDouble(),
                )
            )
            val forcedCancelled =
                run.recorder.warmupSnapshot().forcedCancelled +
                    run.recorder.measurementSnapshot().forcedCancelled
            if (forcedCancelled != 0L) {
                add("forcedCancelled[$forcedCancelled] != 0")
            }
        }
        val report = buildReport(
            config = config,
            aggregateIdPlan = aggregateIdPlan,
            startedAt = startedAt,
            completedAt = completedAt,
            run = run,
            invariantViolations = invariantViolations,
        )
        val humanReport = buildHumanReport(report, config, run)
        writeAtomically(config.resultPath, report.toPrettyJson())
        writeAtomically(config.humanPath, humanReport)
        println(humanReport)

        check(invariantViolations.isEmpty()) {
            "Open-loop accounting invariant failure: ${invariantViolations.joinToString()}"
        }
        check(run.producerFailures.isEmpty()) {
            "Open-loop producer failure(s): ${run.producerFailures.joinToString { it.toString() }}"
        }
    }

    private fun executeLoad(
        config: CommandProcessedOpenLoopConfig,
        totalPlan: me.ahoo.wow.benchmark.workload.OpenLoopArrivalPlan,
        warmupArrivalCount: Long,
        benchmarkStartNanos: Long,
        measurementStartNanos: Long,
        measurementEndNanos: Long,
        aggregateIdPlan: BenchmarkAggregateIdPlan,
        scenario: CommandDispatcherScenario,
        recorder: OpenLoopRunRecorder,
    ): OpenLoopExecution {
        val producerFailures = ConcurrentLinkedQueue<Throwable>()
        val producersDone = CountDownLatch(config.producerCount)
        val producerExecutor = Executors.newFixedThreadPool(
            config.producerCount,
            NamedThreadFactory("command-open-loop-producer"),
        )
        val watchdogExecutor = Executors.newSingleThreadScheduledExecutor(
            NamedThreadFactory("command-open-loop-watchdog", daemon = true),
        )
        val watchdogTask = watchdogExecutor.scheduleWithFixedDelay(
            {
                runCatching {
                    val nowNanos = System.nanoTime()
                    recorder.sampleClientInFlight(nowNanos)
                    recorder.expireDue(nowNanos)
                }.onFailure(producerFailures::add)
            },
            0,
            config.watchdogIntervalNanos,
            TimeUnit.NANOSECONDS,
        )

        repeat(config.producerCount) { producerIndex ->
            producerExecutor.execute {
                try {
                    runProducer(
                        producerIndex = producerIndex,
                        config = config,
                        totalPlan = totalPlan,
                        warmupArrivalCount = warmupArrivalCount,
                        benchmarkStartNanos = benchmarkStartNanos,
                        measurementStartNanos = measurementStartNanos,
                        measurementEndNanos = measurementEndNanos,
                        aggregateIdPlan = aggregateIdPlan,
                        scenario = scenario,
                        recorder = recorder,
                    )
                } catch (failure: Throwable) {
                    producerFailures.add(failure)
                } finally {
                    producersDone.countDown()
                }
            }
        }
        producerExecutor.shutdown()

        val producerDeadlineNanos = Math.addExact(
            measurementEndNanos,
            PRODUCER_COMPLETION_GRACE_NANOS,
        )
        awaitLatchUntil(producersDone, producerDeadlineNanos)
        if (producersDone.count != 0L) {
            producerFailures.add(
                IllegalStateException(
                    "${producersDone.count} producer(s) did not stop after the measurement window."
                )
            )
            producerExecutor.shutdownNow()
        }

        val drainDeadlineNanos = Math.addExact(
            measurementEndNanos,
            Math.addExact(
                config.requestTimeoutNanos,
                Math.multiplyExact(
                    config.watchdogIntervalNanos,
                    WATCHDOG_DRAIN_INTERVALS,
                ),
            ),
        )
        while (
            recorder.currentClientInFlight() > 0 &&
            System.nanoTime() < drainDeadlineNanos
        ) {
            LockSupport.parkNanos(DRAIN_POLL_NANOS)
        }
        if (recorder.currentClientInFlight() > 0) {
            recorder.forceCancelRemaining(System.nanoTime())
        }
        val serverDrainDeadlineNanos = Math.addExact(
            drainDeadlineNanos,
            SERVER_DRAIN_GRACE_NANOS,
        )
        while (
            recorder.currentServerOutstanding() > 0 &&
            System.nanoTime() < serverDrainDeadlineNanos
        ) {
            LockSupport.parkNanos(DRAIN_POLL_NANOS)
        }
        val serverOutstandingBeforeClose = recorder.currentServerOutstanding()
        watchdogTask.cancel(false)
        watchdogExecutor.shutdownNow()
        producerExecutor.awaitTermination(5, TimeUnit.SECONDS)
        watchdogExecutor.awaitTermination(5, TimeUnit.SECONDS)

        return OpenLoopExecution(
            recorder = recorder,
            producerFailures = producerFailures.toList(),
            benchmarkStartNanos = benchmarkStartNanos,
            measurementStartNanos = measurementStartNanos,
            measurementEndNanos = measurementEndNanos,
            drainCompletedNanos = System.nanoTime(),
            serverOutstandingBeforeClose = serverOutstandingBeforeClose,
        )
    }

    private fun runProducer(
        producerIndex: Int,
        config: CommandProcessedOpenLoopConfig,
        totalPlan: me.ahoo.wow.benchmark.workload.OpenLoopArrivalPlan,
        warmupArrivalCount: Long,
        benchmarkStartNanos: Long,
        measurementStartNanos: Long,
        measurementEndNanos: Long,
        aggregateIdPlan: BenchmarkAggregateIdPlan,
        scenario: CommandDispatcherScenario,
        recorder: OpenLoopRunRecorder,
    ) {
        var sequence = totalPlan.firstSequence(producerIndex)
        while (totalPlan.contains(sequence)) {
            val cohort =
                if (sequence < warmupArrivalCount) {
                    ArrivalCohort.WARMUP
                } else {
                    ArrivalCohort.MEASUREMENT
                }
            val cohortEndNanos =
                if (cohort == ArrivalCohort.WARMUP) {
                    measurementStartNanos
                } else {
                    measurementEndNanos
                }
            val scheduledAtNanos = Math.addExact(
                benchmarkStartNanos,
                totalPlan.scheduledOffsetNanos(sequence),
            )
            if (System.nanoTime() >= cohortEndNanos) {
                sequence = skipRemainingCohort(
                    sequence = sequence,
                    cohort = cohort,
                    warmupArrivalCount = warmupArrivalCount,
                    totalArrivalCount = totalPlan.arrivalCount,
                    producerCount = config.producerCount,
                    recorder = recorder,
                )
                continue
            }
            awaitScheduledTime(scheduledAtNanos)
            val beforeCreateNanos = System.nanoTime()
            if (beforeCreateNanos >= cohortEndNanos) {
                sequence = skipRemainingCohort(
                    sequence = sequence,
                    cohort = cohort,
                    warmupArrivalCount = warmupArrivalCount,
                    totalArrivalCount = totalPlan.arrivalCount,
                    producerCount = config.producerCount,
                    recorder = recorder,
                )
                continue
            }
            val deadlineNanos = Math.addExact(scheduledAtNanos, config.requestTimeoutNanos)
            if (beforeCreateNanos >= deadlineNanos) {
                recorder.recordGeneratorExpired(cohort)
                sequence = totalPlan.nextSequence(sequence)
                continue
            }

            val command = createCommand(sequence, aggregateIdPlan)
            val admittedAtNanos = System.nanoTime()
            if (admittedAtNanos >= cohortEndNanos) {
                sequence = skipRemainingCohort(
                    sequence = sequence,
                    cohort = cohort,
                    warmupArrivalCount = warmupArrivalCount,
                    totalArrivalCount = totalPlan.arrivalCount,
                    producerCount = config.producerCount,
                    recorder = recorder,
                )
                continue
            }
            if (admittedAtNanos >= deadlineNanos) {
                recorder.recordGeneratorExpired(cohort)
                sequence = totalPlan.nextSequence(sequence)
                continue
            }
            val request = recorder.tryAdmit(
                commandId = command.commandId,
                cohort = cohort,
                scheduledAtNanos = scheduledAtNanos,
                admittedAtNanos = admittedAtNanos,
            )
            if (request == null) {
                recorder.recordShedAtMaxInFlight(cohort)
                sequence = totalPlan.nextSequence(sequence)
                continue
            }
            if (!request.isTerminal()) {
                subscribeProcessed(scenario, command, request)
            }
            sequence = totalPlan.nextSequence(sequence)
        }
    }

    private fun skipRemainingCohort(
        sequence: Long,
        cohort: ArrivalCohort,
        warmupArrivalCount: Long,
        totalArrivalCount: Long,
        producerCount: Int,
        recorder: OpenLoopRunRecorder,
    ): Long {
        val exclusiveEnd =
            if (cohort == ArrivalCohort.WARMUP) {
                warmupArrivalCount
            } else {
                totalArrivalCount
            }
        if (sequence >= exclusiveEnd) {
            return sequence
        }
        val count = Math.addExact(
            Math.floorDiv(
                Math.subtractExact(Math.subtractExact(exclusiveEnd, 1), sequence),
                producerCount.toLong(),
            ),
            1,
        )
        recorder.recordGeneratorMissed(cohort, count)
        return Math.addExact(
            sequence,
            Math.multiplyExact(count, producerCount.toLong()),
        )
    }

    private fun subscribeProcessed(
        scenario: CommandDispatcherScenario,
        command: CommandMessage<*>,
        request: OpenLoopRequestState,
    ) {
        try {
            val disposable = scenario.commandGateway
                .sendAndWait(command, CommandWait.processed(command.commandId))
                .subscribe(
                    { result ->
                        val nowNanos = System.nanoTime()
                        if (result.stage == CommandStage.PROCESSED && result.succeeded) {
                            request.processed(nowNanos)
                        } else {
                            request.failed(CommandResultException(result), nowNanos)
                        }
                    },
                    { failure ->
                        request.failed(failure, System.nanoTime())
                    },
                    {
                        request.completedWithoutResult(System.nanoTime())
                    },
                )
            request.setSubscription(disposable)
        } catch (failure: Throwable) {
            request.failed(failure, System.nanoTime())
        }
    }

    private fun createCommand(
        sequence: Long,
        aggregateIdPlan: BenchmarkAggregateIdPlan,
    ): CommandMessage<*> {
        if (aggregateIdPlan.isHighCardinality) {
            return BenchmarkCommands.commandPathAddCartItem()
        }
        val index = Math.floorMod(sequence, aggregateIdPlan.aggregateIds.size.toLong()).toInt()
        return BenchmarkCommands.commandPathAddCartItem(aggregateIdPlan.aggregateIds[index])
    }

    private fun awaitScheduledTime(scheduledAtNanos: Long) {
        while (true) {
            if (Thread.currentThread().isInterrupted) {
                throw InterruptedException("Open-loop producer was interrupted.")
            }
            val remainingNanos = scheduledAtNanos - System.nanoTime()
            if (remainingNanos <= 0) {
                return
            }
            if (remainingNanos > SPIN_THRESHOLD_NANOS) {
                LockSupport.parkNanos(remainingNanos - SPIN_THRESHOLD_NANOS)
            } else {
                Thread.onSpinWait()
            }
        }
    }

    private fun awaitCommandSubscriber(commandBus: InMemoryCommandBus) {
        val namedAggregate = BenchmarkAggregates.cartMetadata.namedAggregate.materialize()
        val deadlineNanos = System.nanoTime() + READINESS_TIMEOUT_NANOS
        while (commandBus.subscriberCount(namedAggregate) != 1 && System.nanoTime() < deadlineNanos) {
            LockSupport.parkNanos(READINESS_POLL_NANOS)
        }
        check(commandBus.subscriberCount(namedAggregate) == 1) {
            "Expected exactly one command subscriber before starting the open-loop run."
        }
    }

    private fun runSmokeCommand(scenario: CommandDispatcherScenario) {
        val command = BenchmarkCommands.commandPathAddCartItem()
        val result = scenario.commandGateway
            .sendAndWait(
                command,
                CommandWait.processed(command.commandId),
            )
            .block(Duration.ofSeconds(READINESS_TIMEOUT_SECONDS))
        check(result != null && result.stage == CommandStage.PROCESSED && result.succeeded) {
            "Command processed smoke check failed: $result"
        }
    }

    private fun buildReport(
        config: CommandProcessedOpenLoopConfig,
        aggregateIdPlan: BenchmarkAggregateIdPlan,
        startedAt: Instant,
        completedAt: Instant,
        run: OpenLoopExecution,
        invariantViolations: List<String>,
    ): Map<String, Any?> =
        linkedMapOf(
            "schemaVersion" to 1,
            "status" to if (invariantViolations.isEmpty() && run.producerFailures.isEmpty()) {
                "SUCCESS"
            } else {
                "INVALID"
            },
            "engine" to "bounded-open-loop",
            "runId" to config.runId,
            "startedAt" to startedAt.toString(),
            "completedAt" to completedAt.toString(),
            "config" to linkedMapOf(
                "ratePerSecond" to config.ratePerSecond,
                "warmupSeconds" to nanosToSeconds(config.warmupNanos),
                "measurementSeconds" to nanosToSeconds(config.measurementNanos),
                "producerCount" to config.producerCount,
                "maxInFlight" to config.maxInFlight,
                "requestTimeoutMillis" to config.requestTimeoutMillis,
                "watchdogIntervalMillis" to
                    TimeUnit.NANOSECONDS.toMillis(config.watchdogIntervalNanos),
                "startLeadMillis" to TimeUnit.NANOSECONDS.toMillis(config.startLeadNanos),
                "maxGeneratorMissedRatio" to config.maxGeneratorMissedRatio,
                "maxGeneratorLagP99Millis" to config.maxGeneratorLagP99Millis,
                "observationMode" to config.observationMode.name,
                "schedulerPoolSizeToken" to config.schedulerPoolSizeToken,
                "schedulerPoolSize" to config.schedulerPoolSize,
                "stripeCountToken" to config.stripeCountToken,
                "stripeCount" to config.stripeCount,
                "aggregateCardinality" to config.aggregateCardinality,
                "aggregatePlacement" to BenchmarkAggregateIdPlacement.BALANCED.name,
                "configuredStripeCount" to config.stripeCount,
                "theoreticalMaxActiveStripes" to if (aggregateIdPlan.isHighCardinality) {
                    config.stripeCount
                } else {
                    aggregateIdPlan.activeStripes(config.stripeCount).size
                },
            ),
            "protocol" to linkedMapOf(
                "arrivalModel" to "constant-absolute-time",
                "fullObservationCoverage" to
                    config.observationMode.fullObservationCoverage,
                "formalQualification" to if (
                    config.observationMode.fullObservationCoverage
                ) {
                    "requires-finalized-formal-manifest-clean-source-success-and-artifact-hashes"
                } else {
                    "ineligible-non-full-observer-diagnostic"
                },
                "waitApi" to "sendAndWait-last-processed",
                "sentObservation" to "benchmark-command-bus-doOnSuccess",
                "admission" to "immediate-client-shed-at-max-in-flight",
                "timeout" to if (config.observationMode.deadlineWheelEnabled) {
                    "removable-deadline-wheel-from-scheduled-arrival-half-open-deadline"
                } else {
                    "diagnostic-disabled-completion-still-classifies-half-open-deadline"
                },
                "serverDrain" to if (config.observationMode.serverTrackerEnabled) {
                    "command-bus-subscription-through-command-handler-terminal"
                } else {
                    "diagnostic-disabled-client-drain-only"
                },
                "latencyPercentiles" to linkedMapOf(
                    "generatorLag" to if (config.observationMode.generatorLatencyEnabled) {
                        "measurement-admitted-conditional"
                    } else {
                        "diagnostic-recorder-disabled"
                    },
                    "scheduledToSent" to if (config.observationMode.serviceLatencyEnabled) {
                        "measurement-sent-conditional"
                    } else {
                        "diagnostic-recorder-disabled"
                    },
                    "processedLatencies" to if (config.observationMode.serviceLatencyEnabled) {
                        "measurement-processed-by-deadline-conditional"
                    } else {
                        "diagnostic-recorder-disabled"
                    },
                    "sentToProcessed" to if (config.observationMode.serviceLatencyEnabled) {
                        "measurement-causally-ordered-observations-only"
                    } else {
                        "diagnostic-recorder-disabled"
                    },
                ),
                "observerEffect" to linkedMapOf(
                    "sentProbe" to
                        "one deferred bus wrapper, terminal hooks, and one client-state lookup",
                    "serverDrainProbe" to if (config.observationMode.serverTrackerEnabled) {
                        "one ticket state machine per bus subscription and handler terminal hooks"
                    } else {
                        "ticket state disabled; bus/handler wrappers and terminal hooks remain"
                    },
                    "timeoutProbe" to if (config.observationMode.deadlineWheelEnabled) {
                        "O(1) client sampling plus removable deadline buckets; sweep cost is reported"
                    } else {
                        "deadline wheel disabled; timeout release and admission semantics differ"
                    },
                    "latencyRecorder" to
                        "observation modes disable SampleBuffer storage only; " +
                            "surrounding clocks, hooks, and request state remain",
                ),
            ),
            "timing" to linkedMapOf(
                "plannedStartNanos" to run.benchmarkStartNanos,
                "measurementStartNanos" to run.measurementStartNanos,
                "measurementEndNanos" to run.measurementEndNanos,
                "drainCompletedNanos" to run.drainCompletedNanos,
                "drainAfterMeasurementMillis" to
                    nanosToMillis(run.drainCompletedNanos - run.measurementEndNanos),
                "serverOutstandingBeforeScenarioClose" to
                    run.serverOutstandingBeforeClose,
            ),
            "results" to run.recorder.reportMap(config.measurementNanos),
            "validity" to linkedMapOf(
                "valid" to
                    (invariantViolations.isEmpty() && run.producerFailures.isEmpty()),
                "scope" to if (config.observationMode.fullObservationCoverage) {
                    "full-observation-protocol"
                } else {
                    "mode-local-diagnostic"
                },
                "violations" to invariantViolations,
            ),
            "producerFailures" to run.producerFailures.map(Throwable::toString),
            "runtime" to linkedMapOf(
                "javaVersion" to System.getProperty("java.version"),
                "vmName" to System.getProperty("java.vm.name"),
                "vmVersion" to System.getProperty("java.vm.version"),
                "javaHome" to System.getProperty("java.home"),
                "javaExecutable" to
                    ProcessHandle.current().info().command().orElse(null),
                "osName" to System.getProperty("os.name"),
                "osVersion" to System.getProperty("os.version"),
                "osArch" to System.getProperty("os.arch"),
                "availableProcessors" to Runtime.getRuntime().availableProcessors(),
                "maxMemoryBytes" to Runtime.getRuntime().maxMemory(),
                "jvmInputArguments" to
                    ManagementFactory.getRuntimeMXBean().inputArguments,
                "effectiveSystemProperties" to linkedMapOf(
                    "reactor.schedulers.defaultPoolSize" to
                        System.getProperty("reactor.schedulers.defaultPoolSize"),
                    "wow.parallelism" to System.getProperty("wow.parallelism"),
                ),
            ),
        )

    private fun buildHumanReport(
        report: Map<String, Any?>,
        config: CommandProcessedOpenLoopConfig,
        run: OpenLoopExecution,
    ): String {
        val measurement = run.recorder.measurementSnapshot()
        val processedPerSecond =
            run.recorder.reportMap(config.measurementNanos)
                .nestedMap("measurementWindow")
                .getValue("processedByDeadlinePerSecond") as Double
        val processedYield =
            if (measurement.planned == 0L) {
                0.0
            } else {
                measurement.processedByDeadline.toDouble() / measurement.planned
            }
        val status = report.getValue("status")
        return buildString {
            appendLine("# Command PROCESSED Bounded Open-Loop Result")
            appendLine()
            appendLine("- Status: `$status`")
            val validity = report.nestedMap("validity")
            val violations = validity["violations"] as List<*>
            if (violations.isNotEmpty()) {
                appendLine("- Validity violations: `${violations.joinToString()}`")
            }
            appendLine("- Run ID: `${config.runId}`")
            appendLine("- Offered rate: `${config.ratePerSecond} commands/s`")
            appendLine(
                "- Observation mode: `${config.observationMode.name}` " +
                    "(fullObservationCoverage=" +
                    "${config.observationMode.fullObservationCoverage})"
            )
            if (config.observationMode.fullObservationCoverage) {
                appendLine(
                    "- Evidence qualification: runner output alone is not formal; " +
                        "a finalized `formal` manifest with clean source and matching " +
                        "hashes is required"
                )
            } else {
                appendLine(
                    "- Evidence qualification: ineligible for formal evidence because " +
                        "this diagnostic does not have FULL observation coverage"
                )
            }
            appendLine(
                "- Scheduler: `pool=${config.schedulerPoolSizeToken}(${config.schedulerPoolSize}), " +
                    "stripes=${config.stripeCountToken}(${config.stripeCount})`"
            )
            appendLine(
                "- Protocol: `constant absolute arrivals`, `LAST/PROCESSED`, " +
                    "`clientMaxInFlight=${config.maxInFlight}`, `" +
                    if (config.observationMode.serverTrackerEnabled) {
                        "server terminal drain`"
                    } else {
                        "server drain disabled (diagnostic)`"
                    }
            )
            appendLine()
            appendLine("| Metric | Value |")
            appendLine("|---|---:|")
            appendLine("| Measurement planned arrivals | ${measurement.planned} |")
            appendLine("| Admitted | ${measurement.admitted} |")
            appendLine("| Generator missed | ${measurement.generatorMissed} |")
            appendLine("| Generator expired | ${measurement.generatorExpired} |")
            appendLine("| Shed at maxInFlight | ${measurement.shedAtMaxInFlight} |")
            appendLine("| Processed by deadline | ${measurement.processedByDeadline} |")
            appendLine("| Timed out | ${measurement.timedOut} |")
            appendLine(
                "| Server outstanding before scenario close | " +
                    if (config.observationMode.serverTrackerEnabled) {
                        "${run.serverOutstandingBeforeClose} |"
                    } else {
                        "disabled |"
                    }
            )
            appendLine("| Processed yield | ${formatPercent(processedYield)} |")
            appendLine("| Measurement-window processed rate | ${formatRate(processedPerSecond)} commands/s |")
            appendLine()
            appendLine(
                "> Percentiles in JSON are conditional on successful measurement-cohort completions. " +
                    "An all-offered p99 is reported only when yield is above 99%, using rank 99/yield."
            )
        }
    }

    private fun writeAtomically(
        path: Path,
        content: String,
    ) {
        path.parent?.let(Files::createDirectories)
        val temporaryPath = path.resolveSibling("${path.fileName}.tmp")
        Files.writeString(temporaryPath, content)
        runCatching {
            Files.move(
                temporaryPath,
                path,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }.getOrElse {
            Files.move(
                temporaryPath,
                path,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    private fun awaitLatchUntil(
        latch: CountDownLatch,
        deadlineNanos: Long,
    ) {
        val remainingNanos = maxOf(0, deadlineNanos - System.nanoTime())
        latch.await(remainingNanos, TimeUnit.NANOSECONDS)
    }

    private fun formatPercent(value: Double): String =
        "${(value * 10_000).roundToLong() / 100.0}%"

    private fun formatRate(value: Double): String =
        (value * 100).roundToLong().div(100.0).toString()

    private fun nanosToSeconds(nanos: Long): Double =
        nanos.toDouble() / TimeUnit.SECONDS.toNanos(1)

    private fun nanosToMillis(nanos: Long): Double =
        nanos.toDouble() / TimeUnit.MILLISECONDS.toNanos(1)

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.nestedMap(key: String): Map<String, Any?> =
        getValue(key) as Map<String, Any?>

    private const val READINESS_TIMEOUT_SECONDS: Long = 10
    private val READINESS_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(READINESS_TIMEOUT_SECONDS)
    private val READINESS_POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(1)
    private val PRODUCER_COMPLETION_GRACE_NANOS = TimeUnit.SECONDS.toNanos(5)
    private val SERVER_DRAIN_GRACE_NANOS = TimeUnit.SECONDS.toNanos(5)
    private val DRAIN_POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(1)
    private const val WATCHDOG_DRAIN_INTERVALS: Long = 4
    private const val SPIN_THRESHOLD_NANOS: Long = 2_000
}

private object OpenLoopPlans {
    fun createTotal(
        config: CommandProcessedOpenLoopConfig,
    ): me.ahoo.wow.benchmark.workload.OpenLoopArrivalPlan =
        me.ahoo.wow.benchmark.workload.OpenLoopArrivalPlan(
            ratePerSecond = config.ratePerSecond,
            producerCount = config.producerCount,
            durationNanos = config.totalDurationNanos,
        )

    fun warmupArrivalCount(config: CommandProcessedOpenLoopConfig): Long =
        me.ahoo.wow.benchmark.workload.OpenLoopArrivalPlan(
            ratePerSecond = config.ratePerSecond,
            producerCount = config.producerCount,
            durationNanos = config.warmupNanos,
        ).arrivalCount
}

private data class OpenLoopExecution(
    val recorder: OpenLoopRunRecorder,
    val producerFailures: List<Throwable>,
    val benchmarkStartNanos: Long,
    val measurementStartNanos: Long,
    val measurementEndNanos: Long,
    val drainCompletedNanos: Long,
    val serverOutstandingBeforeClose: Int,
)

private class SentProbingCommandBus(
    private val delegate: CommandBus,
    private val recorder: () -> OpenLoopRunRecorder?,
) : CommandBus by delegate {
    override fun send(message: CommandMessage<*>): Mono<Void> {
        val activeRecorder = recorder() ?: return delegate.send(message)
        val request = requireNotNull(
            activeRecorder.captureForSentProbe(message.commandId)
        ) {
            "Missing active client request for command ${message.commandId}."
        }
        return Mono.defer {
            val serverTicket = activeRecorder.serverSendStarted(message.commandId)
            Mono.defer {
                delegate.send(message)
            }.doOnSuccess {
                serverTicket?.sendSucceeded()
                request.recordSent(System.nanoTime())
            }.doOnError {
                serverTicket?.sendFailed()
            }.doOnCancel {
                serverTicket?.sendCancelled()
            }
        }
    }
}

private class ServerCompletionTrackingCommandHandler(
    private val delegate: CommandHandler,
    private val recorder: () -> OpenLoopRunRecorder?,
) : CommandHandler {
    override fun handle(context: ServerCommandExchange<*>): Mono<Void> =
        Mono.defer {
            val serverTicket = recorder()?.serverHandlerStarted(context.message.commandId)
            Mono.defer {
                delegate.handle(context)
            }.doFinally { signalType ->
                serverTicket?.handlerTerminated(
                    signalType = signalType.name,
                    failed = context.getError() != null,
                )
            }
        }
}

private class NamedThreadFactory(
    private val prefix: String,
    private val daemon: Boolean = false,
) : ThreadFactory {
    private val sequence = AtomicInteger()

    override fun newThread(task: Runnable): Thread =
        Thread(task, "$prefix-${sequence.incrementAndGet()}").apply {
            isDaemon = daemon
        }
}
