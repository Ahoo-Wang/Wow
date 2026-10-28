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

package me.ahoo.wow.elasticsearch.query.schema

import co.elastic.clients.elasticsearch._types.mapping.TypeMapping
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.elasticsearch.events.ElasticsearchEventIndex
import me.ahoo.wow.elasticsearch.query.ElasticsearchIndexMapping
import me.ahoo.wow.elasticsearch.snapshot.ElasticsearchSnapshotIndex
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.EventManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.ManifestTree
import me.ahoo.wow.manifest.Ownership
import me.ahoo.wow.manifest.QueryRoutes
import me.ahoo.wow.manifest.ServiceInfo
import me.ahoo.wow.manifest.StateManifest
import me.ahoo.wow.query.DefaultQueryGateway
import me.ahoo.wow.query.QueryEntry
import me.ahoo.wow.query.backend.QueryBackendStorages
import me.ahoo.wow.query.dataset.DatasetQueryGateways
import me.ahoo.wow.query.dataset.QueryDataset
import me.ahoo.wow.query.dataset.QueryDatasets
import me.ahoo.wow.query.descriptor.QueryModelDescriptor
import me.ahoo.wow.query.events.EventQueryGateways
import me.ahoo.wow.query.json.QueryJson
import me.ahoo.wow.query.memory.InMemoryDatasetStorage
import me.ahoo.wow.query.memory.InMemoryQueryStorage
import me.ahoo.wow.query.schema.DefaultQueryModelSchemaProvider
import me.ahoo.wow.query.schema.EventsQuerySchemas
import me.ahoo.wow.query.schema.LogicalQuerySchema
import me.ahoo.wow.query.schema.ManifestQuerySchemaSource
import me.ahoo.wow.query.schema.QueryModel
import me.ahoo.wow.query.schema.QueryModelCompiler
import me.ahoo.wow.query.schema.QuerySchemaCatalog
import me.ahoo.wow.query.schema.QuerySchemaContext
import me.ahoo.wow.query.schema.QuerySchemaDeclaration
import me.ahoo.wow.query.schema.QuerySchemaSource
import me.ahoo.wow.query.schema.QueryStorageAdapter
import me.ahoo.wow.query.schema.QueryStorageFacts
import me.ahoo.wow.query.schema.QueryValueType
import me.ahoo.wow.query.schema.querySchema
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.io.StringReader
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Holds the descriptors the skill evals show the agent to the descriptor the query route answers for the eval's
 * model: a snapshot or an events model over the index Wow creates, its payload mapped as the eval's service maps it,
 * or a dataset over the in-memory dataset storage. An eval that teaches a descriptor teaches the wire, so it is
 * rendered by the route's writer rather than written by hand, and a change to the wire fails here until the evals
 * follow. Regenerate them with
 * `./gradlew :elasticsearch:test --tests "*SkillEvalDescriptorsTest" -Dwow.snapshot.update=true`.
 */
class SkillEvalDescriptorsTest {
    @ParameterizedTest
    @EnumSource(Scenario::class)
    fun `an eval's descriptor is the one its model's query route answers`(scenario: Scenario): Unit = runBlocking {
        val described = QueryJson.mapper.writerWithDefaultPrettyPrinter().writeValueAsString(scenario.describe())
        for ((prompt, index) in scenario.blocks.map { (path, index) -> SKILLS.resolve(path) to index }) {
            val text = prompt.readText()
            val block = DESCRIPTOR_BLOCK.findAll(text).elementAtOrNull(index)
                ?: error("$prompt shows no JSON descriptor at block $index")
            if (UPDATE) {
                prompt.writeText(text.replaceRange(block.groups[1]!!.range, described))
            } else {
                block.groupValues[1].assert()
                    .describedAs("run with -Dwow.snapshot.update=true to rewrite block $index of $prompt")
                    .isEqualTo(described)
            }
        }
    }

