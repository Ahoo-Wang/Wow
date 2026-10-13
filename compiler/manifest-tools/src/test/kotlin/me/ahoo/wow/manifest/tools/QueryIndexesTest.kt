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

import com.fasterxml.jackson.annotation.JsonProperty
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.annotation.QueryIndex
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.Manifest.Companion.schemaRef
import me.ahoo.wow.manifest.StateManifest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** The `x-wow-index` of a state schema ([QueryIndexes]) and the checks behind it. */
class QueryIndexesTest {
    data class Address(val city: String, val zip: String)

    data class Line(val sku: String)

    @QueryIndex(on = "ownerId", with = ["lastEventTime"])
    @QueryIndex(name = "by_city", on = "state.address.city", with = ["state.shipping.zip", "state.lines.sku"])
    data class OrderState(
        @field:QueryIndex(with = ["state.createdAt"])
        @field:QueryIndex(with = ["tenantId", "aggregateId", "version", "spaceId", "deleted", "tags"])
        val status: String,
        val createdAt: Long,
        @field:QueryIndex
        @JsonProperty("buyer")
        val buyerName: String,
        val address: Address,
        val shipping: Address?,
        val lines: List<Line>,
        @get:QueryIndex(name = "by_first_event", with = ["firstEventTime"])
        val note: String,
    )

    @Test
    fun `the state schema should carry its indexes`() {
        state(OrderState::class.java)["x-wow-index"].assert().isEqualTo(
            listOf(
                index("ix_order_owner_id_last_event_time", "ownerId", "lastEventTime"),
                index("by_city", "state.address.city", "state.shipping.zip", "state.lines.sku"),
                index("ix_order_state_buyer", "state.buyer"),
                index("by_first_event", "state.note", "firstEventTime"),
                index("ix_order_state_status_state_created_at", "state.status", "state.createdAt"),
                index(
                    "ix_order_state_status_tenant_id_aggregate_id_version_s_7c2af210",
                    "state.status",
                    "tenantId",
                    "aggregateId",
                    "version",
                    "spaceId",
                    "deleted",
                    "tags",
                ),
            ),
        )
    }

    data class Unindexed(val status: String)

    @Test
    fun `a state without indexes should have no index keyword`() {
        state(Unindexed::class.java).containsKey("x-wow-index").assert().isFalse()
    }

    @Test
    fun `a derived name should be deterministic and fit a PostgreSQL identifier`() {
        val keys = listOf("state.aVeryLongPropertyName", "state.anotherVeryLongPropertyName", "lastEventTime")
        val name = QueryIndexes.defaultName("purchaseOrder", keys)
        name.assert().isEqualTo(QueryIndexes.defaultName("purchaseOrder", keys)).hasSize(QueryIndexes.MAX_NAME_LENGTH)
        name.assert().startsWith("ix_purchase_order_state_a_very_long_property_name_")
        QueryIndexes.defaultName("purchaseOrder", keys.reversed()).assert().isNotEqualTo(name)
        QueryIndexes.defaultName("cart", listOf("state.items")).assert().isEqualTo("ix_cart_state_items")
    }

    @QueryIndex(with = ["lastEventTime"])
    data class ClassIndexWithoutOn(val status: String)

    @Test
    fun `a class-level index should name its leading key`() {
        failure(ClassIndexWithoutOn::class.java).assert()
            .isEqualTo(
                "@QueryIndex on the state class ${ClassIndexWithoutOn::class.java.name} must name its leading key in on."
            )
    }

    data class PropertyIndexWithOn(@field:QueryIndex(on = "ownerId") val status: String)

    @Test
    fun `a property index should be led by its property`() {
        failure(PropertyIndexWithOn::class.java).assert().isEqualTo(
            "@QueryIndex on ${PropertyIndexWithOn::class.java.name}.status: the property leads the index, so on " +
                "stays empty; list further keys in with.",
        )
    }

    data class UnknownStateKey(@field:QueryIndex(with = ["state.missing"]) val status: String)

    @QueryIndex(on = "operator")
    data class RemovedSystemField(val status: String)

    @QueryIndex(on = "state.address.street")
    data class UnknownNestedKey(val address: Address)

    @QueryIndex(on = "state.address.")
    data class EmptySegment(val address: Address)

    @Test
    fun `every key should be a known logical path`() {
        val known = "a key is one of tenantId, aggregateId, version, ownerId, spaceId, deleted, tags, " +
            "firstEventTime, lastEventTime or a state.* path of the state schema."
        failure(UnknownStateKey::class.java).assert().isEqualTo(
            "@QueryIndex(state.status, state.missing) of ${UnknownStateKey::class.java.name} has the unknown key " +
                "[state.missing]: $known",
        )
        failure(RemovedSystemField::class.java).assert().contains("has the unknown key [operator]")
        failure(UnknownNestedKey::class.java).assert().contains("has the unknown key [state.address.street]")
        failure(EmptySegment::class.java).assert().contains("has the unknown key [state.address.]")
    }

