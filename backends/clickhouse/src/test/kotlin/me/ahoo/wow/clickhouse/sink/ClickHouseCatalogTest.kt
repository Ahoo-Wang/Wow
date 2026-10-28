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

import kotlinx.coroutines.test.runTest
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.clickhouse.sink.SinkFixtures.recordOf
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.EventManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.Ownership
import me.ahoo.wow.manifest.ServiceInfo
import me.ahoo.wow.manifest.StateManifest
import me.ahoo.wow.spi.Ddl
import me.ahoo.wow.spi.json.WowJson
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds

class ClickHouseCatalogTest {
    private val statements = RecordingStatements()

    private fun sinks(options: ClickHouseSinkOptions = ClickHouseSinkOptions()) =
        ClickHouseSinks(statements, CatalogFixtures.MANIFEST, options)

    @Test
    fun `the entries should say whose each table is, and what in it is sensitive or refers to an aggregate`() {
        val entries = sinks().catalogOf(CatalogFixtures.MEMBER).associateBy { it.role }
        entries.keys.assert().containsExactly("state_events", "state", "events", "state_flat")
        val state = entries.getValue("state")
        state.table.assert().isEqualTo("crm_member_state")
        state.service.assert().isEqualTo("crm-service")
        state.context.assert().isEqualTo("crm")
        state.aggregate.assert().isEqualTo("member")
        state.ownership.assert().isEqualTo("OWNER")
        state.tenantColumn.assert().isEqualTo("tenant_id")
        state.ownerColumn.assert().isEqualTo("owner_id")
        state.spaceColumn.assert().isEqualTo("space_id")
        state.sensitive.assert().containsExactlyInAnyOrder(
            CatalogSensitive("state.phone", "DISPLAY", """{"keepPrefix":3,"keepSuffix":4}"""),
            // The value type's keyword and the property's: the highest level is kept.
            CatalogSensitive("state.address.street", "CONFIDENTIAL"),
            CatalogSensitive("state.cards.{key}", "CONFIDENTIAL"),
            CatalogSensitive("state.notes", "DISPLAY"),
        )
        state.references.assert().containsExactlyInAnyOrder(
            CatalogReference("state.referrerId", "crm", "member"),
            CatalogReference("state.storeId", "shop", "store"),
        )
        state.eventRevisions.assert().isEqualTo(mapOf("member_registered" to 2))
        state.columnsHash.assert().isEqualTo(ClickHouseCatalog.columnsHash(ClickHouseSinkSchema.BASE_COLUMNS))

        val events = entries.getValue("events")
        events.sensitive.assert().containsExactly(
            CatalogSensitive("payload.phone", "DISPLAY", "", "member_registered", 2)
        )
        events.columnsHash.assert().isEqualTo(ClickHouseCatalog.columnsHash(ClickHouseSinkSchema.EVENT_COLUMNS))
        entries.getValue("state_events").sensitive.map { it.path }.assert()
            .contains("state.phone", "events.payload.phone")

        // Only the top-level scalars are flat columns, by the column's name.
        val flat = entries.getValue("state_flat")
        flat.sensitive.assert().containsExactly(
            CatalogSensitive("phone", "DISPLAY", """{"keepPrefix":3,"keepSuffix":4}""")
        )
        flat.references.assert().containsExactlyInAnyOrder(
            CatalogReference("referrerId", "crm", "member"),
            CatalogReference("storeId", "shop", "store"),
        )

        // A fingerprint per content: the same entry the same, another table another.
        state.fingerprint.assert().isEqualTo(sinks().catalogOf(CatalogFixtures.MEMBER)[1].fingerprint)
            .isNotEqualTo(flat.fingerprint).hasSize(64)
    }

    @Test
    fun `a tenant's resource should have no owner column, and without the history only two tables`() {
        val options = ClickHouseSinkOptions(
            aggregates = mapOf(CatalogFixtures.STORE to AggregateSinkOptions(history = false))
        )
        val entries = sinks(options).catalogOf(CatalogFixtures.STORE)
        entries.map { it.role }.assert().containsExactly("state", "state_flat")
        entries.forEach {
            it.ownership.assert().isEqualTo("TENANT")
            it.ownerColumn.assert().isEmpty()
            it.sensitive.assert().isEmpty()
        }
    }