    /** One descriptor an eval shows: how it is rendered, and the prompts' JSON blocks (by index) that show it. */
    enum class Scenario(val blocks: List<Pair<String, Int>>, val describe: suspend () -> Any) {
        /**
         * The orders a customer-service view is defined over. Strings are keywords (no full-text field), and
         * `remark` is stored but not mapped, so it takes no condition, no sort and no search.
         */
        ORDER(
            listOf(
                "b59-view-definition-from-descriptor",
                "b60-view-definition-invented-field",
                "b61-view-definition-widened-capability",
                "b62-view-definition-sensitive-group",
                "b63-view-definition-sensitive-metric",
                "b65-view-definition-technical-labels",
                "b66-view-definition-deprecated",
                "b68-view-definition-drift",
                "b69-view-definition-dashboard",
            ).map { "wow-view-definition/evals/$it/prompt.md" to 0 },
            { Orders.snapshot(drifted = false) },
        ),

        /**
         * The same orders after the service's upgrade (b68's new descriptor): `remark` is gone, the buyer has a
         * `tier` and its `level` is deprecated for it, and `warehouse` is protected.
         */
        ORDER_DRIFTED(
            listOf("wow-view-definition/evals/b68-view-definition-drift/prompt.md" to 1),
            { Orders.snapshot(drifted = true) },
        ),

        /** The event streams of the orders: one record per commit, its events an element the view filters inside. */
        ORDER_EVENTS(
            listOf(
                "b64-view-definition-event-element-scope",
                "b70-view-definition-wow-storybook",
            ).map { "wow-view-definition/evals/$it/prompt.md" to 0 },
            { Orders.events() },
        ),

        /**
         * The same orders' events as their service's Manifest (`GET /wow/manifest`) lists them, beside the
         * descriptor: which event types there are and what each body holds, which the descriptor leaves to it.
         */
        ORDER_EVENTS_MANIFEST(
            listOf(
                "b64-view-definition-event-element-scope",
                "b70-view-definition-wow-storybook",
            ).map { "wow-view-definition/evals/$it/prompt.md" to 1 },
            { Orders.eventsManifest() },
        ),

        /** The customers of a service whose phone number is protected but comparable. */
        CUSTOMER(
            listOf("wow-data-query/evals/b54-data-query-protected/prompt.md" to 0),
            {
                snapshot(
                    AggregateType("crm", "customer"),
                    state(
                        properties = mapOf("name" to string(), "phone" to string() + sensitive("display")),
                        required = listOf("name", "phone"),
                    ),
                    """{ "name": { "type": "keyword" }, "phone": { "type": "keyword" } }""",
                )
            },
        ),

        /** Carts that list a status and an item count, and no customer level. */
        CART(
            listOf("wow-data-query/evals/b53-data-query-invented-field/prompt.md" to 0),
            {
                snapshot(
                    AggregateType("shop", "cart"),
                    state(
                        properties = mapOf("status" to string(), "itemCount" to mapOf("type" to "integer")),
                        required = listOf("status", "itemCount"),
                    ),
                    """{ "status": { "type": "keyword" }, "itemCount": { "type": "integer" } }""",
                )
            },
        ),

        /** Orders whose `oldStatus` is kept for existing callers, its message pointing at `status`. */
        DEPRECATED_STATUS(
            listOf("wow-data-query/evals/b57-data-query-deprecated/prompt.md" to 0),
            {
                snapshot(
                    AggregateType("shop", "order"),
                    state(
                        properties = mapOf("status" to string(), "oldStatus" to nullable("string")),
                        required = listOf("status"),
                    ),
                    """{ "status": { "type": "keyword" }, "oldStatus": { "type": "keyword" } }""",
                    querySchema { field("state.oldStatus") { deprecated("Use state.status.") } },
                )
            },
        ),

        /** Orders whose `items` is an array of strings, on Elasticsearch, which cannot compare a whole array. */
        ITEM_ARRAY(
            listOf("wow-data-query/evals/b58-data-query-rejection/prompt.md" to 0),
            {
                snapshot(
                    AggregateType("shop", "order"),
                    state(properties = mapOf("items" to mapOf("type" to "array", "items" to string()))),
                    """{ "items": { "type": "keyword" } }""",
                )
            },
        ),

