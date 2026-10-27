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

package me.ahoo.wow.manifest.tools

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import me.ahoo.test.asserts.assert
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.CommandManifest
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.ErrorManifest
import me.ahoo.wow.manifest.EventManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.Ownership
import me.ahoo.wow.manifest.QueryManifest
import me.ahoo.wow.manifest.SnapshotManifest
import me.ahoo.wow.manifest.StateManifest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ContractSourcesTest {
    @TempDir
    lateinit var directory: File

    // The state of the compiler's QueryPropertiesTest, whose <State>Properties names these paths.
    enum class Status { NEW, PAID }

    @JvmInline
    value class Sku(val value: String)

    data class Line(val sku: Sku, val quantity: Int, val parent: Line? = null)

    data class Address(val city: String, @JsonProperty("zip") val postalCode: String)

    @JsonIgnoreProperties("draft")
    class OrderState(val id: String) {
        var customerId: String? = null
            private set
        var status: Status = Status.NEW
        var address: Address? = null
        var lines: List<Line> = emptyList()
        var attributes: Map<String, String> = emptyMap()
        var tags: Set<String> = emptySet()
        var totalURLCount: Int = 0

        @JsonIgnore
        var secret: String = ""
        var draft: String = ""

        @Suppress("unused")
        private var hidden: Int = 0
        val computed: Int get() = 1
    }

    private fun aggregate(
        name: String = "order",
        state: String = OrderState::class.java.name,
        queries: List<QueryManifest> = listOf(QueryManifest("snapshot"), QueryManifest("events")),
    ) = AggregateManifest(
        name = name,
        description = "An order */ of the shop",
        state = StateManifest(Manifest.schemaRef(state)),
        snapshot = SnapshotManifest(enabled = true),
        commands = listOf(CommandManifest("PlaceOrder", schema = Manifest.schemaRef("shop.api.order.PlaceOrder"))),
        events = listOf(EventManifest("OrderPlaced", schema = Manifest.schemaRef("shop.api.OrderPlaced"))),
        queries = queries,
        ownership = Ownership.TENANT,
    )

    /** The contract of a context `shop-ordering` of [aggregates], its schemas introspected as the plugin does. */
    private fun contract(vararg aggregates: AggregateManifest): Manifest {
        val context = ContextManifest("shop-ordering", description = "Takes orders", aggregates = aggregates.toList())
        val states = aggregates.map { Manifest.schemaKey(it.state.schema) }
            .filter { runCatching { Class.forName(it) }.isSuccess }
        val fragment = Manifest(contexts = listOf(context))
            .withDefs(
                mapOf(
                    "shop.api.order.PlaceOrder" to mapOf("type" to "object"),
                    "shop.api.OrderPlaced" to mapOf("type" to "object")
                )
            )
        val completed = SchemaIntrospector(javaClass.classLoader).complete(
            Manifest(contexts = listOf(ContextManifest("probe", aggregates = states.map { aggregate(state = it) })))
                .withDefs(fragment.defs),
        )
        return fragment.withDefs(completed.defs)
    }

    @Test
    fun `the state's paths should be named as the compiler names them`() {
        val defs = contract(aggregate()).defs
        StateFields.of(Manifest.schemaRef(OrderState::class.java.name), defs).associateBy(
            me.ahoo.wow.manifest.QueryFieldNames::constantOf,
        ).assert().isEqualTo(
            // QueryPropertiesTest's `a state's properties are its serialized paths under state`, without the system fields.
            mapOf(
                "STATE__ID" to "state.id",
                "STATE__CUSTOMER_ID" to "state.customerId",
                "STATE__STATUS" to "state.status",
                "STATE__ADDRESS" to "state.address",
                "STATE__ADDRESS__CITY" to "state.address.city",
                "STATE__ADDRESS__ZIP" to "state.address.zip",
                "STATE__LINES" to "state.lines",
                "STATE__LINES__SKU" to "state.lines.sku",
                "STATE__LINES__QUANTITY" to "state.lines.quantity",
                "STATE__LINES__PARENT" to "state.lines.parent",
                "STATE__ATTRIBUTES" to "state.attributes",
                "STATE__TAGS" to "state.tags",
                "STATE__TOTAL_URL_COUNT" to "state.totalURLCount",
            ),
        )
    }

    @Test
    fun `a schema walk should follow nullable alternatives and stop at leaves, cycles and missing definitions`() {
        val defs = mapOf(
            "S" to mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "owner" to mapOf("anyOf" to listOf(mapOf("type" to "null"), mapOf("\$ref" to "#/\$defs/P"))),
                    "kind" to mapOf("oneOf" to listOf(mapOf("\$ref" to "#/\$defs/A"), mapOf("\$ref" to "#/\$defs/B"))),
                    "1st" to mapOf("type" to "string"),
                    "first-name" to mapOf("type" to "string"),
                    "grid" to mapOf("type" to "array", "items" to mapOf("type" to "array", "items" to mapOf("\$ref" to "#/\$defs/P"))),
                    "missing" to mapOf("\$ref" to "#/\$defs/Missing"),
                ),
            ),
            "P" to mapOf("type" to "object", "properties" to mapOf("self" to mapOf("\$ref" to "#/\$defs/P"), "name" to mapOf("type" to "string"))),
        )
        StateFields.of("#/\$defs/S", defs).assert().containsExactly(
            "state.owner",
            "state.owner.self",
            "state.owner.name",
            "state.kind",
            "state.first-name",
            "state.grid",
            "state.grid.self",
            "state.grid.name",
            "state.missing",
        )
        StateFields.of("#/\$defs/Missing", defs).assert().isEmpty()
    }

    @Test
    fun `the context object should hold the aggregate's type, ref, facts and query fields`() {
        val source = ContractSources.of(contract(aggregate()), queryFields = true).single()
        source.path.assert().isEqualTo("shop/api/ShopOrderingContract.kt")
        source.text.assert().startsWith("// Generated by the Wow Gradle plugin").contains(
            "package shop.api\n",
            "import me.ahoo.wow.api.query.SnapshotProperties",
            " * Takes orders\n",
            "public object ShopOrderingContract {",
            "public const val NAME: String = \"shop-ordering\"",
            " * An order *&#47; of the shop\n",
            "public object Order : SnapshotProperties() {",
            "public const val CONTEXT: String = ShopOrderingContract.NAME",
            "public const val NAME: String = \"order\"",
            "public val TYPE: AggregateType = AggregateType(CONTEXT, NAME)",
            "public val OWNERSHIP: Ownership = Ownership.TENANT",
            "public const val SNAPSHOT: Boolean = true",
            "public val QUERY_MODELS: List<String> = listOf(\"snapshot\", \"events\")",
            "public fun ref(tenantId: String, id: String): AggregateRef = TYPE.ref(tenantId, id)",
            "public fun unassigned(tenantId: String): AggregateRef = TYPE.unassigned(tenantId)",
            "public val STATE__ADDRESS__ZIP: QueryField = QueryField(\"state.address.zip\")",
        )
    }

    @Test
    fun `rejection codes should be constants of the aggregates' and the context's Errors objects`() {
        val errors = listOf(
            ErrorManifest("shop-ordering.order.invalid-items", 422, "An order has items."),
            ErrorManifest("shop-ordering.order.wrong-status", 409),
            ErrorManifest("shop-ordering.closed", 503, "The shop is closed."),
            ErrorManifest("shop-ordering.order.nested.code", 422),
            ErrorManifest("shop-ordering.404", 404),
        )
        val context = ContextManifest("shop-ordering", aggregates = listOf(aggregate()), errors = errors)
        val text = ContractSources.of(Manifest(contexts = listOf(context)), queryFields = false).single().text
        text.assert().contains(
            "    /** The rejection codes the context shares. */\n    public object Errors {",
            "        /** The shop is closed. (HTTP 503) */\n        public const val CLOSED: String = \"shop-ordering.closed\"",
            "public const val ORDER__NESTED__CODE: String = \"shop-ordering.order.nested.code\"",
            "public const val _404: String = \"shop-ordering.404\"",
            "        /** The aggregate's rejection codes, each the full code. */\n        public object Errors {",
            "            /** An order has items. (HTTP 422) */\n" +
                "            public const val INVALID_ITEMS: String = \"shop-ordering.order.invalid-items\"",
            "public const val WRONG_STATUS: String = \"shop-ordering.order.wrong-status\"",
        )
        // A context without codes has no Errors object.
        ContractSources.of(contract(aggregate()), queryFields = false).single().text.assert().doesNotContain("Errors")
    }

    @Test
    fun `without wow-query-api, or with a withheld state, there should be no query fields`() {
        val plain = ContractSources.of(contract(aggregate()), queryFields = false).single().text
        plain.assert().contains("public object Order {").doesNotContain("QueryField", "SnapshotProperties")
        val withheld = contract(aggregate()).let {
            it.withDefs(it.defs + (OrderState::class.java.name to mapOf("type" to "object")))
        }
        ContractSources.of(withheld, queryFields = true).single().text.assert()
            .contains("public object Order : SnapshotProperties() {").doesNotContain("STATE__")
    }

    @Test
    fun `a context of the default package should be generated at the source root`() {
        val aggregate = AggregateManifest(
            name = "stock-item",
            state = StateManifest(Manifest.schemaRef("StockState")),
            commands = listOf(CommandManifest("Reserve", schema = Manifest.schemaRef("Reserve"))),
            events = listOf(EventManifest("Reserved", schema = Manifest.schemaRef("Reserved"))),
        )
        val source = ContractSources.of(
            Manifest(contexts = listOf(ContextManifest("inventory", aggregates = listOf(aggregate)))),
            queryFields = false,
        ).single()
        source.path.assert().isEqualTo("InventoryContract.kt")
        source.text.assert().doesNotContain("package ").contains("public object StockItem {", "listOf()")
    }

    @Test
    fun `the package should be the one the context's classes share`() {
        ContractSources.packageOf(listOf("shop.api.AddItem", "shop.api.cart.ItemAdded")).assert().isEqualTo("shop.api")
        ContractSources.packageOf(listOf("shop.api.AddItem", "billing.Pay")).assert().isEmpty()
        ContractSources.packageOf(listOf("AddItem")).assert().isEmpty()
        ContractSources.packageOf(emptyList()).assert().isEmpty()
    }

    @Test
    fun `names should become Pascal case`() {
        ContractSources.pascal("eshop-inventory").assert().isEqualTo("EshopInventory")
        ContractSources.pascal("order_item.line").assert().isEqualTo("OrderItemLine")
        ContractSources.pascal("cart").assert().isEqualTo("Cart")
    }

    @Test
    fun `the tools should write one file per context and clear what an earlier run left`() {
        val output = File(directory, "out").apply { mkdirs() }
        val stale = File(output, "Stale.kt").apply { writeText("") }
        val contractFile = File(directory, "contract.json").also { ManifestJson.write(contract(aggregate()), it) }
        ManifestTools.generateContractSources(listOf(contractFile, File(directory, "absent.json")), true, output)
            .assert().isEqualTo(1)
        stale.exists().assert().isFalse()
        File(output, "shop/api/ShopOrderingContract.kt").readText().assert().contains("STATE__LINES__SKU")
        ManifestTools.generateContractSources(emptyList(), true, output).assert().isZero()
        output.exists().assert().isFalse()
    }
}
