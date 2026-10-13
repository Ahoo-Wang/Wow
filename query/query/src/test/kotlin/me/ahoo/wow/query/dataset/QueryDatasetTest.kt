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

package me.ahoo.wow.query.dataset

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.annotation.SensitivityLevel
import me.ahoo.wow.query.schema.DeclarationValue
import me.ahoo.wow.query.schema.MaskRule
import me.ahoo.wow.query.schema.QueryMask
import me.ahoo.wow.query.schema.QueryModel
import me.ahoo.wow.query.schema.QuerySchemaContext
import me.ahoo.wow.query.schema.QuerySchemaDeclaration
import me.ahoo.wow.query.schema.QuerySchemaUnavailableException
import me.ahoo.wow.query.schema.QueryValueKind
import me.ahoo.wow.query.schema.QueryValueType
import me.ahoo.wow.query.schema.SnapshotQueryModelProfile
import me.ahoo.wow.query.schema.Temporal
import me.ahoo.wow.query.spec.SystemField
import me.ahoo.wow.spi.json.WowJson
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path

class QueryDatasetTest {
    @Test
    fun `builder declares the profile columns, fields and their sensitivity`() {
        val dataset = orders()
        dataset.name.assert().isEqualTo(ORDERS)
        dataset.scope.tenant.assert().isEqualTo(QueryField("tenant_id"))
        dataset.identity.assert().isEqualTo(QueryField("order_id"))
        dataset.declaration.fields.getValue(QueryField("phone")).maskRule
            .assert().isEqualTo(
                DeclarationValue.Set(MaskRule(SensitivityLevel.CONFIDENTIAL, QueryMask.Keep(suffix = 4)))
            )
        dataset.declaration.fields.getValue(QueryField("city")).maskRule.assert().isEqualTo(DeclarationValue.Unset)
    }

    @Test
    fun `a dataset must declare its tenant and every column it names`() {
        assertThrows<IllegalArgumentException> {
            QueryDataset.of(ORDERS) { field("city") { types(QueryValueType.STRING) } }
        }
        assertThrows<IllegalArgumentException> {
            QueryDataset.of(ORDERS) {
                tenant("tenant_id")
                identity("missing")
                field("tenant_id") { types(QueryValueType.STRING) }
            }
        }
        assertThrows<IllegalArgumentException> {
            QueryDataset.of(ORDERS) {
                tenant("tenant_id")
                field("tenant_id") { types(QueryValueType.STRING) }
                sensitive("missing", SensitivityLevel.DISPLAY)
            }
        }
        assertThrows<IllegalArgumentException> {
            QueryDatasetScope(
                QueryField("t"),
                tags = mapOf(" " to QueryField("t"))
            )
        }
    }

    @Test
    fun `the profile maps system fields to the dataset's own columns and carries no other`() {
        val profile = orders().profile
        profile.model.assert().isEqualTo(QueryModel.DATASET)
        profile.payloadField.assert().isNull()
        profile.systemField(SystemField.IDENTITY).assert().isEqualTo(QueryField("order_id"))
        profile.systemField(SystemField.TENANT_ID).assert().isEqualTo(QueryField("tenant_id"))
        profile.systemField(SystemField.OWNER_ID).assert().isEqualTo(QueryField("owner_id"))
        profile.systemField(SystemField.SPACE_ID).assert().isNull()
        profile.systemField(SystemField.AGGREGATE_ID).assert().isNull()
        profile.systemField(SystemField.DELETED).assert().isNull()
        profile.requiredScope.assert().isEqualTo(QueryField("tenant_id"))
        profile.eventTimeField.assert().isEqualTo(QueryField("updated_at"))
        profile.cursorUniqueKey.assert().containsExactly(QueryField("order_id"))
        profile.defaultScope(MatchAllFilter).assert().isEqualTo(MatchAllFilter)
        profile.assert().isEqualTo(orders().profile)
    }

