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

package me.ahoo.wow.http

import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.http.query.HttpQueries
import me.ahoo.wow.http.query.QueryStreams
import me.ahoo.wow.manifest.EventManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.QueryRoutes
import me.ahoo.wow.manifest.copyWith
import me.ahoo.wow.query.DefaultQueryGateway
import me.ahoo.wow.query.QueryBackendFactory
import me.ahoo.wow.query.QueryBackendStorages
import me.ahoo.wow.query.QueryEntryPolicy
import me.ahoo.wow.query.QueryGateway
import me.ahoo.wow.query.QueryOwnership
import me.ahoo.wow.query.QueryPolicy
import me.ahoo.wow.query.QueryTenantPolicy
import me.ahoo.wow.query.ScopeQueryPolicy
import me.ahoo.wow.query.events.EventQueryGateways
import me.ahoo.wow.query.events.EventVisibility
import me.ahoo.wow.query.mask.PayloadMasks
import me.ahoo.wow.query.memory.InMemoryEventQueryStorage
import me.ahoo.wow.query.memory.InMemoryQueryStorage
import me.ahoo.wow.query.schema.EventsQuerySchemas
import me.ahoo.wow.query.schema.ManifestQuerySchemaSource
import me.ahoo.wow.query.schema.QueryModel
import me.ahoo.wow.query.schema.QueryModelCompiler
import me.ahoo.wow.query.schema.QuerySchemaCatalog
import me.ahoo.wow.query.state.CommittedState
import me.ahoo.wow.query.state.SnapshotPolicyGateway
import me.ahoo.wow.runtime.admission.TenantPolicy
import me.ahoo.wow.runtime.snapshot.SnapshotStatus
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import org.springframework.http.MediaType

val cartType = AggregateType(CONTEXT, "cart")

/** [cartManifest] with the cart's query models (decision D128) and its state schema. */
fun queryManifest(): Manifest {
    val manifest = cartManifest()
    return manifest.copyWith(
        contexts = manifest.contexts.map { context ->
            context.copyWith(
                aggregates = context.aggregates.map {
                    it.copyWith(
                        queries = QueryRoutes.queries(context.name, it.name, snapshots = true),
                        events = listOf(
                            EventManifest("CartOpened", schema = Manifest.schemaRef("CartOpened")),
                            EventManifest("ItemAdded", schema = Manifest.schemaRef("ItemAdded")),
                        ),
                    )
                },
            )
        },
        defs = mapOf(
            "Cart" to mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "items" to mapOf("type" to "integer"),
                    "note" to mapOf("type" to "string"),
                ),
            ),
            "CartOpened" to mapOf("type" to "object", "properties" to mapOf("note" to mapOf("type" to "string"))),
            "ItemAdded" to mapOf(
                "type" to "object",
                "properties" to mapOf("sku" to mapOf("type" to "string"), "quantity" to mapOf("type" to "integer")),
            ),
        ),
    )
}

/**
 * The events query gateway of the cart over [log] (decision D235), as the host builds one over a log in memory:
 * the Manifest's events schema, the scope policy, [visibility] and [masks].
 */
@Suppress("LongParameterList")
fun cartEvents(
    log: EventLog,
    entryPolicy: QueryEntryPolicy = QueryEntryPolicy.DEFAULT,
    tenants: QueryTenantPolicy = QueryTenantPolicy.CALLERS,
    manifest: Manifest = queryManifest(),
    visibility: EventVisibility = EventVisibility.ALL,
    masks: PayloadMasks = PayloadMasks.NONE,
    storage: QueryBackendFactory<*> = InMemoryEventQueryStorage.of(log),
): QueryGateway {
    val schemas = EventsQuerySchemas(manifest)
    val catalog = QuerySchemaCatalog(
        QueryBackendStorages(events = { type -> storage.create(type) }),
        QueryModelCompiler.of(events = schemas),
    )
    return EventQueryGateways.create(
        cartType,
        storage.create(cartType).backend,
        catalog.provider(cartType, QueryModel.EVENTS),
        policies = listOf(ScopeQueryPolicy(tenants, ownerships = QueryOwnership.of(manifest))),
        entryPolicy = entryPolicy,
        visibility = visibility,
        tenants = tenants,
        masks = masks,
    )
}

/** The snapshot query gateway of the cart over [storage], as the host builds one: Manifest schema, scope policy. */
fun cartSnapshots(
    storage: QueryBackendFactory<*>,
    entryPolicy: QueryEntryPolicy = QueryEntryPolicy.DEFAULT,
    tenants: QueryTenantPolicy = QueryTenantPolicy.CALLERS,
    policies: List<QueryPolicy> = emptyList(),
    manifest: Manifest = queryManifest(),
): QueryGateway {
    val catalog = QuerySchemaCatalog(
        QueryBackendStorages(storage),
        QueryModelCompiler.of(listOf(ManifestQuerySchemaSource(manifest))),
    )
    return DefaultQueryGateway(
        cartType,
        storage.create(cartType).backend,
        catalog.provider(cartType, QueryModel.SNAPSHOT),
        policies = listOf(ScopeQueryPolicy(tenants)) + policies,
        entryPolicy = entryPolicy,
    )
}

/**
 * An [HttpNode] whose cart is queried: its snapshots from [storage] (or the gateway [snapshots] makes of it), its
 * events from the node's log.
 */
