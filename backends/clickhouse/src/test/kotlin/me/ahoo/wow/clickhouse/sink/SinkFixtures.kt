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

package me.ahoo.wow.clickhouse.sink

import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Operator
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.StateManifest
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.state.StateEventPayload
import me.ahoo.wow.spi.state.StateEventRecord
import me.ahoo.wow.spi.state.StateView

/** An order aggregate, its state schema and its state events, for the Sink's tests. */
object SinkFixtures {
    val ORDER = AggregateType("shop", "order")

    /** The state schema of an order, as `manifest-tools` writes it into `$defs`. */
    val ORDER_STATE_SCHEMA: Map<String, Any?> = mapOf(
        "type" to "object",
        "properties" to linkedMapOf(
            "status" to mapOf("type" to "string", "enum" to listOf("CREATED", "PAID")),
            "note" to mapOf("type" to listOf("string", "null")),
            "quantity" to mapOf("type" to "integer"),
            "total" to mapOf("type" to "number", "x-wow-decimal" to mapOf("scale" to 2)),
            "price" to mapOf("type" to "number", "x-wow-money" to mapOf("currency" to "JPY")),
            "discount" to mapOf("type" to listOf("number", "null"), "x-wow-money" to mapOf("currencyField" to "cur")),
            "rate" to mapOf("type" to "number"),
            "paid" to mapOf("type" to "boolean"),
            "version" to mapOf("type" to "integer"),
            "items" to mapOf("type" to "array", "items" to mapOf("type" to "string")),
            "address" to mapOf("\$ref" to "#/\$defs/Address"),
        ),
    )

    val MANIFEST = Manifest(
        contexts = listOf(
            ContextManifest(
                name = ORDER.context,
                aggregates = listOf(AggregateManifest(ORDER.name, state = StateManifest("#/\$defs/OrderState"))),
            ),
        ),
        defs = mapOf("OrderState" to ORDER_STATE_SCHEMA),
    )

    /** The state of an order at [version], with decimals [ClickHouse's JSON functions][FlatColumns] cannot read exactly. */
    fun stateOf(version: Int, total: String = "12.30"): String =
        """{"status":"CREATED","note":null,"quantity":$version,"total":$total,"price":1500,""" +
            """"discount":null,"rate":0.5,"paid":false,"version":$version,"items":["a"],""" +
            """"address":{"city":"Hangzhou","total":1.10},"big":123456789012345678901234567.123456789}"""

    fun recordOf(
        id: String = "order-1",
        version: Int = 1,
        state: String? = stateOf(version),
        tenantId: String = "tenant-1",
        type: AggregateType = ORDER,
        operator: Operator? = Operator("alice", "tenant-0", crossScope = true),
    ): StateEventRecord = StateEventRecord(
        ref = type.ref(tenantId, id),
        version = version,
        commandId = "command-$version",
        requestId = "request-$version",
        commandType = "shop.order.PlaceOrder",
        commitTime = 1_760_000_000_123L + version,
        events = listOf(
            StateEventPayload(
                "order_placed",
                1,
                WowJson.mapper.readTree("""{"total":12.30,"lines":[{"price":0.10}]}""")
            ),
            StateEventPayload("order_noted", 2, WowJson.mapper.readTree("""{"note":"fast"}""")),
        ),
        state = state?.let { StateView(version, "fingerprint-1", WowJson.mapper.readTree(it)) },
        scope = Scope("owner-1", null),
        deleted = false,
        tags = mapOf("color" to listOf("red", "blue")),
        causation = Causation("correlation-1", "upstream@1"),
        headers = mapOf("app" to "web"),
        operator = operator,
    )
}

/** The statements of a server that is not there: records what the Sink runs, answers from [tables]. */
internal class RecordingStatements : ClickHouseStatements {
    val executed = mutableListOf<String>()
    val inserts = mutableListOf<Triple<String, String, String>>()

    /** The inserts into `wow_catalog`, apart from the records' ([inserts]). */
    val catalog = mutableListOf<Triple<String, String, String>>()
    val tables = mutableMapOf<String, List<ClickHouseColumn>>()
    var failInsert: Throwable? = null
    var failCatalog: Throwable? = null

    /** Awaited by each insert before it is recorded, when set: holds the inserts while writes queue. */
    var gate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    /** Completed by the first insert that waits on [gate]. */
    val waiting = kotlinx.coroutines.CompletableDeferred<Unit>()

    override suspend fun execute(sql: String) {
        executed += sql
    }

    override suspend fun insert(table: String, body: ByteArray, token: String) {
        gate?.let {
            waiting.complete(Unit)
            it.await()
        }
        if (table.endsWith("`${ClickHouseCatalog.TABLE}`")) {
            failCatalog?.let { throw it }
            catalog += Triple(table, body.toString(Charsets.UTF_8), token)
            return
        }
        failInsert?.let { throw it }
        inserts += Triple(table, body.toString(Charsets.UTF_8), token)
    }

    override suspend fun uuid(database: String, name: String): String? =
        if ("$database.$name" in tables) uuidOf(name) else null

    override suspend fun exists(database: String, name: String): Boolean = "$database.$name" in tables

    override suspend fun columns(database: String, name: String): List<ClickHouseColumn> =
        tables["$database.$name"].orEmpty()

    /** The uuid [uuid] answers for the table [name]. */
    fun uuidOf(name: String): String = java.util.UUID.nameUUIDFromBytes(name.toByteArray()).toString()

    /** Declares every object of [schema] as the Sink expects it. */
    fun create(schema: ClickHouseSinkSchema) {
        schema.expected.forEach { tables["${schema.database}.${it.name}"] = it.columns ?: emptyList() }
    }
}
