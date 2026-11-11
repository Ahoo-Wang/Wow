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

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.kernel.HandlerRetry
import me.ahoo.wow.runtime.contract.ContractDirectory
import me.ahoo.wow.runtime.subscription.ParkedRetryOptions
import me.ahoo.wow.runtime.subscription.Subscription
import me.ahoo.wow.runtime.subscription.SubscriptionFilter
import me.ahoo.wow.runtime.subscription.SubscriptionStart
import me.ahoo.wow.runtime.wait.WaitOptions
import me.ahoo.wow.spi.config.WowConfigurationException
import me.ahoo.wow.spi.ledger.ParkedRetrySpec
import me.ahoo.wow.spi.subscription.SubscriptionId
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * What a node refuses to be built with: starts, parked retry specs and retry budgets of subscriptions it does not have,
 * a Manifest that disagrees, contracts of another Manifest or service; its default wait, and the creation id.
 */
class ServiceNodeConfigurationTest : ServiceNodeFixtures() {
    @Test
    fun `a start configured for a subscription the node does not have is refused`() {
        val plain = SubscriptionFactory { listOf(Subscription("plain", SubscriptionFilter(BANK)) {}) }
        fun starts(vararg names: String) = fastNodeOptions().let {
            it.copyWith(
                subscription = it.subscription.copyWith(
                    starts = names.map { SubscriptionId(BANK, it) }.associateWith { SubscriptionStart.Beginning }
                )
            )
        }
        // The application's subscriptions and the built-in ones may be configured (decision D154), the access
        // stamping's that every context runs included.
        node("node-1", options = starts("plain", "wow.snapshot", "wow.access"), subscriptions = plain)
        assertThrows<WowConfigurationException> {
            node("node-2", options = starts("Plain.typo"), subscriptions = plain)
        }.message.assert().contains("subscription $BANK.Plain.typo", "plain")
    }

    @Test
    fun `a parked retry spec configured for a subscription the node does not have is refused`() {
        val plain = SubscriptionFactory { listOf(Subscription("plain", SubscriptionFilter(BANK)) {}) }
        fun specs(vararg names: String) = fastNodeOptions().let {
            val parked =
                ParkedRetryOptions(
                    specs = names.map { SubscriptionId(BANK, it) }.associateWith { ParkedRetrySpec(maxRetries = 1) }
                )
            it.copyWith(subscription = it.subscription.copyWith(parked = parked))
        }
        val node = node("node-1", options = specs("plain", "wow.snapshot", "wow.access"), subscriptions = plain)
        node.parked.subscriptions.assert().contains(SubscriptionId(BANK, "wow.snapshot"))
        assertThrows<WowConfigurationException> {
            node("node-2", options = specs("Plain.typo"), subscriptions = plain)
        }.message.assert().contains("parked retry spec", "subscription $BANK.Plain.typo", "plain")
    }

    @Test
    fun `a retry budget configured for a subscription the node does not have is refused (P3)`() {
        val plain = SubscriptionFactory { listOf(Subscription("plain", SubscriptionFilter(BANK)) {}) }
        fun retries(vararg names: String) = fastNodeOptions().let {
            it.copyWith(
                subscription = it.subscription.copyWith(
                    retries = names.map { SubscriptionId(BANK, it) }.associateWith { HandlerRetry(maxAttempts = 1) }
                )
            )
        }
        node("node-1", options = retries("plain", "wow.snapshot", "wow.access"), subscriptions = plain)
        assertThrows<WowConfigurationException> {
            node("node-2", options = retries("Plain.typo"), subscriptions = plain)
        }.message.assert().contains("retry budget", "subscription $BANK.Plain.typo", "plain")
    }

    @Test
    fun `the node's default wait timeout is its gateway's`() {
        val options = fastNodeOptions().copyWith(wait = WaitOptions(defaultTimeout = 3.seconds))
        node("node-1", options = options).gateway.defaultWaitTimeout(BANK).assert().isEqualTo(3.seconds)
        assertThrows<IllegalArgumentException> {
            fastNodeOptions().copyWith(wait = WaitOptions(defaultTimeout = Duration.ZERO))
        }
    }

    @Test
    fun `a node whose Manifest disagrees with its models is not built`() {
        val manifest = accountsManifest(BANK, LEDGER)
        assertThrows<me.ahoo.wow.runtime.manifest.ManifestMismatchException> {
            ServiceNode(
                NODE_SERVICE,
                "node-1",
                "node-1.internal:9090",
                listOf(accountModel(BANK), accountModel(AUDIT)),
                manifest,
                InMemoryBackends(),
                channel,
            )
        }.differences.assert().containsExactlyInAnyOrder(
            "aggregate $AUDIT.account has a model but is not in the Manifest",
            "aggregate $LEDGER.account is in the Manifest but has no model",
        )
    }

    @Test
    fun `a creation without an id keeps the id its request created in the context's log (D191)`(): Unit = runBlocking {
        val node = node("node-1").also { it.start() }
        val request = deposit().copyWith(requestId = "deposit-1")
        val first = node.gateway.execute(request)
        first.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        val again = node.gateway.execute(request)
        again.ref.assert().isEqualTo(first.ref)
        again.duplicate.assert().isTrue()
        node.gateway.execute(deposit(context = "nowhere").copyWith(requestId = "deposit-1"))
            .error!!.code.assert().isEqualTo(FrameworkCodes.COMMAND_UNHANDLED)
        node.stop()
    }

    @Test
    fun `a node refuses contracts of another Manifest`() {
        assertThrows<IllegalArgumentException> {
            ServiceNode(
                NODE_SERVICE,
                "node-1",
                "node-1.internal:9090",
                listOf(accountModel(BANK)),
                accountsManifest(BANK),
                InMemoryBackends(),
                channel,
                contracts = ContractDirectory(accountsManifest(BANK, LEDGER)),
            )
        }.message.assert().contains("contracts")
    }

    @Test
    fun `a node refuses a Manifest of another service`() {
        assertThrows<IllegalArgumentException> {
            ServiceNode(
                "other-service",
                "node-1",
                "node-1.internal:9090",
                listOf(accountModel(BANK)),
                accountsManifest(BANK),
                InMemoryBackends(),
                channel,
            )
        }.message.assert().contains("other-service")
    }
}