class QueryNode(
    val storage: InMemoryQueryStorage = InMemoryQueryStorage(),
    entryPolicy: QueryEntryPolicy = QueryEntryPolicy.DEFAULT,
    defaultListSize: Int = HttpQueries.DEFAULT_LIST_SIZE,
    tenants: TenantPolicy = TenantPolicy.CALLERS,
    snapshots: (QueryGateway) -> QueryGateway? = { it },
    snapshotStatus: SnapshotStatus = SnapshotStatus.NONE,
    /** The application's query policies of the cart's snapshots, beside the scope policy. */
    policies: List<QueryPolicy> = emptyList(),
    /** The node's Manifest: the cart's query models and state schema, its sensitive fields among them. */
    manifest: Manifest = queryManifest(),
    /** How lists stream as server-sent events. */
    val streams: QueryStreams = QueryStreams(),
) {
    private val gateway = snapshots(cartSnapshots(storage, entryPolicy, policies = policies, manifest = manifest))

    /** As the host builds one (review R3.3-3): the policies of a cart whose snapshots are not queried. */
    private val policyGateway = if (gateway == null && policies.isNotEmpty()) {
        val catalog = QuerySchemaCatalog(compiler = QueryModelCompiler.of(listOf(ManifestQuerySchemaSource(manifest))))
        SnapshotPolicyGateway(
            cartType,
            catalog.policyProvider(cartType),
            listOf(ScopeQueryPolicy(QueryTenantPolicy.CALLERS)) + policies,
            entryPolicy,
        )
    } else {
        null
    }
    val http: HttpNode = HttpNode(manifest = manifest, tenants = tenants) { log ->
        HttpQueries(
            snapshots = listOfNotNull(gateway),
            events = listOf(
                cartEvents(
                    log,
                    entryPolicy = entryPolicy,
                    manifest = manifest,
                    // As the host wires it: the latest committed state, visible through the snapshot queries
                    // (reviews A3-11, R2.3-6).
                    visibility = EventVisibility.of(
                        { gateway ?: policyGateway },
                        hides = policies.isNotEmpty()
                    ) { ref ->
                        http.reads.committed(ref)?.let {
                            CommittedState(it.ref, it.version, it.scope, it.deleted, it.tags, it.state)
                        }
                    },
                    masks = PayloadMasks(manifest),
                ),
            ),
            defaultListSize = defaultListSize,
            snapshotStatus = snapshotStatus,
            policies = listOfNotNull(policyGateway),
            streams = streams,
        )
    }

    /** Stores the snapshot of cart [id] at [version] owned by [owner], with [items] items. */
    fun save(
        id: String,
        owner: String? = "alice",
        items: Int = 1,
        version: Int = 1,
        deleted: Boolean = false,
        tenant: String = TENANT,
        space: String? = null,
    ) {
        storage.save(
            SnapshotRecord(
                ref = cartType.ref(tenant, id),
                version = version,
                schemaFingerprint = "fp",
                state = """{"items":$items,"note":"cart $id"}""",
                scope = Scope(owner, space),
                deleted = deleted,
                firstEventTime = 1000L,
                lastEventTime = 1000L + version,
            ),
        )
    }

    /** `POST /shop/cart/{model}/query` with [body] as [user]. */
    fun query(
        body: String?,
        user: String? = "alice",
        model: String = QueryRoutes.SNAPSHOT,
        headers: Map<String, String> = emptyMap(),
    ): Answer = http.post("/shop/cart/$model/query", body, user, headers)

    /** `GET /shop/cart/{model}/query`, the descriptor. */
    fun describe(
        model: String = QueryRoutes.SNAPSHOT,
        user: String? = "alice",
        headers: Map<String, String> = emptyMap(),
    ): Answer = http.get("/shop/cart/$model/query", user, headers)

    /** A streamed `list` of [body]: its events, each `event` to `data`. */
    fun stream(
        body: String,
        model: String = QueryRoutes.SNAPSHOT,
        user: String = "alice",
        lastEventId: String? = null,
    ): Streamed {
        val result = http.client.post().uri("/shop/cart/$model/query")
            .header("X-User", user)
            .header("Content-Type", "application/json")
            .headers { headers -> lastEventId?.let { headers.set("Last-Event-ID", it) } }
            .accept(MediaType.TEXT_EVENT_STREAM)
            .bodyValue(body)
            .exchange()
            .expectBody(String::class.java)
            .returnResult()
        val body = result.responseBody.orEmpty()
        val contentType = result.responseHeaders.contentType?.toString()
        if (contentType?.startsWith("text/event-stream") != true) {
            return Streamed(result.status.value(), contentType, emptyList(), body)
        }
        val events = body.split("\n\n").filter { it.isNotBlank() }.map { block ->
            val lines = block.lines()
            val event = lines.firstOrNull { it.startsWith("event:") }?.removePrefix("event:")?.trim()
            val id = lines.firstOrNull { it.startsWith("id:") }?.removePrefix("id:")?.trim()
            val data = lines.filter { it.startsWith("data:") }.joinToString("\n") { it.removePrefix("data:") }
            StreamedEvent(event, id, json.readTree(data))
        }
        return Streamed(result.status.value(), contentType, events, body)
    }
}

class StreamedEvent(val event: String?, val id: String?, val data: tools.jackson.databind.JsonNode)

class Streamed(val status: Int, val contentType: String?, val events: List<StreamedEvent>, val body: String) {
    /** The `row` events. */
    val rows: List<StreamedEvent>
        get() = events.filter { it.event == "row" }

    /** The data of the `end` event, the last one, or null when the stream ended without one. */
    val end: tools.jackson.databind.JsonNode?
        get() = events.lastOrNull()?.takeIf { it.event == "end" }?.data
}
