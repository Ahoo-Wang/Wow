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

import kotlinx.coroutines.test.runTest
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.access.AccessAttribute
import me.ahoo.wow.kernel.aggregate
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.migrate.example.AddressChanged
import me.ahoo.wow.migrate.example.NineXExample
import me.ahoo.wow.migrate.example.OrderCreated
import me.ahoo.wow.migrate.example.OrderItem
import me.ahoo.wow.migrate.example.OrderPaid
import me.ahoo.wow.migrate.example.OrderReceived
import me.ahoo.wow.migrate.example.OrderShipped
import me.ahoo.wow.migrate.example.OrderStatus
import me.ahoo.wow.migrate.example.ShippingAddress
import org.junit.jupiter.api.Test
import tools.jackson.databind.node.ObjectNode
import java.math.BigDecimal

internal class TagsCompareTest : TagsMigrationFixtures() {
    @Test
    fun `compare compares the mapped tags with the access attributes V10 derives, values in any order`() = runTest {
        val (source, _) = store()
        val plan = MigrationPlan(map(), manifest)
        val log = InMemoryEventLog(4)
        val checkpoints = InMemoryCheckpointRepository()
        EventStreamImporter(source, plan, { log }, checkpoints).importAll(order).imported.assert().isEqualTo(6)
        val models = MigrationModels(listOf(taggedOrderModel, NineXExample.cartModel))
        val printed = mutableListOf<String>()
        fun comparison(snapshot: NineXSnapshot, with: MigrationPlan = plan) = StateComparison(
            InMemoryNineXSnapshotSource(listOf(snapshot), source),
            source,
            with,
            models,
            { log },
            checkpoints,
            CompareOptions(restart = true),
            { printed += it },
        )
        // The 9.x snapshot holds the team values in another order, and the derived level and channel.
        comparison(snapshot(6, SNAPSHOT_TAGS)).compare(order).passed.assert().isTrue()
        printed.none { it.startsWith("DIFFERS") }.assert().isTrue()
        comparison(snapshot(6, """{"region": ["west"], "team": ["a", "b", "c"], "level": ["1"], "channel": ["web"]}"""))
            .compare(order).passed.assert().isFalse()
        printed.single { it.startsWith("DIFFERS") }.assert().endsWith(
            "\$access.region: 9.x \"west\", V10 \"east\"; \$access.teams: 9.x [\"a\",\"b\",\"c\"], V10 [\"a\",\"b\"]",
        )
        // Values the attribute cannot hold show as 9.x stored them; a key the snapshot lacks, as none.
        printed.clear()
        comparison(
            snapshot(6, """{"region": ["east", "west"], "team": ["a", "b"], "level": ["1"], "channel": ["web"]}""")
        )
            .compare(order).passed.assert().isFalse()
        printed.single { it.startsWith("DIFFERS") }.assert()
            .endsWith("\$access.region: 9.x [\"east\",\"west\"], V10 \"east\"")
        printed.clear()
        comparison(snapshot(6, """{"team": ["a", "b"], "level": ["1"], "channel": ["web"]}""")).compare(order)
        printed.single { it.startsWith("DIFFERS") }.assert().endsWith("\$access.region: 9.x (none), V10 \"east\"")
        // A derived key mapped to an attribute the Manifest does not declare: the check names it; compare passes it by.
        val undeclared = MigrationPlan(
            map { copy(tags = tags + TagMapping("zone", "zone", TagSource.DERIVED)) },
            manifest
        )
        comparison(snapshot(6, SNAPSHOT_TAGS), undeclared).compare(order).passed.assert().isTrue()
    }

    private companion object {
        /** The order's V10 model, its tags as access attributes: two set by `OrderTagged`, two derived. */
        val taggedOrderModel = aggregate<TaggedOrderState>(NineXExample.CONTEXT, "order") {
            initial { TaggedOrderState(it.id) }
            evolve<OrderCreated>(revision = 2) { created ->
                address = created.address
                items = created.items
                totalAmount = created.items.map { it.totalPrice }.fold(BigDecimal.ZERO, BigDecimal::add)
                status = OrderStatus.CREATED
            }
            upgrade<OrderCreated>(1) { node -> (node as ObjectNode).apply { remove("orderId") } }
            evolve<AddressChanged> { address = it.shippingAddress }
            evolve<OrderPaid> { paid ->
                paidAmount += paid.amount
                if (paid.paid) {
                    status = OrderStatus.PAID
                }
            }
            evolve<OrderShipped> { status = OrderStatus.SHIPPED }
            evolve<OrderReceived> { status = OrderStatus.RECEIVED }
            evolve<OrderTagged> { tagged ->
                region = tagged.region
                teams = tagged.teams
            }
            retired(NineXExample.ORDER_ARCHIVED)
            access(AccessAttribute.single("region", String::class.java)) { it.region }
            access(AccessAttribute.multiple("teams", String::class.java)) { it.teams }
            access(AccessAttribute.single("level", Long::class.javaObjectType)) { it.items.size.toLong() }
            access(AccessAttribute.single("channel", String::class.java)) { "web" }
        }
    }
}

/** The example's order state with its tags as fields, as a V10 port declares them. */
internal class TaggedOrderState(val id: String) {
    var items: List<OrderItem> = emptyList()
    var address: ShippingAddress? = null
    var totalAmount: BigDecimal = BigDecimal.ZERO
    var paidAmount: BigDecimal = BigDecimal.ZERO
    var status: OrderStatus = OrderStatus.CREATED
    val payable: BigDecimal
        get() = totalAmount.minus(paidAmount)
    var region: String? = null
    var teams: Set<String> = emptySet()
}