    @Test
    fun `datasets answer each dataset model's profile and fields`() {
        val datasets = QueryDatasets(listOf(orders()))
        val context = QuerySchemaContext(ORDERS, QueryModel.DATASET)
        datasets.profile(context).assert().isInstanceOf(DatasetQueryModelProfile::class.java)
        datasets.profile(QuerySchemaContext(ORDERS, QueryModel.SNAPSHOT)).assert().isSameAs(SnapshotQueryModelProfile)
        datasets.profile(QuerySchemaContext(OTHER, QueryModel.DATASET)).assert().isNull()
        runBlocking {
            datasets.schemaSource.load(context).assert().containsExactly(orders().declaration)
            datasets.schemaSource.load(QuerySchemaContext(ORDERS, QueryModel.SNAPSHOT)).assert().isEmpty()
            datasets.schemaSource.load(QuerySchemaContext(OTHER, QueryModel.DATASET)).assert().isEmpty()
        }
        assertThrows<IllegalArgumentException> { QueryDatasets(listOf(orders(), orders())) }
    }

    @Test
    fun `a dataset name is its context and the name after the last dot`() {
        QueryDataset.nameOf("analytics.orders").assert().isEqualTo(ORDERS)
        QueryDataset.nameOf("shop.v2.sales").assert().isEqualTo(AggregateType("shop.v2", "sales"))
        listOf("orders", ".orders", "analytics.").forEach { name ->
            assertThrows<IllegalArgumentException> { QueryDataset.nameOf(name) }
        }
    }

    @Test
    fun `a dataset document declares the profile, the storage binding and sensitive fields`() {
        val dataset = QueryDataset.parse(
            ORDERS,
            """
            {
              "title": "Orders",
              "identity": "order_id",
              "eventTime": "updated_at",
              "scope": { "tenant": "tenant_id", "owner": "owner_id", "space": "space_id", "tags": { "region": "region" } },
              "storage": { "table": "bi_db.orders" },
              "fields": {
                "order_id": { "types": ["STRING"], "nullable": false, "description": "The order" },
                "region": { "types": ["STRING"], "nullable": false },
                "tenant_id": { "types": ["STRING"] },
                "owner_id": { "types": ["STRING"] },
                "space_id": { "types": ["STRING"] },
                "status": { "types": ["STRING"], "enum": [{ "value": "PAID", "description": "Paid" }, { "value": "NEW" }] },
                "updated_at": { "types": ["STRING"], "semantic": { "type": "TEMPORAL_DATE" } },
                "lines": { "kind": "ARRAY", "items": { "kind": "OBJECT", "properties": { "sku": { "types": ["STRING"] } } } },
                "attributes": { "kind": "OBJECT", "values": { "types": ["STRING"] } },
                "phone": { "types": ["STRING"], "sensitive": { "level": "CONFIDENTIAL", "keepPrefix": 3, "keepSuffix": 4 } }
              }
            }
            """.trimIndent(),
        )
        dataset.title.assert().isEqualTo("Orders")
        dataset.identity.assert().isEqualTo(QueryField("order_id"))
        dataset.eventTime.assert().isEqualTo(QueryField("updated_at"))
        dataset.scope.owner.assert().isEqualTo(QueryField("owner_id"))
        dataset.scope.space.assert().isEqualTo(QueryField("space_id"))
        dataset.scope.tags.assert().isEqualTo(mapOf("region" to QueryField("region")))
        dataset.profile.tags.assert().isEqualTo(dataset.scope.tags)
        dataset.storage.get("table").stringValue().assert().isEqualTo("bi_db.orders")
        val fields = dataset.declaration.fields
        fields.getValue(QueryField("phone")).maskRule.assert().isEqualTo(
            DeclarationValue.Set(MaskRule(SensitivityLevel.CONFIDENTIAL, QueryMask.Keep(prefix = 3, suffix = 4))),
        )
        fields.getValue(
            QueryField("phone")
        ).valueTypes.assert().isEqualTo(DeclarationValue.Set(setOf(QueryValueType.STRING)))
        fields.getValue(QueryField("order_id")).description.assert().isEqualTo(DeclarationValue.Set("The order"))
        fields.getValue(QueryField("order_id")).nullable.assert().isEqualTo(DeclarationValue.Set(false))
        fields.getValue(QueryField("updated_at")).semanticType.assert().isEqualTo(DeclarationValue.Set(Temporal.Date))
        val status = fields.getValue(QueryField("status"))
        checkNotNull(
            (status.enumValues as DeclarationValue.Set).value
        ).map { it.stringValue() }.assert().containsExactly("PAID", "NEW")
        (status.enumDescriptions as DeclarationValue.Set).value.values.assert().containsExactly("Paid")
        val lines = fields.getValue(QueryField("lines"))
        lines.kind.assert().isEqualTo(DeclarationValue.Set(QueryValueKind.ARRAY))
        val items = checkNotNull((lines.items as DeclarationValue.Set).value)
        (items.properties as DeclarationValue.Set).value.keys.assert().containsExactly("sku")
        (
            fields.getValue(
                QueryField("attributes")
            ).additionalProperties as DeclarationValue.Set
            ).value.assert().isNotNull()
    }