    @Test
    fun `the sink should append its tables' rows to wow_catalog once they are created`(): Unit = runTest {
        val sink = sinks().sinkOf("crm", "member")
        statements.create(sink.schema)
        sink.write(listOf(recordOf(type = CatalogFixtures.MEMBER)))
        sink.write(listOf(recordOf(type = CatalogFixtures.MEMBER, version = 2)))
        statements.executed.first().assert().isEqualTo(ClickHouseCatalog.ddl("default"))
        val (table, body, token) = statements.catalog.single()
        table.assert().isEqualTo("`default`.`wow_catalog`")
        token.assert().startsWith("catalog:")
        val rows = body.trimEnd().lines().map { WowJson.mapper.readTree(it) }
        rows.map { it["table"].asString() }.assert()
            .containsExactly(
                "crm_member_state_events",
                "crm_member_state",
                "crm_member_events",
                "crm_member_state_flat"
            )
        val row = rows[1]
        row["format"].asInt().assert().isEqualTo(ClickHouseCatalog.FORMAT)
        row["database"].asString().assert().isEqualTo("default")
        row["table_uuid"].asString().assert().isEqualTo(statements.uuidOf("crm_member_state"))
        row["sensitive"].values().map { it["path"].asString() }.assert().contains("state.phone")
        row["references"].values().first()["aggregate"].asString().assert().isEqualTo("member")
        row["event_revisions"]["member_registered"].asInt().assert().isEqualTo(2)
        row.has("writer").assert().isFalse()
        row.has("written_at").assert().isFalse()
        statements.inserts.size.assert().isEqualTo(2)
    }

    @Test
    fun `each check should append the rows again only when they changed`(): Unit = runTest {
        val schema = sinks().schemaOf(CatalogFixtures.MEMBER)
        var now = 0L
        val sink = ClickHouseSink(
            schema,
            statements,
            Ddl.VALIDATE,
            catalog = sinks().catalogOf(CatalogFixtures.MEMBER),
            recheckEvery = 1.milliseconds,
            clock = {
                now += 10
                now
            },
        )
        statements.create(schema)
        statements.comments["default.crm_member_state_flat"] = FlatLineage(listOf(schema.flatFingerprint)).comment
        sink.write(listOf(recordOf(type = CatalogFixtures.MEMBER)))
        sink.write(listOf(recordOf(type = CatalogFixtures.MEMBER, version = 2)))
        statements.catalog.size.assert().isEqualTo(1)
        // The flat view replaced by another writer: another uuid, so another row.
        statements.uuids["default.crm_member_state_flat"] = "00000000-0000-0000-0000-000000000001"
        sink.write(listOf(recordOf(type = CatalogFixtures.MEMBER, version = 3)))
        statements.catalog.size.assert().isEqualTo(2)
    }

    @Test
    fun `with ddl none the rows should be appended for the tables whose uuid is readable`(): Unit = runTest {
        val sink = sinks(ClickHouseSinkOptions(ddl = Ddl.NONE)).sinkOf("crm", "member")
        statements.tables["default.crm_member_state"] = ClickHouseSinkSchema.BASE_COLUMNS
        sink.write(listOf(recordOf(type = CatalogFixtures.MEMBER)))
        statements.executed.assert().isEmpty()
        statements.catalog.single().second.trimEnd().lines().size.assert().isEqualTo(1)
    }

    @Test
    fun `a failed catalog write should never fail the sink's writes`(): Unit = runTest {
        val sink = sinks().sinkOf("crm", "member")
        statements.create(sink.schema)
        statements.failCatalog = IllegalStateException("no INSERT on wow_catalog")
        sink.write(listOf(recordOf(type = CatalogFixtures.MEMBER)))
        statements.inserts.size.assert().isEqualTo(1)
        statements.catalog.assert().isEmpty()
    }

