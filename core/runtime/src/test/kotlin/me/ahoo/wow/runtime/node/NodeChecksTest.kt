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

package me.ahoo.wow.runtime.node

import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.kernel.HandlerRetry
import me.ahoo.wow.runtime.admission.ModelRegistry
import me.ahoo.wow.runtime.ledger.LedgerPublishing
import me.ahoo.wow.runtime.subscription.ParkedRetryOptions
import me.ahoo.wow.runtime.subscription.SubscriptionOptions
import me.ahoo.wow.runtime.subscription.SubscriptionStart
import me.ahoo.wow.spi.ledger.ParkedRetrySpec
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow

/** The refusals of a misconfigured node, each naming the node and what to set. */
class NodeChecksTest {
    private val names = setOf("saga", "projection")

    @Test
    fun `a setting of a subscription the node does not have should be refused, naming the ones it has`() {
        assertThrownBy<IllegalArgumentException> {
            NodeChecks.subscriptionsNamed(
                "n-1",
                names,
                SubscriptionOptions(starts = mapOf("sage" to SubscriptionStart.Beginning))
            )
        }.hasMessage(
            "A start is configured for the subscription sage, which node n-1 does not have: [projection, saga]."
        )
        assertThrownBy<IllegalArgumentException> {
            NodeChecks.subscriptionsNamed(
                "n-1",
                names,
                SubscriptionOptions(parked = ParkedRetryOptions(specs = mapOf("sage" to ParkedRetrySpec()))),
            )
        }.hasMessage(
            "A parked retry spec is configured for the subscription sage, which node n-1 does not have: " +
                "[projection, saga].",
        )
        assertThrownBy<IllegalArgumentException> {
            NodeChecks.subscriptionsNamed("n-1", names, SubscriptionOptions(retries = mapOf("sage" to HandlerRetry())))
        }.hasMessage(
            "A retry budget is configured for the subscription sage, which node n-1 does not have: [projection, saga]."
        )
        assertDoesNotThrow {
            NodeChecks.subscriptionsNamed(
                "n-1",
                names,
                SubscriptionOptions(starts = mapOf("saga" to SubscriptionStart.Beginning))
            )
        }
    }

    @Test
    fun `a node that publishes its ledger should need a command transport`() {
        assertThrownBy<IllegalArgumentException> {
            NodeChecks.ledgerTransport("n-1", LedgerPublishing(), null)
        }.hasMessageContaining(
            "Node n-1 publishes its failure ledger to ops"
        ).hasMessageContaining("wow.kafka.bootstrap-servers")
        assertDoesNotThrow { NodeChecks.ledgerTransport("n-1", null, null) }
    }

    @Test
    fun `the Kafka router should take served types and need a producer`() {
        val registry = ModelRegistry(listOf(accountModel("bank")))
        val served = registry.models.single().type
        assertThrownBy<IllegalArgumentException> {
            NodeChecks.kafkaRouted("n-1", setOf(AggregateType("bank", "loan")), registry, hasProducer = true)
        }.hasMessage(
            "The Kafka router is configured for [bank.loan], which node n-1 does not serve; it serves [$served]."
        )
        assertThrownBy<IllegalArgumentException> {
            NodeChecks.kafkaRouted("n-1", setOf(served), registry, hasProducer = false)
        }.hasMessageContaining("node n-1 has no command transport")
        assertDoesNotThrow { NodeChecks.kafkaRouted("n-1", emptySet(), registry, hasProducer = false) }
    }
}
