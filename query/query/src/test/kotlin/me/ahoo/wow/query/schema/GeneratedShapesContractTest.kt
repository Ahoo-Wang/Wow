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

package me.ahoo.wow.query.schema

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.query.IdFilter
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.SingleQuery
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.EventManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.StateManifest
import me.ahoo.wow.query.DefaultQueryGateway
import me.ahoo.wow.query.QueryCaller
import me.ahoo.wow.query.QueryEntry
import me.ahoo.wow.query.admission.QueryBudget
import me.ahoo.wow.query.events.EventQueryGateways
import me.ahoo.wow.query.mask.PayloadMasks
import me.ahoo.wow.query.memory.InMemoryEventQueryStorage
import me.ahoo.wow.query.memory.InMemoryQueryStorage
import me.ahoo.wow.spi.access.AccessValues
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode

/**
 * What `manifest-tools` generates, the query engine reads (#6555): every container of every kind of value
 * (`QueryShapesTest` of `manifest-tools`, whose `$defs` and sample states are the snapshots
 * `query-shapes.defs.json` and `query-shapes.samples.json`, copied here as [ManifestFixtures.SHAPE_DEFS] and
 * [ManifestFixtures.SHAPE_SAMPLES]) compiles to a query schema that describes itself, and its sample reads by id and
 * in a list, as a snapshot's state and as an event's body. A shape the reader could not take would fail here, not at
 * a service's first query.
 */
class GeneratedShapesContractTest {
    private val type = ManifestFixtures.ORDER
    private val caller = QueryCaller(Caller(tenantId = "tenant"), entry = QueryEntry.IN_PROCESS)

    private fun manifest(state: String, defs: Map<String, Any?>): Manifest = Manifest(
        contexts = listOf(
            ContextManifest(
                name = type.context,
                aggregates = listOf(
                    AggregateManifest(
                        name = type.name,
                        state = StateManifest(Manifest.schemaRef(state)),
                        events = listOf(EventManifest(name = "Changed", schema = Manifest.schemaRef(state))),
                    ),
                ),
            ),
        ),
        defs = defs,
    )

    @Test
    fun `the manifest-tools shapes should cover every container`() {
        ManifestFixtures.SHAPE_DEFS.keys.map { it.substringAfterLast('$') }.assert()
            .containsExactlyInAnyOrder("InMap", "InList", "InNestedMap", "InMapOfList", "InListOfMap")
        ManifestFixtures.SHAPE_SAMPLES.keys.assert().isEqualTo(ManifestFixtures.SHAPE_DEFS.keys)
    }

    @Test
    fun `every generated shape should compile and read as a snapshot's state, by id and in a list`() {
        runTest {
            ManifestFixtures.SHAPE_DEFS.forEach { (state, defs) ->
                val sample = WowJson.mapper.writeValueAsString(ManifestFixtures.SHAPE_SAMPLES.getValue(state))
                val schema = ManifestFixtures.schemaOf(ManifestQuerySchemaSource(manifest(state, defs)))
                schema.describe(QueryBudget.HTTP_DEFAULT, defaultListSize = null)
                val storage = InMemoryQueryStorage()
                storage.save(
                    SnapshotRecord(
                        ref = type.ref("tenant", "s1"),
                        version = 1,
                        schemaFingerprint = "test",
                        state = sample,
                        scope = Scope.NONE,
                        firstEventTime = 1,
                        lastEventTime = 1,
                    ),
                )
                val gateway = DefaultQueryGateway(
                    type,
                    storage.binding(type).backend,
                    FixedQueryModelSchemaProvider(schema),
                )
                val byId = checkNotNull(gateway.single(SingleQuery(IdFilter("s1")), caller)) { state }
                val listed = gateway.list(ListQuery(MatchAllFilter, limit = 1), caller).toList().single()
                listOf(byId, listed).forEach { record ->
                    val read = record.path("state")
                    read.propertyNames().toList().assert().describedAs(state)
                        .containsExactlyInAnyOrderElementsOf(SAMPLE_PROPERTIES)
                    // The sensitive token is masked under every container; everything else is as stored.
                    read.toString().assert().describedAs(state).doesNotContain(TOKEN)
                    read.path("plain").assert().describedAs(state)
                        .isEqualTo(WowJson.mapper.readTree(sample).path("plain"))
                }
            }
        }
    }

    @Test
    fun `every generated shape should compile and read as an event's body`() {
        runTest {
            ManifestFixtures.SHAPE_DEFS.forEach { (state, defs) ->
                val body = WowJson.mapper.writeValueAsString(ManifestFixtures.SHAPE_SAMPLES.getValue(state))
                val stream = EventStreamRecord(
                    ref = type.ref("tenant", "s1"),
                    version = 1,
                    commandId = "c1",
                    requestId = "r1",
                    commandType = "shop.order.Change",
                    commitTime = 1,
                    events = listOf(EventRecord("shop.order.Changed", 1, body)),
                    scope = Scope.NONE,
                    access = AccessValues.ofStream(emptyMap(), Scope.NONE),
                )
                val binding = InMemoryEventQueryStorage({ listOf(stream) }).binding(type)
                val schema = QueryModelCompiler.of(events = EventsQuerySchemas(manifest(state, defs)))
                    .compile(QuerySchemaContext(type, QueryModel.EVENTS), binding.storage)
                schema.schema().describe(QueryBudget.HTTP_DEFAULT, defaultListSize = null)
                val read = EventQueryGateways.create(type, binding.backend, schema)
                    .list(ListQuery(MatchAllFilter, limit = 1), caller).toList().single()
                read.path("events").path(0).path("body").propertyNames().toList().assert().describedAs(state)
                    .containsExactlyInAnyOrderElementsOf(SAMPLE_PROPERTIES)
            }
        }
    }

    @Test
    fun `every generated shape should mask as an event's payload and as a state`() {
        ManifestFixtures.SHAPE_DEFS.forEach { (state, defs) ->
            val masks = PayloadMasks(manifest(state, defs))
            val sample = WowJson.mapper.valueToTree<JsonNode>(ManifestFixtures.SHAPE_SAMPLES.getValue(state))
            listOf(
                masks.event(type, "shop.order.Changed", 1, sample.deepCopy()),
                masks.state(type, sample.deepCopy()),
            ).forEach { masked ->
                masked.toString().assert().describedAs(state).doesNotContain(TOKEN)
                masked.path("plain").assert().describedAs(state).isEqualTo(sample.path("plain"))
            }
        }
    }

    private companion object {
        /** The token of every sample's sensitive values. */
        const val TOKEN = "s3cr3t"

        /** The properties of every sample state: one per kind of value. */
        val SAMPLE_PROPERTIES = listOf(
            "plain",
            "tagged",
            "shape",
            "switch",
            "sku",
            "nullable",
            "secret",
            "timed",
            "holder",
        )
    }
}