    @Test
    fun `no table readable should append nothing`(): Unit = runTest {
        sinks(ClickHouseSinkOptions(ddl = Ddl.NONE)).sinkOf("crm", "member")
            .write(listOf(recordOf(type = CatalogFixtures.MEMBER)))
        statements.catalog.assert().isEmpty()
        statements.inserts.size.assert().isEqualTo(1)
    }

    @Test
    fun `the catalog table should be a ReplacingMergeTree by written_at, its writer the inserting account`() {
        val ddl = ClickHouseCatalog.ddl("analytics")
        ddl.assert().startsWith("CREATE TABLE IF NOT EXISTS `analytics`.`wow_catalog`")
            .contains("`writer` String MATERIALIZED currentUser()")
            .contains("`table_uuid` UUID")
            .endsWith("ENGINE = ReplacingMergeTree(written_at)\nORDER BY (`database`, `table`, fingerprint)")
        ClickHouseCatalog.columnsHash(listOf(ClickHouseColumn("id", "String"))).assert()
            .isEqualTo(ClickHouseCatalog.sha256("id String"))
    }
}

/** A member aggregate whose schemas mark sensitive values and references, for the catalog's tests. */
object CatalogFixtures {
    val MEMBER = AggregateType("crm", "member")
    val STORE = AggregateType("crm", "store")

    private val SENSITIVE_DISPLAY = mapOf("level" to "display")
    private val SENSITIVE_CONFIDENTIAL = mapOf("level" to "confidential")

    val MANIFEST = Manifest(
        service = ServiceInfo("crm-service"),
        contexts = listOf(
            ContextManifest(
                name = MEMBER.context,
                aggregates = listOf(
                    AggregateManifest(
                        MEMBER.name,
                        state = StateManifest("#/\$defs/MemberState"),
                        events = listOf(
                            EventManifest("member_registered", revision = 2, schema = "#/\$defs/MemberRegistered"),
                        ),
                    ),
                    AggregateManifest(
                        STORE.name,
                        state = StateManifest("#/\$defs/StoreState"),
                        ownership = Ownership.TENANT,
                    ),
                ),
            ),
        ),
        defs = mapOf(
            "MemberState" to mapOf(
                "type" to "object",
                "properties" to linkedMapOf(
                    "phone" to mapOf(
                        "type" to "string",
                        "x-wow-sensitive" to SENSITIVE_DISPLAY,
                        "x-wow-mask" to linkedMapOf("keepPrefix" to 3, "keepSuffix" to 4),
                    ),
                    "referrerId" to mapOf("type" to "string", "x-wow-reference" to mapOf("aggregateName" to "member")),
                    "storeId" to mapOf(
                        "type" to "string",
                        "x-wow-reference" to mapOf("aggregateName" to "store", "contextName" to "shop"),
                    ),
                    "address" to mapOf("\$ref" to "#/\$defs/Address"),
                    "cards" to mapOf(
                        "type" to "object",
                        "additionalProperties" to mapOf("type" to "string", "x-wow-sensitive" to SENSITIVE_CONFIDENTIAL),
                    ),
                    "notes" to mapOf(
                        "type" to "array",
                        "items" to mapOf("anyOf" to listOf(mapOf("type" to "string", "x-wow-sensitive" to SENSITIVE_DISPLAY))),
                    ),
                    "self" to mapOf("\$ref" to "#/\$defs/MemberState"),
                ),
            ),
            "Address" to mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "street" to mapOf(
                        "allOf" to listOf(mapOf("\$ref" to "#/\$defs/Street")),
                        "x-wow-sensitive" to SENSITIVE_DISPLAY,
                    ),
                ),
            ),
            "Street" to mapOf("type" to "string", "x-wow-sensitive" to SENSITIVE_CONFIDENTIAL),
            "MemberRegistered" to mapOf(
                "type" to "object",
                "properties" to mapOf("phone" to mapOf("type" to "string", "x-wow-sensitive" to SENSITIVE_DISPLAY)),
            ),
            "StoreState" to mapOf("type" to "object", "properties" to mapOf("name" to mapOf("type" to "string"))),
        ),
    )
}
