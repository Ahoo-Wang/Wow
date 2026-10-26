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

package me.ahoo.wow.analytics.catalog.server

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import me.ahoo.test.asserts.assert
import me.ahoo.wow.analytics.catalog.api.AnalyticsCatalogContext
import me.ahoo.wow.analytics.catalog.api.source.SourceColumn
import me.ahoo.wow.analytics.catalog.domain.warehouse.CatalogRow
import me.ahoo.wow.analytics.catalog.domain.warehouse.CatalogTrust
import me.ahoo.wow.analytics.catalog.domain.warehouse.WarehouseTable
import me.ahoo.wow.analytics.catalog.testing.MemoryWarehouse
import me.ahoo.wow.analytics.catalog.testing.MemoryWarehouseConfiguration
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.ManifestTree
import me.ahoo.wow.spi.json.WowJson
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The reference actions of a dataset's rows (decisions D238 ⑤, D257) end to end: the catalog relays a row's command to
 * the owning service ([crm], a peer described by its Manifest alone, in user mode), as the caller, whom that service
 * authorizes; the relay offers only the commands the peer's Manifest declares with a flat body.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReferenceActionsServerTest {
    /** What the owning service took: each request's method, path, caller and body. */
    private val taken = CopyOnWriteArrayList<List<String>>()

    private val crm: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange -> answer(exchange) }
        start()
    }

    private val node = startCatalog(
        "wow.analytics.catalog.trusted-writers.wow_sink" to "crm",
        "wow.analytics.catalog.refresh-interval" to "200ms",
        "wow.analytics.catalog.datasets.refresh-interval" to "100ms",
        "wow.clients.contexts.crm.url" to "http://127.0.0.1:${crm.address.port}",
        "wow.clients.contexts.crm.contract" to "manifest",
        "wow.clients.contexts.crm.mode" to "user",
        "wow.clients.mode" to "user",
        "wow.clients.forwarded-headers" to "X-User,X-Role",
        sources = listOf(MemoryWarehouseConfiguration::class.java),
    )
    private val warehouse = node.context.getBean(MemoryWarehouse::class.java)
    private val dataset = AnalyticsCatalogContext.datasetIdOf("shop", "invoices")

    @AfterAll
    fun close() {
        node.close()
        crm.stop(0)
    }

    private var published = false

    /** Publishes the invoices, whose member column refers to crm.member, the aggregate a Wow table is of. */
    @Synchronized
    private fun publish() {
        if (published) return
        published = true
        warehouse.put(MEMBER_STATE)
        warehouse.rows += CatalogRow(
            database = MEMBER_STATE.database,
            table = MEMBER_STATE.name,
            tableUuid = MEMBER_STATE.uuid,
            role = "state",
            service = "crm-service",
            context = "crm",
            aggregate = "member",
            ownership = "OWNER",
            tenantColumn = "tenant_id",
            ownerColumn = "",
            spaceColumn = "",
            sensitive = emptyList(),
            references = emptyList(),
            eventRevisions = emptyMap(),
            fingerprint = "f-1",
            columnsHash = CatalogTrust.columnsHash(MEMBER_STATE.columns),
            writtenAt = "2026-10-19 10:00:00.000",
            writer = "wow_sink",
        )
        val members = AnalyticsCatalogContext.sourceIdOf("warehouse", MemoryWarehouse.DATABASE, MEMBER_STATE.name)
        eventually("the members' Wow table adopted") {
            node.get("/analytics-catalog/source/$members/state", Who.VIEWER).status == OK
        }
        warehouse.put(INVOICES)
        val source = AnalyticsCatalogContext.sourceIdOf("warehouse", MemoryWarehouse.DATABASE, INVOICES.name)
        node.command(source, "draft-source", """{"database":"${MemoryWarehouse.DATABASE}","table":"invoices"}""")
            .assertOk()
        node.command(
            source,
            "revise-source",
            """{"tenantColumn":"tenant","references":[{"path":"member_id","context":"crm","aggregate":"member"}]}""",
        ).assertOk()
        node.command(
            source,
            "review-source-sensitivity",
            """{"fields":[{"path":"tenant"},{"path":"member_id"}]}""",
        ).assertOk()
        node.command(source, "mark-source-reviewed", "{}", Who.APPROVER).assertOk()
        val definition = """{"sources":[{"alias":"i","sourceId":"$source"}],"subject":"i",""" +
            """"columns":[{"name":"member","from":"i.member_id"},{"name":"total","from":"i.total"}]}"""
        node.datasetCommand(
            dataset,
            "draft-dataset",
            """{"context":"shop","name":"invoices","definition":$definition}""",
            Who.EDITOR,
        ).assertOk()
        node.datasetCommand(dataset, "publish-dataset", "{}", Who.EDITOR).assertOk()
    }

    @Test
    fun `a row should offer the declared flat commands of the aggregate its reference field holds`() {
        publish()
        val actions = node.get("/analytics-catalog/datasets/$dataset/reference-actions", MEMBER).assertOk().json
        actions["dataset"].asString().assert().isEqualTo(dataset)
        val field = actions["fields"].values().single()
        field["field"].asString().assert().isEqualTo("member")
        field["context"].asString().assert().isEqualTo("crm")
        field["aggregate"].asString().assert().isEqualTo("member")
        // Not CreateMember, which creates, nor TagMember, whose body is not flat.
        val command = field["commands"].values().single()
        command["name"].asString().assert().isEqualTo("SuspendMember")
        command["inputs"].values().map { it["name"].asString() to it["required"].asBoolean() }
            .assert().containsExactly("reason" to true, "until" to false)
        command["inputs"][1]["options"].values().map { it.asString() }.assert().containsExactly("WEEK", "EVER")
        taken.first { it[1] == "/wow/manifest" }[2].assert().isEqualTo("mary")
        node.get("/analytics-catalog/datasets/shop.nothing/reference-actions", MEMBER).status.assert().isEqualTo(404)
        node.get("/analytics-catalog/datasets/$dataset/reference-actions", Who(emptyMap())).assertProblem(
            401,
            "wow.auth.unauthenticated",
        )
    }

    @Test
    fun `the owning service should authorize the relayed command as the caller, its refusal passing through`() {
        publish()
        val path = "/analytics-catalog/datasets/$dataset/rows/member/m-1/SuspendMember"
        val done = node.post(path, """{"reason":"spam"}""", ADMIN_OF_CRM).assertOk().json
        done["status"].asString().assert().isEqualTo("SUCCEEDED")
        taken.last().assert().containsExactly("POST", "/crm/member/m-1/suspend-member", "cora", """{"reason":"spam"}""")
        node.post(path, """{"reason":"spam"}""", MEMBER).assertProblem(403, "wow.auth.forbidden")
            .json["detail"].asString().assert().isEqualTo("mary may not suspend a member.")
        taken.last()[2].assert().isEqualTo("mary")
    }

    @Test
    fun `the relay should refuse what the dataset and the peer's Manifest do not declare`() {
        publish()
        val sent = taken.count { it[0] == "POST" }
        val rows = "/analytics-catalog/datasets/$dataset/rows"
        node.post("$rows/member/m-1/CreateMember", "{}", ADMIN_OF_CRM).assertProblem(400, "wow.command.unhandled")
        node.post("$rows/member/m-1/TagMember", "{}", ADMIN_OF_CRM).assertProblem(400, "wow.command.unhandled")
        node.post("$rows/member/m-1/Burn", "{}", ADMIN_OF_CRM).assertProblem(400, "wow.command.unhandled")
        node.post("$rows/member/m-1/SuspendMember", "[1]", ADMIN_OF_CRM).assertProblem(400, "wow.command.malformed")
        node.post("$rows/member/m-1/SuspendMember", "{", ADMIN_OF_CRM).assertProblem(400, "wow.command.malformed")
        node.post("$rows/total/m-1/SuspendMember", "{}", ADMIN_OF_CRM).status.assert().isEqualTo(404)
        node.post("/analytics-catalog/datasets/shop.nothing/rows/member/m-1/SuspendMember", "{}", ADMIN_OF_CRM)
            .status.assert().isEqualTo(404)
        taken.count { it[0] == "POST" }.assert().isEqualTo(sent)
    }

    /** The owning service: its Manifest for a named caller, and SuspendMember for a caller with crm.admin. */
    private fun answer(exchange: HttpExchange) {
        val user = exchange.requestHeaders.getFirst("X-User")
        val roles = exchange.requestHeaders.getFirst("X-Role").orEmpty()
        val body = exchange.requestBody.readAllBytes().decodeToString()
        taken += listOf(exchange.requestMethod, exchange.requestURI.path, user.orEmpty(), body)
        when {
            user == null -> reply(exchange, 401, PROBLEM, problem(401, "wow.auth.unauthenticated", "Who?", null))
            exchange.requestURI.path == "/wow/manifest" -> reply(exchange, OK, JSON, ManifestTree.write(MANIFEST))
            "crm.admin" in roles -> reply(exchange, OK, JSON, resultOf("m-1", "SUCCEEDED", null))
            else -> {
                val detail = "$user may not suspend a member."
                val rejected = resultOf("m-1", "REJECTED", mapOf("code" to "wow.auth.forbidden", "message" to detail))
                reply(exchange, 403, PROBLEM, problem(403, "wow.auth.forbidden", detail, rejected))
            }
        }
    }

    private fun reply(exchange: HttpExchange, status: Int, type: String, value: Any) {
        val bytes = WowJson.mapper.writeValueAsBytes(value)
        exchange.responseHeaders.add("Content-Type", type)
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun problem(status: Int, code: String, detail: String, result: Any?) = buildMap {
        put("type", "urn:wow:error:$code")
        put("title", "Refused")
        put("status", status)
        put("code", code)
        put("detail", detail)
        result?.let { put("result", it) }
    }

    /** A command's result as a node writes it (its wire form), [status] with [error]. */
    private fun resultOf(id: String, status: String, error: Map<String, String>?) = buildMap {
        put("format", 1)
        put("commandId", "c-1")
        put("requestId", "r-1")
        put("correlationId", "c-1")
        put(
            "ref",
            mapOf("type" to mapOf("context" to "crm", "name" to "member"), "tenantId" to "(platform)", "id" to id)
        )
        put("hop", mapOf("stage" to "COMMITTED", "context" to "crm"))
        put(
            "function",
            mapOf("kind" to "COMMAND", "context" to "crm", "processor" to "member", "name" to "SuspendMember")
        )
        put("status", status)
        put("version", 1)
        error?.let { put("error", it) }
        put("signalTime", 1)
    }

    private companion object {
        const val OK = 200
        const val JSON = "application/json"
        const val PROBLEM = "application/problem+json"
        val MEMBER: Who = Who.of("mary", "analytics.catalog.viewer")
        val ADMIN_OF_CRM: Who = Who.of("cora", "analytics.catalog.viewer,crm.admin")

        val MEMBER_STATE = WarehouseTable(
            MemoryWarehouse.DATABASE,
            "crm_member_state",
            "uuid-member-state",
            "ReplacingMergeTree",
            listOf(SourceColumn("tenant_id", "String"), SourceColumn("state", "String")),
        )

        val INVOICES = WarehouseTable(
            MemoryWarehouse.DATABASE,
            "invoices",
            "uuid-invoices-ref",
            "MergeTree",
            listOf(
                SourceColumn("tenant", "String"),
                SourceColumn("member_id", "String"),
                SourceColumn("total", "Int64")
            ),
        )

        private const val DEFS = "#/\$defs/"

        val MANIFEST = manifest(
            contexts = listOf(
                context(
                    name = "crm",
                    aggregates = listOf(
                        aggregate(
                            name = "member",
                            state = "${DEFS}crm.MemberState",
                            commands = listOf(
                                command(name = "SuspendMember", schema = "${DEFS}crm.SuspendMember"),
                                command(
                                    name = "CreateMember",
                                    schema = "${DEFS}crm.CreateMember",
                                    creation = "REQUIRED",
                                ),
                                command(name = "TagMember", schema = "${DEFS}crm.TagMember"),
                            ),
                        ),
                    ),
                ),
            ),
            defs = mapOf(
                "crm.MemberState" to mapOf("type" to "object"),
                "crm.SuspendMember" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "reason" to mapOf("type" to "string"),
                        "until" to mapOf("type" to listOf("string", "null"), "enum" to listOf("WEEK", "EVER")),
                    ),
                    "required" to listOf("reason"),
                ),
                "crm.CreateMember" to mapOf("type" to "object", "properties" to emptyMap<String, Any>()),
                "crm.TagMember" to mapOf(
                    "type" to "object",
                    "properties" to mapOf("tags" to mapOf("type" to "array", "items" to mapOf("type" to "string"))),
                ),
            ),
        )
    }
}
