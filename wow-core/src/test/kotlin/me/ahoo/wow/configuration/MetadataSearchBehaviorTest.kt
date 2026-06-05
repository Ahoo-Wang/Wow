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

package me.ahoo.wow.configuration

import me.ahoo.test.asserts.assert
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class MetadataSearchBehaviorTest {

    @Test
    fun `scope context search should prefer the most specific matching scope`() {
        val searcher = metadata().toScopeContextSearcher()

        searcher.search("me.ahoo.sales.order.command.CreateOrder")!!.contextName.assert().isEqualTo("sales")
        searcher.search("me.ahoo.billing.invoice.CreateInvoice")!!.contextName.assert().isEqualTo("billing")
        searcher.search("me.ahoo.unknown").assert().isNull()
    }

    @Test
    fun `scope aggregate search should match aggregate scopes commands and events`() {
        val searcher = metadata().toScopeNamedAggregateSearcher()

        searcher.search("me.ahoo.sales.order.OrderState")
            .assert().isEqualTo(MaterializedNamedAggregate("sales", "Order"))
        searcher.search("me.ahoo.sales.order.command.CreateOrder")
            .assert().isEqualTo(MaterializedNamedAggregate("sales", "Order"))
        searcher.search("me.ahoo.sales.order.event.OrderCreated")
            .assert().isEqualTo(MaterializedNamedAggregate("sales", "Order"))
        searcher.search("me.ahoo.missing").assert().isNull()
    }

    @Test
    fun `required search should fail when no scope matches`() {
        val exception = assertThrows<IllegalStateException> {
            metadata().toScopeNamedAggregateSearcher().requiredSearch("me.ahoo.missing")
        }

        exception.message.assert().contains("does not have a matching metadata definition")
    }

    @Test
    fun `aggregate type search should include loadable types and skip missing types`() {
        val metadata = metadata()

        val typeNamedAggregate = metadata.toTypeNamedAggregateSearcher()
        val namedAggregateType = metadata.toNamedAggregateTypeSearcher()

        typeNamedAggregate[FixtureAggregate::class.java]
            .assert().isEqualTo(MaterializedNamedAggregate("sales", "Order"))
        namedAggregateType[MaterializedNamedAggregate("sales", "Order")]
            .assert().isEqualTo(FixtureAggregate::class.java)
        typeNamedAggregate.values.assert().doesNotContain(MaterializedNamedAggregate("billing", "Invoice"))
    }

    private fun metadata(): WowMetadata = WowMetadata(
        contexts = mapOf(
            "sales" to BoundedContext(
                scopes = linkedSetOf("me.ahoo.sales"),
                aggregates = mapOf(
                    "Order" to Aggregate(
                        scopes = linkedSetOf("me.ahoo.sales.order"),
                        type = FixtureAggregate::class.java.name,
                        commands = linkedSetOf("me.ahoo.sales.order.command"),
                        events = linkedSetOf("me.ahoo.sales.order.event"),
                    ),
                ),
            ),
            "billing" to BoundedContext(
                scopes = linkedSetOf("me.ahoo.billing"),
                aggregates = mapOf(
                    "Invoice" to Aggregate(
                        scopes = linkedSetOf("me.ahoo.billing.invoice"),
                        type = "me.ahoo.wow.configuration.DoesNotExist",
                    ),
                ),
            ),
        ),
    )

    private class FixtureAggregate
}
