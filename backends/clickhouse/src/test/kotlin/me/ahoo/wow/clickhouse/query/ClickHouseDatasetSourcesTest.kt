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

package me.ahoo.wow.clickhouse.query

import com.clickhouse.client.api.Client
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.query.annotation.SensitivityLevel.DISPLAY
import me.ahoo.wow.clickhouse.sink.CatalogFixtures
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.StateManifest
import me.ahoo.wow.query.dataset.QueryDataset
import me.ahoo.wow.query.dataset.QueryDatasetConfig
import me.ahoo.wow.query.dataset.QueryDatasetSource
import me.ahoo.wow.query.dataset.QueryDatasetSource.Masked
import me.ahoo.wow.query.dataset.QueryDatasetSource.Opaque
import me.ahoo.wow.query.dataset.QueryDatasetSources
import me.ahoo.wow.query.schema.QueryMask
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.store.StoreResources
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * What the Sink's tables of a Manifest hold, as the Sink describes them in `wow_catalog` (#6587): a dataset over one
 * inherits its state's protection, from the same function over the same Manifest.
 */
class ClickHouseDatasetSourcesTest {
    private val sources = ClickHouseDatasetSources(CatalogFixtures.MANIFEST, DATABASE)

    private fun over(table: String): QueryDataset = QueryDataset.of(AggregateType("crm", "rows")) {
        tenant("tenant_id")
        storage(WowJson.mapper.createObjectNode().put("table", table))
        field("tenant_id") {}
    }

    private fun columnsOf(table: String): Map<String, QueryDatasetSource.Column> =
        checkNotNull(sources.sourceOf(over(table))).columns

    @Test
    fun `a flat column should hold its state property's value, masked as the state masks it`() {
        val source = checkNotNull(sources.sourceOf(over("crm_member_state_flat")))
        source.description.assert().isEqualTo("crm_db.crm_member_state_flat, the ClickHouse Sink's table of crm.member")
        source.columns.assert().isEqualTo(mapOf("phone" to PHONE))
        // The table may name its database, the one the Sink writes to.
        sources.sourceOf(over("$DATABASE.crm_member_state_flat")).assert().isEqualTo(source)
        // A state that protects nothing: its flat table is known, with nothing protected.
        columnsOf("crm_store_state_flat").assert().isEmpty()
    }

    @Test
    fun `a document column with protected paths in it should be opaque, as is state_numbers`() {
        val state = columnsOf("crm_member_state")
        // The flat columns computed on _state are masked as _state_flat's.
        state["phone"].assert().isEqualTo(PHONE)
        val document = state["state"] as Opaque
        document.paths.assert().contains("state.phone", "state.address.street", "state.cards.{key}", "state.notes")
        (state["state_numbers"] as Opaque).paths.assert().containsExactlyInAnyOrder("state.phone", "state.notes")
        val history = columnsOf("crm_member_state_events")
        history.keys.assert().containsExactlyInAnyOrder("state", "state_numbers", "events")
        history["events"].assert().isEqualTo(Opaque.of(listOf("events.body.phone")))
        columnsOf("crm_member_events").assert().isEqualTo(mapOf("body" to Opaque.of(listOf("body.phone"))))
        // Nothing protected in the state: no document column is opaque.
        columnsOf("crm_store_state").assert().isEmpty()
    }

    @Test
    fun `a table the Sink does not write, or of another database, should be unknown`() {
        sources.sourceOf(over("crm_member_orders")).assert().isNull()
        sources.sourceOf(over("other_db.crm_member_state_flat")).assert().isNull()
        assertThrows<IllegalArgumentException> { sources.sourceOf(over("a.b.c")) }
        sources.toString().assert().startsWith("ClickHouseDatasetSources(crm_db, [")
    }

    @Test
    fun `a table name two aggregates share should be unknown`() {
        fun aggregate(context: String) = ContextManifest(
            context,
            aggregates = listOf(AggregateManifest("order", state = StateManifest("#/\$defs/State"))),
        )
        val shared = Manifest(
            contexts = listOf(aggregate("shop-a"), aggregate("shop_a")),
            defs = mapOf("State" to mapOf("type" to "object", "properties" to emptyMap<String, Any>())),
        )
        ClickHouseDatasetSources(shared, DATABASE).sourceOf(over("shop_a_order_state_flat")).assert().isNull()
    }

    @Test
    fun `the backend's bindings should tell the sources of the node's Manifest, or none without one`() {
        val provider = ClickHouseDatasetProvider()
        val client = Client.Builder().addEndpoint("http://127.0.0.1:1").setUsername("default").build()
        val settings = mapOf("database" to DATABASE)
        fun open(resources: Map<String, Any>, options: Map<String, String> = settings): QueryDatasetSources {
            val config = QueryDatasetConfig.of("clickhouse", options, StoreResources.of(resources))
            return provider.open(config) as QueryDatasetSources
        }
        open(mapOf("client" to client, "manifest" to CatalogFixtures.MANIFEST))
            .sourceOf(over("crm_member_state_flat"))!!.columns.assert().isEqualTo(mapOf("phone" to PHONE))
        open(mapOf("client" to client)).sourceOf(over("crm_member_state_flat")).assert().isNull()
        // Bindings over a client of their own tell them too.
        val own = open(mapOf("manifest" to CatalogFixtures.MANIFEST), settings + ("endpoint" to "http://127.0.0.1:1"))
        own.sourceOf(over("crm_member_state_flat"))!!.columns.keys.assert().containsExactly("phone")
        (own as AutoCloseable).close()
    }

    private companion object {
        const val DATABASE = "crm_db"
        val PHONE = Masked.of("state.phone", DISPLAY, QueryMask.Keep.of(prefix = 3, suffix = 4))
    }
}