    data class RepeatedKey(@field:QueryIndex(name = "twice", with = ["state.status"]) val status: String)

    @Test
    fun `an index should not repeat a key`() {
        failure(RepeatedKey::class.java).assert()
            .isEqualTo("@QueryIndex(twice) of ${RepeatedKey::class.java.name} repeats the key state.status.")
    }

    @QueryIndex(name = "by_status", on = "state.status")
    data class DuplicateName(@field:QueryIndex(name = "by_status", with = ["ownerId"]) val status: String)

    @QueryIndex(name = "by_status", on = "state.status")
    data class AnotherState(val status: String)

    @Test
    fun `index names should be unique within a context`() {
        failure(DuplicateName::class.java).assert()
            .isEqualTo(
                "The query index name by_status is declared twice in the context shop: by shop.order and shop.order."
            )
        val twoAggregates = Manifest(
            contexts = listOf(
                ContextManifest(
                    name = "shop",
                    aggregates = listOf(
                        aggregate("order", AnotherState::class.java),
                        aggregate("cart", DuplicateNameFree::class.java),
                    ),
                ),
                // Another context may use the same name.
                ContextManifest(name = "billing", aggregates = listOf(aggregate("invoice", AnotherState2::class.java))),
            ),
        )
        assertThrows<IllegalStateException> { introspector.complete(twoAggregates) }.message.assert()
            .isEqualTo(
                "The query index name by_status is declared twice in the context shop: by shop.order and shop.cart."
            )
    }

    @QueryIndex(name = "by_status", on = "state.status")
    data class DuplicateNameFree(val status: String)

    @QueryIndex(name = "by_status", on = "state.status")
    data class AnotherState2(val status: String)

    @Test
    fun `the same index name should be allowed in another context`() {
        val manifest = Manifest(
            contexts = listOf(
                ContextManifest(name = "shop", aggregates = listOf(aggregate("order", AnotherState::class.java))),
                ContextManifest(name = "billing", aggregates = listOf(aggregate("invoice", AnotherState2::class.java))),
            ),
        )
        val defs = introspector.complete(manifest).defs
        listOf(AnotherState::class.java, AnotherState2::class.java).forEach { state ->
            (defs[state.name] as Map<*, *>)["x-wow-index"].assert().isEqualTo(
                listOf(index("by_status", "state.status"))
            )
        }
    }

    @Test
    fun `an indexed state should be the state of one aggregate`() {
        val shared = Manifest(
            contexts = listOf(
                ContextManifest(
                    name = "shop",
                    aggregates = listOf(
                        aggregate("order", Unindexed::class.java),
                        aggregate("cart", Unindexed::class.java)
                    ),
                ),
                ContextManifest(
                    name = "billing",
                    aggregates = listOf(aggregate("invoice", AnotherState::class.java)),
                ),
                ContextManifest(
                    name = "audit",
                    aggregates = listOf(aggregate("record", AnotherState::class.java)),
                ),
            ),
        )
        assertThrows<IllegalStateException> { introspector.complete(shared) }.message.assert().isEqualTo(
            "The state ${AnotherState::class.java.name} declares query indexes, so it is the state of one aggregate " +
                "only, but billing.invoice and audit.record share it.",
        )
    }

    data class Nested(@field:QueryIndex val city: String)

    data class HoldsNested(val nested: Nested)

    @QueryIndex(on = "state.city")
    data class IndexedValue(val city: String)

    data class HoldsIndexedValue(val value: IndexedValue)

    @Test
    fun `only a state should declare an index`() {
        failure(HoldsNested::class.java).assert().isEqualTo(
            "@QueryIndex on ${Nested::class.java.name}.city: only a property of an aggregate state class leads a " +
                "query index; declare it on the state class instead, with on = \"state.<path>\".",
        )
        failure(HoldsIndexedValue::class.java).assert().isEqualTo(
            "@QueryIndex on ${IndexedValue::class.java.name}: only an aggregate state class declares a query index.",
        )
    }

    private val introspector = SchemaIntrospector(javaClass.classLoader)

    private fun aggregate(name: String, state: Class<*>) =
        AggregateManifest(name = name, state = StateManifest(schemaRef(state.name)))

    private fun state(state: Class<*>): Map<*, *> =
        introspector.complete(QueryKeywordsTest.fragment(state)).defs[state.name] as Map<*, *>

    private fun failure(state: Class<*>): String? =
        assertThrows<IllegalStateException> { introspector.complete(QueryKeywordsTest.fragment(state)) }.message

    private fun index(name: String, vararg keys: String): Map<String, Any> = mapOf(
        "name" to name,
        "keys" to keys.toList()
    )
}
