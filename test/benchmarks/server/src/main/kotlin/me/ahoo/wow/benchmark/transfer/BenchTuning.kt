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

package me.ahoo.wow.benchmark.transfer

import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.runtime.log.AppendQueueOptions
import me.ahoo.wow.runtime.node.ServiceNodeOptions
import me.ahoo.wow.runtime.snapshot.SnapshotOptions
import me.ahoo.wow.spring.boot.node.NodeOptionsCustomizer
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Condition
import org.springframework.context.annotation.ConditionContext
import org.springframework.context.annotation.Conditional
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.core.type.AnnotatedTypeMetadata

/**
 * A run's tuning of the node's counts no option gives, under `bench.tuning.*`, so one build measures many: the append
 * queue's committers (`committers`), its merged call's cap (`call-cap`) and a lane's batch (`lane-batch`), and the
 * snapshot writes in flight (`snapshot-writes`, over the store's own). Each left out keeps the node's own; the node is
 * built with them through the host's [NodeOptionsCustomizer], a seam for measurements, and the values it ran with are
 * logged once as `bench-tuning {json}` at WARN, beside the run's other lines.
 */
@OptIn(InternalWowApi::class)
class BenchTuning(
    val committers: Int? = null,
    val callCap: Int? = null,
    val laneBatch: Int? = null,
    val snapshotWrites: Int? = null,
) : NodeOptionsCustomizer {
    override fun customize(options: ServiceNodeOptions): ServiceNodeOptions {
        val queue = options.appendQueue
        val snapshots = options.snapshots
        val tuned = options.copyWith(
            appendQueue = AppendQueueOptions(
                maxBatch = laneBatch ?: queue.maxBatch,
                mergers = committers ?: queue.mergers,
                mergeMax = callCap ?: queue.mergeMax,
            ),
            snapshots = SnapshotOptions(
                interval = snapshots.interval,
                batch = snapshots.batch,
                pages = snapshots.pages,
                leaseTtl = snapshots.leaseTtl,
                memberTtl = snapshots.memberTtl,
                writeBatch = snapshots.writeBatch,
                loadTimeout = snapshots.loadTimeout,
                writesInFlight = snapshotWrites ?: snapshots.writesInFlight,
            ),
        )
        log.warn("bench-tuning {}", lineOf(tuned))
        return tuned
    }

    companion object {
        private val log = LoggerFactory.getLogger(BenchTuning::class.java)

        /** The properties a run tunes the node with, under `bench.tuning.`. */
        val NAMES = listOf("committers", "call-cap", "lane-batch", "snapshot-writes")

        /** Whether [environment] gives any of the tuning's values: the customizer is declared only then. */
        fun given(environment: Environment): Boolean = NAMES.any { environment.containsProperty("bench.tuning.$it") }

        /** The tuning [environment] gives, each value left out as null. */
        fun of(environment: Environment): BenchTuning {
            fun int(name: String) = environment.getProperty("bench.tuning.$name", Int::class.java)
            return BenchTuning(int("committers"), int("call-cap"), int("lane-batch"), int("snapshot-writes"))
        }

        /** The counts a node runs with, as one JSON object: the run's record of them. */
        fun lineOf(options: ServiceNodeOptions): String = with(options) {
            "{\"committers\":${appendQueue.mergers},\"callCap\":${appendQueue.mergeMax}," +
                "\"laneBatch\":${appendQueue.maxBatch},\"snapshotWrites\":${snapshots.writesInFlight ?: "null"}}"
        }
    }
}

/** Whether a run gives any `bench.tuning.*` value: the customizer is declared only then. */
class BenchTuningGiven : Condition {
    override fun matches(context: ConditionContext, metadata: AnnotatedTypeMetadata): Boolean =
        BenchTuning.given(context.environment)
}

/** [BenchTuning] on the node, when a run gives one of its values. */
@Configuration(proxyBeanMethods = false)
@Conditional(BenchTuningGiven::class)
class BenchTuningConfiguration {
    @Bean
    fun benchTuning(environment: Environment): BenchTuning = BenchTuning.of(environment)
}
