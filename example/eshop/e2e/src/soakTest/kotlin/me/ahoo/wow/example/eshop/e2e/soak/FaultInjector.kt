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

package me.ahoo.wow.example.eshop.e2e.soak

import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * One fault on the timeline: its [kind], what it hit ([target]), when it began, when the injection ended (the node
 * killed and its replacement started, the container back) and when everything served again ([recovered], null when
 * it did not within the health deadline). Times are epoch milliseconds.
 */
data class FaultEvent(
    val kind: FaultKind,
    val target: String,
    val began: Long,
    var injected: Long? = null,
    var recovered: Long? = null,
    var detail: String? = null,
)

/**
 * Injects the configured faults one after another, a fault every [SoakConfig.faultInterval], cycling through
 * [SoakConfig.faults] in their order. Each one is injected, then the stack is awaited healthy before the next slot,
 * so two faults never overlap. The first comes one interval after the start.
 */
class FaultInjector(private val stack: SoakStack, private val config: SoakConfig, private val log: (String) -> Unit) {
    private val running = AtomicBoolean(true)
    private val random = Random(config.seed)
    private var worker: Thread? = null

    /** The faults so far, in order. */
    val timeline = ConcurrentLinkedQueue<FaultEvent>()

    fun start() {
        if (config.faults.isEmpty()) return
        worker = thread(name = "soak-faults", isDaemon = true) {
            var index = 0
            var next = System.nanoTime() + config.faultInterval.toNanos()
            while (running.get()) {
                val wait = (next - System.nanoTime()) / NANOS_PER_MILLI
                if (wait > 0) {
                    Thread.sleep(minOf(wait, TICK.toMillis()))
                    continue
                }
                val kind = config.faults[index++ % config.faults.size]
                runCatching { inject(kind) }.onFailure { log("fault $kind failed: $it") }
                next += config.faultInterval.toNanos()
            }
        }
    }

    /** Stops after the fault in progress, if any, and its recovery. */
    fun stop() {
        running.set(false)
        worker?.join()
    }

    private fun inject(kind: FaultKind) {
        val event = FaultEvent(kind, target(kind), System.currentTimeMillis())
        timeline += event
        log("fault ${kind.name} on ${event.target}")
        try {
            when (kind) {
                FaultKind.KILL_ORDERING -> replace("ordering", event)
                FaultKind.KILL_INVENTORY -> replace("inventory", event)
                FaultKind.KILL_PAYMENT -> replace("payment", event)
                FaultKind.KILL_BASKET -> replace("basket", event)
                FaultKind.PAUSE_POSTGRES -> {
                    stack.pausePostgres()
                    try {
                        Thread.sleep(PAUSE.toMillis())
                    } finally {
                        stack.unpausePostgres()
                    }
                }

                FaultKind.RESTART_POSTGRES -> stack.restartPostgres()
                FaultKind.RESTART_KAFKA -> stack.restartKafka()
            }
        } finally {
            event.injected = System.currentTimeMillis()
        }
        if (stack.awaitHealthy()) {
            event.recovered = System.currentTimeMillis()
            log("fault ${kind.name} recovered in ${event.recovered!! - event.began} ms")
        } else {
            log("fault ${kind.name}: the stack is not healthy after it")
        }
    }

    private fun target(kind: FaultKind): String = when (kind) {
        FaultKind.PAUSE_POSTGRES, FaultKind.RESTART_POSTGRES -> "postgres"
        FaultKind.RESTART_KAFKA -> "kafka"
        else -> context(kind)
    }

    private fun context(kind: FaultKind): String = kind.name.removePrefix("KILL_").lowercase()

    /** Kills a node of [context], chosen at random, then starts one of a new id in its place. */
    private fun replace(context: String, event: FaultEvent) {
        val nodes = stack.nodes(context)
        check(nodes.isNotEmpty()) { "No $context node to kill." }
        val victim = nodes[random.nextInt(nodes.size)]
        stack.kill(victim)
        // A replacement that fails to start (a backend still recovering, say) is tried again once.
        val replacement = runCatching { stack.start(context) }.getOrElse { failure ->
            log("starting a $context node failed, trying again: $failure")
            Thread.sleep(RETRY_START.toMillis())
            stack.start(context)
        }
        event.detail = "killed ${victim.id}, started ${replacement.id}"
    }

    private companion object {
        val PAUSE: Duration = Duration.ofSeconds(15)
        val TICK: Duration = Duration.ofMillis(500)
        val RETRY_START: Duration = Duration.ofSeconds(10)
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
