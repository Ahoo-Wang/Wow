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

package me.ahoo.wow.runtime.admission

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.runtime.FrameworkCodes
import me.ahoo.wow.runtime.cluster.CONTEXT
import me.ahoo.wow.runtime.cluster.Hrw
import me.ahoo.wow.runtime.cluster.TestCluster
import me.ahoo.wow.runtime.cluster.TestNode
import me.ahoo.wow.runtime.cluster.fastOptions
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds

/**
 * Admission runs on the node a request arrives at; the admitted command is forwarded over the cluster channel to its
 * partition's owner, which executes it without admitting it again (decisions D71, D92).
 */
class ClusterAdmissionTest {
    /** A forward that is slow under load must not degrade to running locally: the test asserts where it ran. */
    private val cluster = TestCluster(options = fastOptions(forwardTimeout = 10.seconds))

    @AfterEach
    fun close() {
        cluster.close()
    }

    /** The actions each node's authorizer saw. */
    private val authorized = CopyOnWriteArrayList<Pair<String, CommandAction>>()

    private fun gateway(node: TestNode) = CommandGateway(
        ModelRegistry(listOf(node.model)),
        mapOf(CONTEXT to node.coordinator),
        authorizer = { _, action ->
            authorized += node.name to action
            Authorization.Allowed
        },
    )

    private fun request(id: String, caller: Caller) = CommandRequest(
        context = CONTEXT,
        aggregate = "account",
        command = "Deposit",
        body = CommandBody.of("""{"amount":5}"""),
        caller = caller,
        aggregateId = id,
        wait = WaitPlan.COMMITTED,
    )

    @Test
    fun `a command admitted on one node is forwarded to its owner and keeps its admitted identity`(): Unit =
        runBlocking {
            val (one, two) = cluster.start("node-1", "node-2")
            cluster.awaitStable()
            val id = (0 until 64).map { "acc-$it" }
                .first { Hrw.owner(cluster.partitionOf(it), listOf("node-1", "node-2")) == "node-2" }
            val alice = Caller("t", Scope("alice"), subject = "alice")

            val result = gateway(one).execute(request(id, alice))
            result.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
            result.hop.assert().isEqualTo(Hop.committed(CONTEXT))
            two.decided.assert().containsExactly(result.commandId)
            one.decided.assert().isEmpty()
            authorized.map { it.first }.assert().containsExactly("node-1")

            // The owner's kernel guards the scope the arriving node admitted: another owner is refused there.
            val bob = gateway(one).execute(request(id, Caller("t", Scope("bob"), subject = "bob")))
            bob.status.assert().isEqualTo(CommandResult.Status.REJECTED)
            bob.error!!.code.assert().isEqualTo(FrameworkCodes.AGGREGATE_FORBIDDEN)
            two.decided.size.assert().isEqualTo(1)
            one.decided.assert().isEmpty()
        }
}