        /** `analytics.order_wide`, a table of orders by customer, read as a dataset. */
        ORDER_WIDE(
            listOf("wow-data-query/evals/b120-data-query-dataset-root-fields/prompt.md" to 0),
            { orderWide() },
        ),
    }

    /** The orders of b59–b70, a `sales.order` aggregate owned by its buyer. */
    private object Orders {
        val TYPE = AggregateType("sales", "order")

        private val LEVELS = listOf("REGULAR", "SILVER", "GOLD")

        fun state(drifted: Boolean): Map<String, Any?> = state(
            properties = mapOf(
                "orderNo" to string(),
                "status" to string() + ("enum" to listOf("PENDING_PAYMENT", "PAID", "SHIPPED", "SIGNED", "CANCELLED")),
                "oldStatus" to nullable("string"),
                "channel" to nullable("string") + ("enum" to listOf("APP", "MINI_PROGRAM", "WEB")),
                "warehouse" to nullable("string") + (if (drifted) sensitive("display") else emptyMap()),
                "paidAmount" to nullable("number"),
                "createdAt" to mapOf("type" to "integer", TEMPORAL),
                "paidAt" to nullable("integer") + TEMPORAL,
                "buyer" to mapOf(
                    "type" to listOf("object", "null"),
                    "properties" to mapOf(
                        "nick" to nullable("string"),
                        "level" to nullable("string") + ("enum" to LEVELS),
                        "phone" to nullable("string") + sensitive("display"),
                        "idCard" to nullable("string") + sensitive("confidential"),
                        "annualIncome" to nullable("string") + sensitive("confidential"),
                    ) + (if (drifted) mapOf("tier" to nullable("string") + ("enum" to TIERS)) else emptyMap()),
                ),
                "items" to mapOf(
                    "type" to listOf("array", "null"),
                    "items" to state(
                        properties = mapOf(
                            "sku" to nullable("string"),
                            "title" to nullable("string"),
                            "qty" to nullable("integer"),
                        ),
                    ),
                ),
            ) + (
                if (drifted) {
                    emptyMap()
                } else {
                    mapOf(
                        "remark" to nullable("string") +
                            ("description" to "Free text the buyer wrote; stored unindexed."),
                    )
                }
                ),
            required = listOf("orderNo", "status", "createdAt"),
        )

        /** The mapping of the state, `remark` left out: Elasticsearch keeps it in `_source`, unindexed. */
        fun mapping(drifted: Boolean): String = """
            {
              "orderNo": { "type": "keyword" },
              "status": { "type": "keyword" },
              "oldStatus": { "type": "keyword" },
              "channel": { "type": "keyword" },
              "warehouse": { "type": "keyword" },
              "paidAmount": { "type": "double" },
              "createdAt": { "type": "long" },
              "paidAt": { "type": "long" },
              "buyer": {
                "properties": {
                  "nick": { "type": "keyword" },
                  "level": { "type": "keyword" },
                  ${if (drifted) """"tier": { "type": "keyword" },""" else ""}
                  "phone": { "type": "keyword" },
                  "idCard": { "type": "keyword" },
                  "annualIncome": { "type": "keyword" }
                }
              },
              "items": {
                "type": "nested",
                "properties": {
                  "sku": { "type": "keyword" },
                  "title": { "type": "keyword" },
                  "qty": { "type": "integer" }
                }
              }
            }
        """.trimIndent()

        suspend fun snapshot(drifted: Boolean): QueryModelDescriptor = snapshot(
            TYPE,
            state(drifted),
            mapping(drifted),
            querySchema {
                field("state.oldStatus") { deprecated("Use state.status.") }
                if (drifted) field("state.buyer.level") { deprecated("Use state.buyer.tier.") }
            },
            ownership = Ownership.OWNER,
        )

        private val EVENTS = mapOf(
            "OrderCreated" to state(
                properties = mapOf("totalAmount" to mapOf("type" to "number"), "channel" to string()),
            ),
            "OrderPaid" to state(
                properties = mapOf(
                    "paidAmount" to mapOf("type" to "number"),
                    "paymentMethod" to string(),
                    "payerCard" to string() + sensitive("confidential"),
                ),
            ),
            "OrderCancelled" to state(properties = mapOf("reason" to string())),
        )

