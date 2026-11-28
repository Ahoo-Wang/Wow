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

package me.ahoo.wow.query.events

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.QueryErrorCodes
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.EventManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.StateManifest
import me.ahoo.wow.query.QueryCaller
import me.ahoo.wow.query.QueryEntry
import me.ahoo.wow.query.QueryGateway
import me.ahoo.wow.query.error.QueryError
import me.ahoo.wow.query.json.QueryJson
import me.ahoo.wow.query.mask.PayloadMasks
import me.ahoo.wow.query.memory.InMemoryEventQueryStorage
import me.ahoo.wow.query.schema.EventsQuerySchemas
import me.ahoo.wow.query.schema.QueryModel
import me.ahoo.wow.query.schema.QueryModelCompiler
import me.ahoo.wow.query.schema.QuerySchemaConflictException
import me.ahoo.wow.query.schema.QuerySchemaContext
import me.ahoo.wow.spi.access.AccessValues
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode

/**
 * Two events of one aggregate that protect one body path differently (#7248). The events model masks each event's
 * body by its own event's schema, so the events may mask the path by different rules and the model still loads; a
 * condition or a group on the path is held to the most restrictive protection of the events that declare it.
 */
class EventsMaskRulesPerEventTest {
    private val type = AggregateType("shop", "customer")

    /**
     * `phone` masked by a rule of each event's own (a prefix kept, a suffix kept); `email` marked `DISPLAY` by one and
     * plain in the other; `ssn` marked `CONFIDENTIAL` by one and plain in the other; `code` marked `DISPLAY` by one and
     * `CONFIDENTIAL` by the other.
     */
    private val manifest = manifestOf(
        body(
            "phone" to sensitive("display", mapOf("keepPrefix" to 3)),
            "email" to sensitive("display", null),
            "ssn" to sensitive("confidential", null),
            "code" to sensitive("display", null),
        ),
    )

    /** The aggregate's Manifest, its `Registered` body [registered]. */
    private fun manifestOf(registered: Map<String, Any?>) = Manifest(
        contexts = listOf(
            ContextManifest(
                "shop",
                aggregates = listOf(
                    AggregateManifest(
                        "customer",
                        state = StateManifest(Manifest.schemaRef("Customer")),
                        events = listOf(
                            EventManifest("Registered", schema = Manifest.schemaRef("Registered")),
                            EventManifest("PhoneChanged", schema = Manifest.schemaRef("PhoneChanged")),
                        ),
                    ),
                ),
            ),
        ),
        defs = mapOf(
            "Customer" to mapOf("type" to "object"),
            "Registered" to registered,
            "PhoneChanged" to body(
                "phone" to sensitive("display", mapOf("keepSuffix" to 4)),
                "email" to PLAIN,
                "ssn" to PLAIN,
                "code" to sensitive("confidential", null),
            ),
        ),
    )

    private val stream = EventStreamRecord(
        ref = type.ref(TENANT, "c-1"),
        version = 1,
        commandId = "cmd-1",
        requestId = "req-1",
        commandType = "shop.customer.Register",
        commitTime = 1000L,
        events = listOf(
            EventRecord(
                "shop.customer.Registered",
                1,
                """{"phone":"13800001111","email":"a@x.io","ssn":"111-11","code":"c-1"}""",
            ),
            EventRecord(
                "shop.customer.PhoneChanged",
                1,
                """{"phone":"13900002222","email":"b@x.io","ssn":"222-22","code":"c-2"}""",
            ),
        ),
        scope = Scope("alice", "s-1"),
        access = AccessValues.ofStream(emptyMap(), Scope("alice", "s-1")),
    )

    private val caller = QueryCaller(Caller(tenantId = TENANT), entry = QueryEntry.HTTP)

    private fun gateway(): QueryGateway {
        val binding = InMemoryEventQueryStorage({ listOf(stream) }).binding(type)
        return EventQueryGateways.create(
            type,
            binding.backend,
            QueryModelCompiler.of(events = EventsQuerySchemas(manifest))
                .compile(QuerySchemaContext(type, QueryModel.EVENTS), binding.storage),
            masks = PayloadMasks(manifest),
        )
    }

