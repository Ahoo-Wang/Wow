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

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.kernel.HandlerRetry
import me.ahoo.wow.runtime.admission.ModelRegistry
import me.ahoo.wow.runtime.ledger.LedgerPublishing
import me.ahoo.wow.runtime.subscription.ParkedRetryOptions
import me.ahoo.wow.runtime.subscription.SubscriptionId
import me.ahoo.wow.runtime.subscription.SubscriptionOptions
import me.ahoo.wow.runtime.subscription.SubscriptionStart
import me.ahoo.wow.spi.config.WowConfigurationException
import me.ahoo.wow.spi.ledger.ParkedRetrySpec
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow

/** The refusals of a misconfigured node, each naming the node and what to set. */
class NodeChecksTest {
    private companion object {
        val SAGE = SubscriptionId("shop", "sage")
    }

    private val names = setOf(SubscriptionId("shop", "saga"), SubscriptionId("shop", "projection"))

    @Test
    fun `a setting of a subscription the node does not have should be refused, naming the ones it has`() {
        assertThrownBy<IllegalArgumentException> {
            NodeChecks.subscriptionsNamed(
                "n-1",
                names,
                SubscriptionOptions(starts = mapOf(SAGE to SubscriptionStart.Beginning))
            )
        }.hasMessage(
            "A start is configured for the subscription shop.sage, which node n-1 does not have: [shop.projection, shop.saga]."
        )
        assertThrownBy<IllegalArgumentException> {
            NodeChecks.subscriptionsNamed(
                "n-1",
                names,
                SubscriptionOptions(parked = ParkedRetryOptions(specs = mapOf(SAGE to ParkedRetrySpec()))),
            )
        }.hasMessage(
            "A parked retry spec is configured for the subscription shop.sage, which node n-1 does not have: " +
                "[shop.projection, shop.saga].",
        )
        assertThrownBy<IllegalArgumentException> {
            NodeChecks.subscriptionsNamed("n-1", names, SubscriptionOptions(retries = mapOf(SAGE to HandlerRetry())))
        }.hasMessage(
            "A retry budget is configured for the subscription shop.sage, which node n-1 does not have: [shop.projection, shop.saga]."
        )
        assertDoesNotThrow {
            NodeChecks.subscriptionsNamed(
                "n-1",
                names,
                SubscriptionOptions(starts = mapOf(SubscriptionId("shop", "saga") to SubscriptionStart.Beginning))
            )
        }
    }

    @Test
    fun `a node that publishes its ledger should need a command transport`() {
        assertThrownBy<WowConfigurationException> {
            NodeChecks.ledgerTransport("n-1", LedgerPublishing(), null)
        }.hasMessageContaining(
            "Node n-1 publishes its failure ledger to ops"
        ).isInstanceOfSatisfying(WowConfigurationException::class.java) {
            it.key.assert().isEqualTo(NodeChecks.TRANSPORT)
        }
        assertDoesNotThrow { NodeChecks.ledgerTransport("n-1", null, null) }
    }

    @Test
    fun `the topic router should take served types and need a producer`() {
        val registry = ModelRegistry(listOf(accountModel("bank")))
        val served = registry.models.single().type
        assertThrownBy<WowConfigurationException> {
            NodeChecks.topicRouted("n-1", setOf(AggregateType("bank", "loan")), registry, hasProducer = true)
        }.hasMessageStartingWith(
            "The topic router is configured for [bank.loan], which node n-1 does not serve; it serves [$served]."
        ).isInstanceOfSatisfying(WowConfigurationException::class.java) {
            it.key.assert().isEqualTo("wow.aggregates.[bank.loan].routing")
        }
        assertThrownBy<WowConfigurationException> {
            NodeChecks.topicRouted("n-1", setOf(served), registry, hasProducer = false)
        }.hasMessageContaining("node n-1 has no command transport")
        assertDoesNotThrow { NodeChecks.topicRouted("n-1", emptySet(), registry, hasProducer = false) }
    }
}