        private fun eventsModel(): Manifest = manifest(
            TYPE,
            state(drifted = false),
            Ownership.OWNER,
            events = EVENTS.keys.map { EventManifest(it, schema = Manifest.schemaRef(it)) },
            defs = EVENTS,
        )

        /**
         * The part of the Manifest's JSON about the orders' events, as `ManifestTree` writes it: the context, the
         * aggregate's `events`, and the `$defs` of their bodies.
         */
        @Suppress("UNCHECKED_CAST")
        fun eventsManifest(): Map<String, Any?> {
            val tree = ManifestTree.write(eventsModel())
            val context = (tree.getValue("contexts") as List<Map<String, Any?>>).single()
            val aggregate = (context.getValue("aggregates") as List<Map<String, Any?>>).single()
            return linkedMapOf(
                "contexts" to listOf(
                    linkedMapOf(
                        "name" to context["name"],
                        "aggregates" to listOf(linkedMapOf("name" to aggregate["name"], "events" to aggregate["events"])),
                    ),
                ),
                "\$defs" to (tree.getValue("\$defs") as Map<String, Any?>).filterKeys { it in EVENTS },
            )
        }

        suspend fun events(): QueryModelDescriptor {
            val manifest = eventsModel()
            val schemas = EventsQuerySchemas(manifest)
            val body = """
                "body": { "type": "object", "dynamic": false, "properties": {
                  "totalAmount": { "type": "double" },
                  "channel": { "type": "keyword" },
                  "paidAmount": { "type": "double" },
                  "paymentMethod": { "type": "keyword" },
                  "payerCard": { "type": "keyword" },
                  "reason": { "type": "keyword" }
                } }
            """.trimIndent()
            val index = ElasticsearchEventIndex.MAPPINGS.replace(UNMAPPED_BODY, body)
            check(index != ElasticsearchEventIndex.MAPPINGS) { "The event index no longer maps a body as expected" }
            val provider = DefaultQueryModelSchemaProvider(
                QuerySchemaContext(TYPE, QueryModel.EVENTS),
                listOf(schemas),
                schemas.adapter(TYPE, ElasticsearchIndex(index, QueryModel.EVENTS)),
                profile = schemas.profile(TYPE),
            )
            val backend = checkNotNull(InMemoryQueryStorage().binding(TYPE)).backend
            return EventQueryGateways.create(TYPE, backend, provider).describe(QueryEntry.HTTP, DEFAULT_LIST_SIZE)
        }
    }

    /** The storage facts of an Elasticsearch index of [mapping], as the adapter reads them from a live index. */
    private class ElasticsearchIndex(mapping: String, private val model: QueryModel) : QueryStorageAdapter {
        private val index = ElasticsearchIndexMapping.from(
            "index",
            TypeMapping.of { it.withJson(StringReader(mapping)) }
        )

        override suspend fun facts(logicalSchema: LogicalQuerySchema): QueryStorageFacts =
            ElasticsearchQuerySchemaAdapter.facts(logicalSchema, index, model)
    }

    /** Declarations a host registers in code, beside the Manifest's: what the Manifest cannot say, such as why. */
    private class Declared(private val declaration: QuerySchemaDeclaration) : QuerySchemaSource {
        override val priority: Int = CODE_PRIORITY

        override suspend fun load(context: QuerySchemaContext): List<QuerySchemaDeclaration> = listOf(declaration)
    }