    private fun bodies(): List<JsonNode> = runBlocking {
        val list = QueryJson.mapper.readValue("""{"filter":{"op":"match-all"},"limit":10}""", ListQuery::class.java)
        gateway().list(list, caller).toList().single()["events"].values().map { it["body"] }
    }

    /** The ids a condition on body path [field] equal to [value] matches, or the code of its violation. */
    private fun filtered(field: String, value: String): Any = runCatching {
        runBlocking {
            val filter = """{"op":"element-match","field":"events",""" +
                """"predicate":{"op":"eq","field":"body.$field","value":"$value"}}"""
            val list = QueryJson.mapper.readValue("""{"filter":$filter,"limit":10}""", ListQuery::class.java)
            gateway().list(list, caller).toList().map { it["aggregateId"].asString() }
        }
    }.getOrElse { (it as QueryError).violations.single().code }

    /** The code of the violation a terms group on body path [field] is refused with, or its rows. */
    private fun grouped(field: String): Any = runCatching {
        runBlocking {
            val query = QueryJson.mapper.readValue(
                """{"filter":{"op":"match-all"},"elements":[{"path":"events"}],""" +
                    """"groupBy":[{"type":"terms","field":"body.$field","alias":"g"}],""" +
                    """"metrics":[{"type":"count","alias":"n"}]}""",
                AggregationQuery::class.java,
            )
            gateway().aggregate(query, caller).toList().toString()
        }
    }.getOrElse { (it as QueryError).violations.single().code }

    @Test
    fun `an events list should mask each event's body by its own event's rules`() {
        val phones = bodies().map { it["phone"].asString() }
        phones.assert().hasSize(2)
        phones.first().assert().startsWith("138").doesNotContain("1111")
        phones.last().assert().endsWith("2222").doesNotContain("139")
    }

    @Test
    fun `a path two events protect differently is held to the most restrictive of them`() {
        val (registered, changed) = bodies()
        // Each event's value is answered as its own event's schema protects it.
        registered["email"].asString().assert().isNotEqualTo("a@x.io")
        changed["email"].asString().assert().isEqualTo("b@x.io")
        registered["ssn"].asString().assert().isNotEqualTo("111-11")
        changed["ssn"].asString().assert().isEqualTo("222-22")
        registered["code"].asString().assert().isNotEqualTo("c-1")
        changed["code"].asString().assert().isNotEqualTo("c-2")

        // A path some event marks DISPLAY compares raw, in every event that declares it, and is never grouped.
        listOf("phone" to "13900002222", "email" to "a@x.io", "email" to "b@x.io").forEach { (field, value) ->
            filtered(field, value).assert().describedAs("$field = $value").isEqualTo(listOf("c-1"))
            grouped(field).assert().describedAs(field).isEqualTo(QueryErrorCodes.PROTECTED_AGGREGATION)
        }
        // A path some event marks CONFIDENTIAL is neither compared nor grouped, in any event.
        listOf("ssn" to "222-22", "code" to "c-1").forEach { (field, value) ->
            filtered(field, value).assert().describedAs(field).isEqualTo(QueryErrorCodes.PROTECTED_COMPARISON)
            grouped(field).assert().describedAs(field).isEqualTo(QueryErrorCodes.PROTECTED_AGGREGATION)
        }
    }

    @Test
    fun `a union within one event's body still takes one rule a path`() {
        val conflicting = manifestOf(
            mapOf(
                "oneOf" to listOf(
                    body("phone" to sensitive("display", mapOf("keepPrefix" to 3))),
                    body("phone" to sensitive("display", mapOf("keepSuffix" to 4))),
                ),
            ),
        )
        assertThrownBy<QuerySchemaConflictException> { EventsQuerySchemas(conflicting).declare(type) }
            .hasMessage("Conflicting query schema declaration: [events.body.maskRule].")
    }

    private companion object {
        const val TENANT = "t-1"
        val PLAIN = mapOf("type" to "string")

        fun body(vararg properties: Pair<String, Any?>) =
            mapOf("type" to "object", "properties" to mapOf(*properties))

        fun sensitive(level: String, mask: Map<String, Any?>?): Map<String, Any?> = buildMap {
            put("type", "string")
            put("x-wow-sensitive", mapOf("level" to level))
            mask?.let { put("x-wow-mask", it) }
        }
    }
}
