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

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.kernel.Dependencies
import me.ahoo.wow.kernel.ModelProvider
import me.ahoo.wow.kernel.memory.InMemoryCheckpointStore
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.kernel.memory.InMemoryFailureLedger
import me.ahoo.wow.kernel.memory.InMemoryLeaseStore
import me.ahoo.wow.kernel.memory.InMemorySnapshotStore
import me.ahoo.wow.manifest.ServiceInfo
import me.ahoo.wow.runtime.admission.CommandBody
import me.ahoo.wow.runtime.admission.ServerCommandRequest
import me.ahoo.wow.runtime.manifest.ManifestLoader
import me.ahoo.wow.runtime.node.ContextBackends
import me.ahoo.wow.runtime.node.ServiceNode
import me.ahoo.wow.runtime.snapshot.SnapshotTargets
import me.ahoo.wow.spi.json.WowJson
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.support.StaticListableBeanFactory
import tools.jackson.databind.JsonNode
import kotlin.time.Duration.Companion.seconds

/**
 * [BenchPipeline] over a started node: the benchmark's own application, on the kernel's in-memory stores, one node.
 * Where the instrument reads a node it can go quiet (a log group's log found as its hand-off log, a section left
 * out while empty), so a change in how a node is assembled that drops a section fails here.
 */
@OptIn(InternalWowApi::class)
class BenchPipelineNodeTest {
    private fun node(): ServiceNode {
        val loader = Thread.currentThread().contextClassLoader
        val providers = ModelProvider.load(loader)
        return ServiceNode(
            service = SERVICE,
            node = "node-1",
            address = "node-1.internal:9090",
            models = ServiceNode.models(Dependencies.NONE, providers),
            manifest = ManifestLoader(loader, WowJson.mapper).load(ServiceInfo(SERVICE), providers),
            backends = { _ ->
                ContextBackends(
                    log = InMemoryEventLog(PARTITIONS),
                    checkpoints = InMemoryCheckpointStore(),
                    leases = InMemoryLeaseStore(),
                    ledger = InMemoryFailureLedger(),
                    snapshots = SnapshotTargets.single(InMemorySnapshotStore()),
                )
            },
            channel = { _, _ -> error("A single node forwards nothing.") },
        )
    }

    private fun open(owner: Int) = ServerCommandRequest(
        context = TransferContext.NAME,
        aggregate = TransferContext.ACCOUNT,
        command = "OpenAccount",
        body = CommandBody.of("""{"owner":"owner-$owner","balance":100}"""),
        caller = Caller(TENANT),
        wait = WaitPlan.COMMITTED,
    )

    private fun sections(pipeline: BenchPipeline): JsonNode? = pipeline.snapshot()?.let { WowJson.mapper.readTree(it) }

    @Test
    fun `a started node's line should have every section, its calls and the hand-off's deliveries counted`(): Unit =
        runBlocking {
            val node = node()
            node.start()
            try {
                val pipeline = BenchPipeline.of(StaticListableBeanFactory(mapOf("node" to node)))
                // The first read starts the append queue's counts: the commands below are counted.
                sections(pipeline)
                repeat(COMMANDS) { node.gateway.execute(open(it)) }

                // The tails deliver after the commits return, and a call is clocked once its committer is free, after
                // its callers resumed: both waited for, then whatever the line says is held.
                withTimeoutOrNull(10.seconds) {
                    while (sections(pipeline).let { it?.path("handoff")?.path("delivered")?.asLong() ?: 0 } == 0L ||
                        sections(pipeline).let { it?.path("appendClock")?.path("calls")?.asLong() ?: 0 } == 0L
                    ) {
                        delay(10)
                    }
                }
                val line = checkNotNull(sections(pipeline))
                listOf("appendDepth", "appendCalls", "appendClock", "handoff", "window", "windowMax").forEach { section ->
                    line.has(section).assert().describedAs(section).isTrue()
                }
                line.path("appendCalls").path("calls").asLong().assert().isGreaterThan(0L)
                line.path("appendCalls").path("streams").asLong().assert().isGreaterThanOrEqualTo(COMMANDS.toLong())
                line.path("handoff").path("delivered").asLong().assert().isGreaterThan(0L)
                // The kernel's in-memory log marks none of the steps only a store's log sees.
                line.path("appendClock").path("calls").asLong().assert().isGreaterThan(0L)
                line.path("appendClock").path("marked").asLong().assert().isZero()
            } finally {
                node.stop()
            }
        }

    private companion object {
        const val SERVICE = "benchmark"
        const val TENANT = "tenant"
        const val PARTITIONS = 4
        const val COMMANDS = 8
    }
}