    private companion object {
        const val DEFAULT_LIST_SIZE = 20

        /** The priority of declarations registered in code, above the Manifest's. */
        const val CODE_PRIORITY = 300
        const val STATE = "State"
        const val UNMAPPED_STATE = """"state": { "type": "object", "dynamic": false }"""
        const val UNMAPPED_BODY = """"body": { "type": "object", "dynamic": false }"""
        val TIERS = listOf("T1", "T2", "T3", "T4")
        val TEMPORAL = "x-wow-temporal" to mapOf("unit" to "MILLISECONDS")
        val SKILLS: Path = Path.of("..", "..", "skills")
        val DESCRIPTOR_BLOCK = Regex("```json\\n(\\{.*?\\n})\\n```", RegexOption.DOT_MATCHES_ALL)
        val UPDATE = System.getProperty("wow.snapshot.update").equals("true", ignoreCase = true)

        fun string(): Map<String, Any?> = mapOf("type" to "string")

        fun nullable(type: String): Map<String, Any?> = mapOf("type" to listOf(type, "null"))

        fun sensitive(level: String): Map<String, Any?> = mapOf("x-wow-sensitive" to mapOf("level" to level))

        fun state(properties: Map<String, Any?>, required: List<String> = emptyList()): Map<String, Any?> =
            mapOf("type" to "object", "properties" to properties) +
                (if (required.isEmpty()) emptyMap() else mapOf("required" to required))

        fun manifest(
            type: AggregateType,
            state: Map<String, Any?>,
            ownership: Ownership,
            events: List<EventManifest> = emptyList(),
            defs: Map<String, Any?> = emptyMap(),
        ): Manifest = Manifest(
            service = ServiceInfo("${type.context}-service"),
            contexts = listOf(
                ContextManifest(
                    name = type.context,
                    aggregates = listOf(
                        AggregateManifest(
                            name = type.name,
                            state = StateManifest(Manifest.schemaRef(STATE)),
                            events = events,
                            queries = QueryRoutes.queries(type.context, type.name, snapshots = true),
                            ownership = ownership,
                        ),
                    ),
                ),
            ),
            defs = defs + (STATE to state),
        )

        /** The snapshot descriptor of [type] whose [state] the snapshot index maps as [stateMapping]. */
        suspend fun snapshot(
            type: AggregateType,
            state: Map<String, Any?>,
            stateMapping: String,
            declared: QuerySchemaDeclaration? = null,
            ownership: Ownership = Ownership.TENANT,
        ): QueryModelDescriptor {
            val index = ElasticsearchSnapshotIndex.MAPPINGS.replace(
                UNMAPPED_STATE,
                """"state": { "type": "object", "dynamic": false, "properties": $stateMapping }""",
            )
            check(
                index != ElasticsearchSnapshotIndex.MAPPINGS
            ) { "The snapshot index no longer maps state as expected" }
            val provider = DefaultQueryModelSchemaProvider(
                QuerySchemaContext(type, QueryModel.SNAPSHOT),
                listOfNotNull(ManifestQuerySchemaSource(manifest(type, state, ownership)), declared?.let(::Declared)),
                ElasticsearchIndex(index, QueryModel.SNAPSHOT),
            )
            // The descriptor reads the schema alone; the backend only satisfies the gateway.
            val backend = checkNotNull(InMemoryQueryStorage().binding(type)).backend
            return DefaultQueryGateway(type, backend, provider).describe(QueryEntry.HTTP, DEFAULT_LIST_SIZE)
        }

        /** `analytics.order_wide`: a tenant's orders by customer level and amount, keyed by the order. */
        suspend fun orderWide(): QueryModelDescriptor {
            val dataset = QueryDataset.of(AggregateType("analytics", "order_wide")) {
                tenant("tenant_id")
                identity("order_id")
                storage(InMemoryDatasetStorage.binding("order_wide"))
                listOf("order_id", "tenant_id", "customer_level").forEach { name ->
                    field(name) {
                        types(QueryValueType.STRING)
                        nullable(false)
                    }
                }
                field("amount") {
                    types(QueryValueType.DECIMAL)
                    nullable(false)
                }
            }
            val datasets = QueryDatasets(listOf(dataset))
            val storage = InMemoryDatasetStorage()
            val catalog = QuerySchemaCatalog(
                storages = QueryBackendStorages(datasetBindings = storage, datasets = datasets),
                compiler = QueryModelCompiler.of(datasets = datasets),
                datasets = datasets,
            )
            return checkNotNull(DatasetQueryGateways.of(datasets, catalog, storage)[dataset.name])
                .describe(QueryEntry.HTTP, DEFAULT_LIST_SIZE)
        }
    }
}