    @Test
    fun `a dataset without a tenant column is declared platform-level`(@TempDir config: Path) {
        write(config, "analytics.erp", """{ "scope": { "platform": true }, "fields": { "sku": {} } }""")
        val dataset = files(config).load(AggregateType("analytics", "erp"))
        dataset.scope.platform.assert().isTrue()
        dataset.scope.tenant.assert().isNull()
        dataset.scope.fields.assert().isEmpty()
        dataset.profile.fixedTenantId.assert().isEqualTo(QueryDatasetScope.PLATFORM_TENANT)
        dataset.profile.requiredScope.assert().isEqualTo(QueryField("tenantId"))
        dataset.profile.systemField(SystemField.TENANT_ID).assert().isNull()
        assertThrows<IllegalArgumentException> {
            QueryDataset.of(ORDERS) { field("sku") { types(QueryValueType.STRING) } }
        }.message.assert().contains("platform-level")
        assertThrows<IllegalArgumentException> { QueryDatasetScope(QueryField("t"), platform = true) }
        QueryDataset.of(ORDERS) {
            platform()
            field("sku") { types(QueryValueType.STRING) }
        }.scope.platform.assert().isTrue()
        orders().scope.fixedTenantId.assert().isNull()
        orders().profile.fixedTenantId.assert().isNull()
    }

    @Test
    fun `a dataset file is read from the working directory`(@TempDir config: Path) {
        write(
            config,
            "analytics.orders",
            """{ "title": "Orders", "scope": { "tenant": "t" }, "storage": { "table": "orders" }, "fields": { "t": {} } }""",
        )
        val files = files(config)
        files.directory.assert().isEqualTo(config.resolve("wow").resolve("dataset"))
        val dataset = files.load(ORDERS)
        dataset.title.assert().isEqualTo("Orders")
        dataset.storage.get("table").stringValue().assert().isEqualTo("orders")
    }

    @Test
    fun `a dataset file is refused when it is malformed or missing`(@TempDir config: Path) {
        val invalid = mapOf(
            "unknown" to """{ "scope": { "tenant": "t" }, "fields": { "t": {} }, "table": "x" }""",
            "notenant" to """{ "scope": {}, "fields": { "t": {} } }""",
            "badlevel" to """{ "scope": { "tenant": "t" }, "fields": { "t": { "sensitive": { "level": "HIGH" } } } }""",
            "badkeep" to """{ "scope": { "tenant": "t" }, "fields": { "t": { "sensitive": { "level": "DISPLAY", "keepPrefix": -1 } } } }""",
            "undeclared" to """{ "scope": { "tenant": "missing" }, "fields": { "t": {} } }""",
            "notjson" to """{ "scope": """,
        )
        invalid.forEach { (name, json) -> write(config, "analytics.$name", json) }
        invalid.keys.forEach { name ->
            assertThrows<QuerySchemaUnavailableException> { files(config).load(AggregateType("analytics", name)) }
                .cause.assert().isInstanceOf(IllegalArgumentException::class.java)
        }
        assertThrows<QuerySchemaUnavailableException> { files(config).load(OTHER) }
        assertThrows<IllegalArgumentException> { files(config).load(AggregateType("a/b", "x")) }
    }

    @Test
    fun `the working directory file replaces the classpath one`(@TempDir config: Path) {
        files(config).load(CLASSPATH_ORDERS).title.assert().isEqualTo("From the classpath")
        write(
            config,
            "analytics.classpath_orders",
            """{ "title": "From config", "scope": { "tenant": "t" }, "fields": { "t": {} } }""",
        )
        val loaded = files(config).loadAll(listOf(CLASSPATH_ORDERS))
        loaded[CLASSPATH_ORDERS]!!.title.assert().isEqualTo("From config")
    }

