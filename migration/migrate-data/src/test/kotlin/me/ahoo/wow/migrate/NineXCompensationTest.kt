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

package me.ahoo.wow.migrate

import me.ahoo.test.asserts.assert
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.FunctionKind
import me.ahoo.wow.manifest.FunctionManifest
import me.ahoo.wow.manifest.JobManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.SubscriptionKind
import me.ahoo.wow.manifest.SubscriptionManifest
import me.ahoo.wow.manifest.SubscriptionSource
import me.ahoo.wow.migrate.example.NineXCompensationExample
import me.ahoo.wow.migrate.example.NineXExample
import me.ahoo.wow.spi.json.WowJson
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.databind.node.ObjectNode

class NineXCompensationTest {
    @Test
    fun `a 9x execution reads from the snapshot the 9x compensation service stored`() {
        val execution = NineXCompensationExample.execution(2)
        execution.assert().isEqualTo(
            NineXExecutionFailed(
                id = "0VYC0mp1nA0002",
                function = NineXFunction("example-service", "OrderSaga", "onEvent"),
                functionKind = "EVENT",
                aggregate = NineXAggregate("example-service", "order"),
                aggregateId = NineXAggregateId(NineXCompensationExample.ORDER_ID, NineXCompensationExample.TENANT),
                version = 1,
                errorCode = "Conflict",
                errorMessage = "stock reserved twice",
                executeAt = 1_792_004_371_000,
                retries = 1,
                retryAt = 1_792_004_370_000,
                maxRetries = 5,
                minBackoff = 60,
                executionTimeout = 30,
                recoverable = "RECOVERABLE",
                status = "FAILED",
            ),
        )
        execution.unresolved.assert().isTrue()
        execution.function.owner.toString().assert().isEqualTo("example-service.OrderSaga")
        execution.toString().assert()
            .isEqualTo(
                "0VYC0mp1nA0002 (example-service.OrderSaga.onEvent on example-service.order/(0)/0VY3GwaK4OmV00E v1)"
            )
    }

    @Test
    fun `succeeded and deleted executions are resolved, failed and prepared ones are not`() {
        NineXCompensationExample.executions().associate { it.id.takeLast(1) to it.unresolved }.assert().isEqualTo(
            mapOf("1" to true, "2" to true, "3" to false, "4" to true, "5" to false, "6" to true, "7" to true),
        )
        NineXCompensationExample.execution(3).status.assert().isEqualTo(NineXExecutionFailed.SUCCEEDED)
        NineXCompensationExample.execution(4).status.assert().isEqualTo("PREPARED")
        NineXCompensationExample.execution(5).deleted.assert().isTrue()
    }

    @Test
    fun `a missing field is named, a missing message or mark has a default`() {
        val node = WowJson.mapper.readTree(NineXCompensationExample.resource(1)) as ObjectNode
        val state = node.get("state") as ObjectNode
        (state.get("error") as ObjectNode).remove("errorMsg")
        state.remove("recoverable")
        NineXExecutionFailed.parse(node).also {
            it.errorMessage.assert().isEmpty()
            it.recoverable.assert().isEqualTo("UNKNOWN")
        }
        (state.get("retryState") as ObjectNode).put("retries", "one")
        assertThrows<MigrationException> { NineXExecutionFailed.parse(node) }.message!!.assert()
            .isEqualTo("9.x execution 0VYC0mp1nA0001 has no number at retryState.retries.")
        state.remove("function")
        assertThrows<MigrationException> { NineXExecutionFailed.parse(node) }.message!!.assert()
            .isEqualTo("9.x execution 0VYC0mp1nA0001 has no function.contextName.")
        node.remove("state")
        assertThrows<MigrationException> { NineXExecutionFailed.parse(node) }.message!!.assert()
            .isEqualTo("9.x execution 0VYC0mp1nA0001 has no state.")
        node.remove("aggregateId")
        assertThrows<MigrationException> { NineXExecutionFailed.parse(node) }.message!!.assert()
            .isEqualTo("A 9.x execution has no _id.")
    }

    @Test
    fun `a text field of another type is named`() {
        val node = WowJson.mapper.readTree(NineXCompensationExample.resource(1)) as ObjectNode
        (node.get("state") as ObjectNode).put("status", 1)
        assertThrows<MigrationException> { NineXExecutionFailed.parse(node) }.message!!.assert()
            .isEqualTo("9.x execution 0VYC0mp1nA0001 has no text at status.")
    }

    @Test
    fun `a subscription parks only the aggregates it reads from its own context's log`() {
        fun subscription(name: String, source: SubscriptionSource, rejected: Boolean = false, job: Boolean = false) =
            SubscriptionManifest(
                name = name,
                kind = SubscriptionKind.SAGA,
                source = source,
                function = FunctionManifest(FunctionKind.SAGA, "example", "S", name),
                rejected = if (rejected) listOf("example.order.CreateOrder") else null,
                job = if (job) JobManifest("nightly", "0 0 * * * *") else null,
            )
        val manifest = Manifest(
            contexts = NineXExample.manifest.contexts.map {
                ContextManifest(
                    name = it.name,
                    aggregates = it.aggregates,
                    subscriptions = listOf(
                        subscription("S.cart", SubscriptionSource(aggregates = listOf("cart"))),
                        subscription("S.any", SubscriptionSource()),
                        subscription("S.rejected", SubscriptionSource(), rejected = true),
                        subscription("S.job", SubscriptionSource(), job = true),
                    ),
                )
            } + ContextManifest("billing", subscriptions = listOf(subscription("S.billing", SubscriptionSource()))),
        )
        val plan = MigrationPlan(NineXExample.map, manifest).compensation
        val order = NineXAggregate("example-service", "order")
        plan.problemOf("S.any", order).assert().isNull()
        plan.problemOf("S.cart", order).assert().isEqualTo("the V10 subscription S.cart does not read example.order")
        plan.problemOf("S.rejected", order).assert().isEqualTo("the V10 subscription S.rejected does not read events")
        plan.problemOf("S.job", order).assert().isEqualTo("the V10 subscription S.job does not read events")
        plan.problemOf("S.billing", order).assert()
            .isEqualTo("the V10 subscription S.billing of billing does not read the log of example")
        plan.problemOf("S.none", order).assert().isEqualTo("the V10 subscription S.none is not in the Manifest")
        plan.contexts.assert().containsExactly("example-service")
    }
}
