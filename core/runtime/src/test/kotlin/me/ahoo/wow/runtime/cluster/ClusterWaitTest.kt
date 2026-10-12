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

package me.ahoo.wow.runtime.cluster

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.runtime.wait.ResultCodes
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Waits across two nodes over the loopback cluster channel (design 04 §6; decisions D62, D69): the command arrives at
 * one node and its partition's owner is the other.
 */
class ClusterWaitTest {
    /** As in [ClusterSnapshotTest]: the forward must not degrade to running here on a cold JVM. */
    private val cluster = TestCluster(options = fastOptions(forwardTimeout = 10.seconds), saga = true)

    @AfterEach
    fun close(): Unit = runBlocking {
        cluster.nodes.values.forEach { it.kill() }
        cluster.close()
    }

    /** Two running nodes, and an account the second one owns. */
    private suspend fun forwarded(): Triple<TestNode, TestNode, String> {
        val (one, two) = cluster.start("node-1", "node-2")
        cluster.awaitStable()
        val names = cluster.running.map { it.name }
        val id = (0 until 64).map { "acc-$it" }.first { Hrw.owner(cluster.partitionOf(it), names) == two.name }
        return Triple(one, two, id)
    }

    @Test
    fun `an explicit snapshot wait without a snapshot subscription is rejected before it is forwarded`(): Unit =
        runBlocking {
            val (one, two, id) = forwarded()
            val result = one.deposit(id, wait = WaitPlan.SNAPSHOT)
            result.status.assert().isEqualTo(CommandResult.Status.REJECTED)
            result.error!!.code.assert().isEqualTo(ResultCodes.WAIT_INVALID)
            result.hop.assert().isEqualTo(Hop.accepted(CONTEXT))
            one.decided.assert().doesNotContain(result.commandId)
            two.decided.assert().doesNotContain(result.commandId)
            cluster.events.assert().doesNotContain("append $id")
            one.registry.size.assert().isEqualTo(0)
        }

    @Test
    fun `the default wait without a snapshot subscription is COMMITTED on the owner`(): Unit = runBlocking {
        val (one, two, id) = forwarded()
        val result = one.deposit(id, wait = WaitPlan.DEFAULT)
        result.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        result.hop.assert().isEqualTo(Hop.committed(CONTEXT))
        two.decided.assert().contains(result.commandId)
        one.decided.assert().doesNotContain(result.commandId)
    }

    @Test
    fun `the stream emits one result per derived command, signalled from the owner`(): Unit = runBlocking {
        val (one, two, id) = forwarded()
        cluster.derives[id] = 3
        val command = one.command(id, wait = WaitPlan.of(Hop.handled(SAGA), Hop.committed()))
        val emitted = withTimeout(10.seconds) { one.coordinator.stream(one.model, command).toList() }

        emitted.size.assert().isEqualTo(4)
        val sagaHop = emitted.first()
        sagaHop.hop.assert().isEqualTo(Hop.handled(SAGA, CONTEXT))
        sagaHop.function.kind.assert().isEqualTo(FunctionKind.SAGA)
        val derived = (1..3).map { "${command.id}-d-$it" }
        sagaHop.derived.assert().containsExactly(*derived.toTypedArray())
        val settled = emitted.drop(1)
        settled.forEach {
            it.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
            it.hop.assert().isEqualTo(Hop.committed(CONTEXT))
            it.correlationId.assert().isEqualTo(command.id)
        }
        settled.map { it.commandId }.assert().containsExactlyInAnyOrder(*derived.toTypedArray())
        two.decided.assert().contains(command.id)
        two.decided.assert().contains(*derived.toTypedArray())
        one.registry.size.assert().isEqualTo(0)
    }
}