    @Test
    fun `every dataset file of the working directory is loaded`(@TempDir config: Path) {
        files(config).loadDirectory().all.assert().isEmpty()
        write(config, "shop.sales", """{ "scope": { "tenant": "t" }, "fields": { "t": {} } }""")
        write(config, "analytics.orders", """{ "scope": { "tenant": "t" }, "fields": { "t": {} } }""")
        Files.writeString(config.resolve("wow/dataset/notes.txt"), "not a dataset")
        Files.createDirectories(config.resolve("wow/dataset/nested.json"))
        files(config).loadDirectory().all.map { it.name }.assert()
            .containsExactly(ORDERS, AggregateType("shop", "sales"))
    }

    @Test
    fun `every malformed part of a dataset file is refused`() {
        val scope = """"scope": { "tenant": "t" }"""
        val fields = """"fields": { "t": {} }"""
        val invalid = listOf(
            """[]""",
            """{ $scope, "fields": [] }""",
            """{ "scope": [], $fields }""",
            """{ "scope": { "tenant": "t", "region": "r" }, $fields }""",
            """{ $scope, $fields, "storage": [] }""",
            """{ "scope": { "tenant": "t", "platform": true }, $fields }""",
            """{ "scope": { "platform": false }, $fields }""",
            """{ "scope": { "platform": "yes" }, $fields }""",
            """{ "scope": { "tenant": "t", "tags": [] }, $fields }""",
            """{ "scope": { "tenant": "t", "tags": { "region": 1 } }, $fields }""",
            """{ "scope": { "tenant": "t", "tags": { "region": "missing" } }, $fields }""",
            """{ "scope": { "tenant": "t", "tags": { " ": "t" } }, $fields }""",
            """{ "scope": { "tenant": 1 }, $fields }""",
            """{ $scope, "fields": { "t": 1 } }""",
            """{ $scope, $fields, "title": 1 }""",
            """{ $scope, $fields, "identity": 1 }""",
            """{ $scope, "fields": { "t": { "sensitive": 1 } } }""",
            """{ $scope, "fields": { "t": { "sensitive": { "level": "DISPLAY", "strategy": "x" } } } }""",
            """{ $scope, "fields": { "t": { "sensitive": { "level": 1 } } } }""",
            """{ $scope, "fields": { "t": { "sensitive": { "level": "DISPLAY", "keepSuffix": "1" } } } }""",
            """{ $scope, "fields": { "t": { "unknown": true } } }""",
            """{ $scope, "fields": { "t": { "kind": "UNION" } } }""",
            """{ $scope, "fields": { "t": { "kind": 1 } } }""",
            """{ $scope, "fields": { "t": { "types": [] } } }""",
            """{ $scope, "fields": { "t": { "types": "STRING" } } }""",
            """{ $scope, "fields": { "t": { "types": ["OBJECT"] } } }""",
            """{ $scope, "fields": { "t": { "types": [1] } } }""",
            """{ $scope, "fields": { "t": { "nullable": "yes" } } }""",
            """{ $scope, "fields": { "t": { "description": 1 } } }""",
            """{ $scope, "fields": { "t": { "enum": [] } } }""",
            """{ $scope, "fields": { "t": { "enum": ["A"] } } }""",
            """{ $scope, "fields": { "t": { "enum": [{ "description": "A" }] } } }""",
            """{ $scope, "fields": { "t": { "enum": [{ "value": "A", "other": 1 }] } } }""",
            """{ $scope, "fields": { "t": { "enum": [{ "value": "A", "description": 1 }] } } }""",
            """{ $scope, "fields": { "t": { "enum": [{ "value": "A" }, { "value": "A" }] } } }""",
            """{ $scope, "fields": { "t": { "semantic": "DATE" } } }""",
            """{ $scope, "fields": { "t": { "semantic": { "type": "NOT_A_TYPE" } } } }""",
            """{ $scope, "fields": { "t": { "properties": [] } } }""",
            """{ $scope, "fields": { "t": { "properties": { "a.b": {} } } } }""",
            """{ $scope, "fields": { "t": { "properties": { "a": 1 } } } }""",
            """{ $scope, "fields": { "t": { "items": [] } } }""",
            """{ $scope, "fields": { "t": { "values": 1 } } }""",
        )
        invalid.forEach { json ->
            assertThrows<IllegalArgumentException>(json) { QueryDataset.parse(ORDERS, json) }
        }
    }

