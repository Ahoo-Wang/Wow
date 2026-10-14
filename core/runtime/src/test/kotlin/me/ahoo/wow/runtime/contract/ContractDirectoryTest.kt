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

package me.ahoo.wow.runtime.contract

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.ManifestFragment
import me.ahoo.wow.manifest.PublishedManifest
import me.ahoo.wow.manifest.ServiceInfo
import me.ahoo.wow.manifest.SubscriptionKind
import me.ahoo.wow.manifest.SubscriptionSource
import me.ahoo.wow.runtime.contract.ContractFixtures.shop
import me.ahoo.wow.runtime.contract.ContractFixtures.subscription
import me.ahoo.wow.runtime.wait.SubscriptionDirectory
import me.ahoo.wow.runtime.wait.WaitChain
import me.ahoo.wow.spi.log.EventStreamRecord
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ContractDirectoryTest {
    private val directory = ContractDirectory(shop(), ContractFixtures.peers)

    private fun problems(manifest: Manifest, nullable: Set<String> = emptySet()): List<String> =
        assertThrows<ContractException> { ContractDirectory(manifest, ContractFixtures.peers, nullable) }.problems

    @Test
    fun `contexts should resolve to their services`() {
        directory.serviceOf("shop").assert().isEqualTo(SHOP_SERVICE)
        directory.serviceOf("inventory").assert().isEqualTo("inventory-service")
        directory.serviceOf("payment").assert().isEqualTo("payment-service")
        directory.serviceOf("unknown").assert().isNull()
        directory.isLocal("shop").assert().isTrue()
        directory.isLocal("inventory").assert().isFalse()
        directory.isLocal("unknown").assert().isFalse()
        directory.contextOf("inventory")!!.aggregates.map { it.name }.assert().containsExactly("stock", "bin", "ledger")
        directory.aggregateOf(AggregateType("inventory", "stock"))!!.commands.size.assert().isEqualTo(2)
        directory.aggregateOf(AggregateType("inventory", "missing")).assert().isNull()
        directory.aggregateOf(AggregateType("missing", "stock")).assert().isNull()
    }

    @Test
    fun `an aggregate's published stream should give its topic and whether it carries the state`() {
        directory.publishedOf(AggregateType("inventory", "stock"))
            .assert().isEqualTo(PublishedManifest("stock", "wow.inventory.stock.state", true))
        directory.publishedOf(AggregateType("inventory", "bin"))
            .assert().isEqualTo(PublishedManifest("bin", "inventory-bins", false))
        directory.publishedOf(AggregateType("inventory", "ledger")).assert().isNull()
        directory.publishedOf(AggregateType("shop", "cart"))?.topic.assert().isEqualTo("wow.shop.cart.state")
    }

    @Test
    fun `a command's schema reference should resolve to its type name and back`() {
        directory.commandTypeOf(Manifest.schemaRef("sample.inventory.ReserveStock"))
            .assert().isEqualTo("inventory.stock.ReserveStock")
        directory.commandTypeOf(Manifest.schemaRef("sample.shop.AddItem")).assert().isEqualTo("shop.cart.AddItem")
        directory.commandTypeOf("payment.payment.Pay").assert().isEqualTo("payment.payment.Pay")
        directory.commandTypeOf("payment.payment.Refund").assert().isNull()
        directory.commandTypeOf(Manifest.schemaRef("sample.Unknown")).assert().isNull()
        directory.schemaOf("inventory.stock.ReserveStock")
            .assert().isEqualTo(Manifest.schemaRef("sample.inventory.ReserveStock"))
        directory.schemaOf("inventory.stock.Unknown").assert().isNull()
    }

    @Test
    fun `subscriptions should resolve by context and name with their kinds`() {
        directory.subscriptionOf(
            "inventory",
            "StockProjector.onStock"
        )!!.kind.assert().isEqualTo(SubscriptionKind.PROJECTION)
        directory.subscriptionOf("shop", "OrderSaga.onStock")!!.kind.assert().isEqualTo(SubscriptionKind.SAGA)
        directory.subscriptionOf("inventory", "OrderSaga.onStock").assert().isNull()
        directory.kindOf("inventory", "StockProjector.onStock").assert().isEqualTo(FunctionKind.PROJECT)
        directory.kindOf("inventory", "RestockSaga.onReserved").assert().isEqualTo(FunctionKind.SAGA)
        directory.kindOf("inventory", "BinWatcher.onBin").assert().isEqualTo(FunctionKind.REACT)
        // An @OnRejected handler handles rejections, not commits: no hop names it.
        directory.kindOf("inventory", "RestockSaga.onRejected").assert().isNull()
        directory.kindOf("inventory", "Unknown.onEvent").assert().isNull()
        directory.kindOf("unknown", "StockProjector.onStock").assert().isNull()
    }

    @Test
    fun `the snapshot subscription should be known where an aggregate keeps snapshots`() {
        directory.kindOf("inventory", Hop.SNAPSHOT_SUBSCRIPTION).assert().isEqualTo(FunctionKind.SNAPSHOT)
        directory.kindOf("payment", Hop.SNAPSHOT_SUBSCRIPTION).assert().isNull()
        directory.covers(Hop.SNAPSHOT_SUBSCRIPTION, AggregateType("inventory", "stock")).assert().isTrue()
        directory.covers(Hop.SNAPSHOT_SUBSCRIPTION, AggregateType("inventory", "bin")).assert().isFalse()
        directory.covers(Hop.SNAPSHOT_SUBSCRIPTION, AggregateType("inventory", "missing")).assert().isFalse()
    }

    @Test
    fun `a subscription should cover the aggregates of its own context it reads`() {
        directory.covers("StockProjector.onStock", AggregateType("inventory", "stock")).assert().isTrue()
        directory.covers("StockProjector.onStock", AggregateType("inventory", "bin")).assert().isFalse()
        directory.covers("RestockSaga.onReserved", AggregateType("inventory", "bin")).assert().isTrue()
        directory.covers("Unknown.onEvent", AggregateType("inventory", "stock")).assert().isFalse()
        // It reads another context's published stream, not its own context's commits.
        directory.covers("OrderSaga.onStock", AggregateType("shop", "cart")).assert().isFalse()
        directory.covers("CartProjector.onCart", AggregateType("shop", "cart")).assert().isTrue()
    }

    @Test
    fun `a Manifest should say nothing of a subscription's progress`(): Unit = runBlocking {
        directory.hasHandled("StockProjector.onStock", mockk<EventStreamRecord>()).assert().isFalse()
    }

    @Test
    fun `over the runtime it should answer the own context from the runtime and others from the Manifests`(): Unit =
        runBlocking {
            val stream = mockk<EventStreamRecord>()
            val runtime = mockk<SubscriptionDirectory> {
                every { kindOf("shop", "Live.onCart") } returns FunctionKind.REACT
                every { kindOf("shop", "CartProjector.onCart") } returns null
                every { covers("Live.onCart", AggregateType("shop", "cart")) } returns true
                coEvery { hasHandled("Live.onCart", stream) } returns true
            }
            val over = directory.over(runtime, "shop")
            over.kindOf("shop", "Live.onCart").assert().isEqualTo(FunctionKind.REACT)
            over.kindOf("shop", "CartProjector.onCart").assert().isNull()
            over.kindOf("inventory", "StockProjector.onStock").assert().isEqualTo(FunctionKind.PROJECT)
            over.covers("Live.onCart", AggregateType("shop", "cart")).assert().isTrue()
            over.covers("StockProjector.onStock", AggregateType("inventory", "stock")).assert().isTrue()
            over.hasHandled("Live.onCart", stream).assert().isTrue()
        }

    @Test
    fun `a saga's derives should resolve to the contexts of its commands`() {
        directory.hasContext("payment").assert().isTrue()
        directory.hasContext("nowhere").assert().isFalse()
        directory.derivedContextsOf("shop", "OrderSaga.onStock").assert().containsExactly("inventory", "shop")
        directory.derivedContextsOf("inventory", "RestockSaga.onReserved").assert().containsExactly("inventory")
        // Not declared, an @OnRejected handler, or not known: unchecked.
        directory.derivedContextsOf("shop", "CartProjector.onCart").assert().isNull()
        directory.derivedContextsOf("inventory", "RestockSaga.onRejected").assert().isNull()
        directory.derivedContextsOf("shop", "Unknown.onCart").assert().isNull()
        // A schema reference no Manifest declares cannot be placed in a context: unchecked too.
        val unresolved = ContractDirectory(
            shop(
                subscriptions = listOf(subscription("Lost.onCart", derives = listOf(Manifest.schemaRef("sample.Lost"))))
            ),
            ContractFixtures.peers,
        )
        unresolved.derivedContextsOf("shop", "Lost.onCart").assert().isNull()
        val over = directory.over(SubscriptionDirectory.NONE, "own")
        over.hasContext("own").assert().isTrue()
        over.hasContext("inventory").assert().isTrue()
        over.hasContext("nowhere").assert().isFalse()
        over.derivedContextsOf("shop", "OrderSaga.onStock").assert().containsExactly("inventory", "shop")
    }

    @Test
    fun `a chain should follow a saga's hop only into a known context it derives commands of`() {
        val cart = AggregateType("shop", "cart")
        fun check(vararg hops: Hop): Any = WaitChain.of(WaitPlan.of(*hops), cart, directory, 4).let {
            (it as? WaitChain.Invalid)?.reason ?: it
        }
        val saga = Hop.handled("OrderSaga.onStock")
        check(Hop.committed(), saga, Hop.committed("inventory")).assert().isInstanceOf(WaitChain::class.java)
        check(Hop.committed(), saga, Hop.accepted("shop"), Hop.committed("shop"))
            .assert().isInstanceOf(WaitChain::class.java)
        check(Hop.committed(), saga, Hop.committed("payment")).toString().assert()
            .contains("derives commands of [inventory, shop] only").contains("D138")
        check(Hop.committed(), saga, Hop.committed("nowhere")).toString().assert().contains("unknown context")
        // A hop of another context's saga, then into what it derives.
        val restock = Hop.handled("RestockSaga.onReserved", "inventory")
        check(Hop.committed(), restock, Hop.committed("inventory")).assert().isInstanceOf(WaitChain::class.java)
        check(Hop.committed(), restock, Hop.committed("shop")).toString().assert().contains("[inventory] only")
        // The root command's own hops are of its context.
        check(Hop.committed("inventory")).toString().assert().contains("not of the command's context shop")
        check(Hop.accepted("payment"), Hop.committed()).toString().assert().contains("not of the command's context")
    }

    @Test
    fun `a directory without peers should know the service's own contexts only`() {
        val alone = ContractDirectory(shop(subscriptions = listOf(subscription("CartProjector.onCart"))))
        alone.peers.assert().isEmpty()
        alone.serviceOf("shop").assert().isEqualTo(SHOP_SERVICE)
        alone.serviceOf("inventory").assert().isNull()
    }

    @Test
    fun `an @OnCommitted handler should be no hop, and the commands it takes should count as sent outside`() {
        val committed = subscription(
            "OrderSaga.onReserved",
            source = SubscriptionSource(),
            derives = listOf("shop.cart.AddItem"),
            committed = listOf("inventory.stock.ReserveStock"),
        )
        val directory = ContractDirectory(shop(subscriptions = listOf(committed)), ContractFixtures.peers)
        directory.kindOf("shop", "OrderSaga.onReserved").assert().isNull()
        directory.derivedContextsOf("shop", "OrderSaga.onReserved").assert().isNull()
        directory.commandsOutside(setOf("shop"))
            .assert().isEqualTo(mapOf("shop/OrderSaga.onReserved" to sortedSetOf("inventory")))
    }

    @Test
    fun `a source of an unknown context or aggregate, or of an unpublished one, should fail`() {
        problems(
            shop(
                subscriptions = listOf(
                    subscription("A.onUnknown", source = SubscriptionSource("warehouse", listOf("bin"))),
                    subscription("B.onMissing", source = SubscriptionSource("inventory", listOf("missing"))),
                    subscription("C.onLedger", source = SubscriptionSource("inventory", listOf("ledger"))),
                    // Its own context and a rejection handler are not cross-context sources.
                    subscription("D.onCart"),
                    subscription("E.onRejected", source = SubscriptionSource("warehouse"), rejected = emptyList()),
                    subscription("F.onCommitted", source = SubscriptionSource("warehouse"), committed = emptyList()),
                ),
            ),
        ).assert().containsExactly(
            "subscription shop.A.onUnknown reads context warehouse, which no Manifest declares",
            "subscription shop.B.onMissing reads aggregate inventory.missing, which context inventory does not have",
            "subscription shop.C.onLedger reads aggregate inventory.ledger, which context inventory does not publish",
        )
    }

    @Test
    fun `a source published without its state should fail unless its handler takes a nullable state`() {
        val manifest = shop(
            subscriptions = listOf(
                subscription("Bins.onBin", SubscriptionKind.PROJECTION, SubscriptionSource("inventory", listOf("bin"))),
                // No aggregates named: every aggregate the context publishes.
                subscription("All.onInventory", SubscriptionKind.REACTOR, SubscriptionSource("inventory")),
            ),
        )
        problems(manifest).assert().containsExactly(
            "subscription shop.Bins.onBin reads inventory.bin, published without its state (inventory-bins), so " +
                "its handler takes a nullable state: StateEvent<S?>",
            "subscription shop.All.onInventory reads inventory.bin, published without its state (inventory-bins), " +
                "so its handler takes a nullable state: StateEvent<S?>",
        )
        ContractDirectory(manifest, ContractFixtures.peers, setOf("Bins.onBin", "All.onInventory"))
            .nullableStates.assert().containsExactlyInAnyOrder("Bins.onBin", "All.onInventory")
    }

    @Test
    fun `a handler the Manifest marks as taking a nullable state should read a stream without its state`() {
        val nullable = SubscriptionSource("inventory", listOf("bin"), nullableState = true)
        val manifest = shop(
            subscriptions = listOf(
                subscription("Bins.onBin", SubscriptionKind.PROJECTION, nullable),
                subscription("Strict.onBin", SubscriptionKind.PROJECTION, nullable.copy(nullableState = false)),
            ),
        )
        problems(manifest).assert().containsExactly(
            "subscription shop.Strict.onBin reads inventory.bin, published without its state (inventory-bins), so " +
                "its handler takes a nullable state: StateEvent<S?>",
        )
        ContractDirectory(manifest, ContractFixtures.peers, setOf("Strict.onBin"))
            .nullableStates.assert().containsExactlyInAnyOrder("Bins.onBin", "Strict.onBin")
    }

    @Test
    fun `a source in another context of the service itself should be checked against its Manifest`() {
        val billing = ContextManifest("billing")
        problems(
            shop(
                subscriptions = listOf(
                    subscription("A.onAccount", source = SubscriptionSource("billing", listOf("account")))
                ),
                contexts = listOf(billing),
            ),
        ).assert().containsExactly(
            "subscription shop.A.onAccount reads aggregate billing.account, which context billing does not have",
        )
    }

    @Test
    fun `two peers of different services declaring one context should fail`() {
        val rival = ManifestFragment(
            "rival.json",
            Manifest(
                service = ServiceInfo("rival"),
                contexts = listOf(ContextManifest("inventory"), ContextManifest("shop"))
            ),
        )
        assertThrows<ContractException> { ContractDirectory(shop(), ContractFixtures.peers + rival) }
            .problems.assert().containsExactly(
                "context inventory is declared by the peers inventory-service " +
                    "(${ContractFixtures.peers.first().source}) and rival (rival.json)",
            )
    }

    @Test
    fun `a copy of the service's own Manifest among the peers should be no peer of the contexts it serves`() {
        // On a class path several servers share (DX gap G21): another server carries this service's Manifest as its
        // peer, twice here, and a third peer declares a context the node serves besides one of its own.
        val copy = ManifestFragment("other-server.jar!/$SHOP_SERVICE.json", shop())
        val again = ManifestFragment("third-server.jar!/$SHOP_SERVICE.json", shop())
        val monolith = ManifestFragment(
            "monolith.json",
            Manifest(
                service = ServiceInfo("monolith"),
                contexts = listOf(ContextManifest("shop"), ContextManifest("audit"))
            ),
        )
        val contracts = ContractDirectory(shop(), ContractFixtures.peers + copy + again + monolith)

        contracts.peers.map { it.manifest.service?.name }.assert()
            .containsExactly("inventory-service", "payment-service", "monolith")
        contracts.peers.last().manifest.contexts.map { it.name }.assert().containsExactly("audit")
        contracts.isLocal("shop").assert().isTrue()
        contracts.serviceOf("shop").assert().isEqualTo(SHOP_SERVICE)
        contracts.serviceOf("audit").assert().isEqualTo("monolith")
        contracts.serviceOf("inventory").assert().isEqualTo("inventory-service")
    }

    @Test
    fun `a copy of the service's own Manifest should keep the contexts the node does not serve`() {
        val own = shop()
        val copy = ManifestFragment(
            "other-server.jar!/$SHOP_SERVICE.json",
            own.copy(contexts = own.contexts + ContextManifest("shop-admin")),
        )
        val contracts = ContractDirectory(own, ContractFixtures.peers + copy)

        contracts.peers.last().manifest.contexts.map { it.name }.assert().containsExactly("shop-admin")
        contracts.isLocal("shop-admin").assert().isFalse()
        contracts.serviceOf("shop-admin").assert().isEqualTo(SHOP_SERVICE)
    }

    @Test
    fun `load should read the peers on the class loader`() {
        val loaded = ContractDirectory.load(shop(), peersLoader("peers"))
        loaded.peers.map { it.manifest.service?.name }.assert().containsExactly("inventory-service", "payment-service")
        loaded.local.assert().isEqualTo(shop())
    }
}