    @Test
    fun `a dataset declared twice on the classpath is refused`(@TempDir first: Path, @TempDir second: Path) {
        listOf(first, second).forEach { root ->
            val directory = Files.createDirectories(root.resolve("META-INF/wow/dataset"))
            Files.writeString(
                directory.resolve("analytics.twice.json"),
                """{ "scope": { "tenant": "t" }, "fields": { "t": {} } }""",
            )
        }
        URLClassLoader(arrayOf(first.toUri().toURL(), second.toUri().toURL()), null).use { loader ->
            val files = QueryDatasetFiles(first.resolve("none"), loader)
            assertThrows<IllegalArgumentException> { files.load(AggregateType("analytics", "twice")) }
                .message.assert().contains("more than once")
        }
    }

    @Test
    fun `the builder sets every profile fact and the storage binding`() {
        val storage = WowJson.mapper.createObjectNode().put("table", "orders")
        val dataset = QueryDataset.of(ORDERS) {
            tenant("tenant_id")
            owner("owner_id")
            space("space_id")
            tag("region", "region_id")
            title("Orders")
            storage(storage)
            field("tenant_id") { types(QueryValueType.STRING) }
            field("owner_id") { types(QueryValueType.STRING) }
            field("space_id") { types(QueryValueType.STRING) }
            field("region_id") { types(QueryValueType.STRING) }
        }
        dataset.scope.fields.assert().containsExactly(
            QueryField("tenant_id"),
            QueryField("owner_id"),
            QueryField("space_id"),
            QueryField("region_id"),
        )
        dataset.profile.tags.assert().isEqualTo(mapOf("region" to QueryField("region_id")))
        dataset.title.assert().isEqualTo("Orders")
        dataset.storage.assert().isEqualTo(storage)
        dataset.profile.systemField(SystemField.SPACE_ID).assert().isEqualTo(QueryField("space_id"))
        dataset.profile.identityField.assert().isNull()
        dataset.profile.eventTimeField.assert().isNull()
        dataset.profile.systemDeclaration.fields.assert().isEmpty()
        dataset.profile.toString().assert().contains("analytics")
        dataset.profile.hashCode().assert().isEqualTo(dataset.hashCode())
        dataset.profile.assert().isNotEqualTo(orders().profile).isNotEqualTo(SnapshotQueryModelProfile)
        dataset.copy(title = null).title.assert().isNull()
        assertThrows<IllegalArgumentException> {
            QueryDataset(ORDERS, QueryDatasetScope(QueryField("tenant_id")), QuerySchemaDeclaration(emptyMap()))
        }
    }

    private fun files(config: Path) = QueryDatasetFiles(config)

    private fun write(config: Path, key: String, json: String) {
        val directory = Files.createDirectories(config.resolve("wow").resolve("dataset"))
        Files.writeString(directory.resolve("$key.json"), json)
    }

    companion object {
        val ORDERS = AggregateType("analytics", "orders")
        val OTHER = AggregateType("analytics", "missing")
        val CLASSPATH_ORDERS = AggregateType("analytics", "classpath_orders")

        fun orders(): QueryDataset = QueryDataset.of(ORDERS) {
            tenant("tenant_id")
            owner("owner_id")
            identity("order_id")
            eventTime("updated_at")
            field("order_id") {
                types(QueryValueType.STRING)
                nullable(false)
            }
            field("tenant_id") {
                types(QueryValueType.STRING)
                nullable(false)
            }
            field("owner_id") {
                types(QueryValueType.STRING)
                nullable(false)
            }
            field("city") {
                types(QueryValueType.STRING)
                nullable(false)
            }
            field("amount") {
                types(QueryValueType.DECIMAL)
                nullable(false)
            }
            field("updated_at") {
                types(QueryValueType.INTEGER)
                nullable(false)
                temporalEpoch()
            }
            field("phone") {
                types(QueryValueType.STRING)
                nullable(true)
            }
            sensitive("phone", SensitivityLevel.CONFIDENTIAL, QueryMask.Keep(suffix = 4))
        }
    }
}
