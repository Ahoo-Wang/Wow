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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.annotation.Mask
import me.ahoo.wow.api.query.annotation.SensitivityLevel
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.api.query.spec.SystemField
import me.ahoo.wow.configuration.WowResourceLocator
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.query.schema.DatasetQueryModelProfile
import me.ahoo.wow.query.schema.DeclarationValue
import me.ahoo.wow.query.schema.MaskRule
import me.ahoo.wow.query.schema.QueryDataset
import me.ahoo.wow.query.schema.QueryDatasetFiles
import me.ahoo.wow.query.schema.QueryDatasetScope
import me.ahoo.wow.query.schema.QueryDatasets
import me.ahoo.wow.query.schema.QuerySchemaContext
import me.ahoo.wow.query.schema.QuerySchemaDeclaration
import me.ahoo.wow.query.schema.QuerySchemaUnavailableException
import me.ahoo.wow.query.schema.SnapshotQueryModelProfile
import me.ahoo.wow.serialization.JsonSerializer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import reactor.kotlin.test.test
import java.nio.file.Files
import java.nio.file.Path

class QueryDatasetTest {
    @Test
    fun `builder declares the profile columns, fields and their sensitivity`() {
        val dataset = orders()
        dataset.namedAggregate.assert().isEqualTo(ORDERS)
        dataset.scope.tenant.assert().isEqualTo(QueryField("tenant_id"))
        dataset.identity.assert().isEqualTo(QueryField("order_id"))
        dataset.declaration.fields[QueryField("phone")]!!.maskRule
            .assert().isEqualTo(DeclarationValue.Set(MaskRule(SensitivityLevel.CONFIDENTIAL, Mask(keepSuffix = 4))))
        dataset.declaration.fields[QueryField("city")]!!.maskRule.assert().isEqualTo(DeclarationValue.Unset)
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
        profile.defaultScope(
            me.ahoo.wow.api.query.MatchAllFilter
        ).assert().isEqualTo(me.ahoo.wow.api.query.MatchAllFilter)
        profile.assert().isEqualTo(orders().profile)
    }

    @Test
    fun `datasets answer each dataset model's profile and fields`() {
        val datasets = QueryDatasets(listOf(orders()))
        val context = QuerySchemaContext(ORDERS, QueryModel.DATASET)
        datasets.profile(context).assert().isInstanceOf(DatasetQueryModelProfile::class.java)
        datasets.profile(QuerySchemaContext(ORDERS, QueryModel.SNAPSHOT)).assert().isSameAs(SnapshotQueryModelProfile)
        datasets.profile(QuerySchemaContext(OTHER, QueryModel.DATASET)).assert().isNull()
        datasets.schemaSource.load(context).test().expectNext(orders().declaration).verifyComplete()
        datasets.schemaSource.load(QuerySchemaContext(ORDERS, QueryModel.SNAPSHOT)).test().verifyComplete()
        datasets.schemaSource.load(QuerySchemaContext(OTHER, QueryModel.DATASET)).test().verifyComplete()
        assertThrows<IllegalArgumentException> { QueryDatasets(listOf(orders(), orders())) }
    }

    @Test
    fun `a dataset file declares the profile, the storage binding and sensitive fields`(@TempDir config: Path) {
        write(
            config,
            "analytics.orders",
            """
            {
              "title": "Orders",
              "identity": "order_id",
              "eventTime": "updated_at",
              "scope": { "tenant": "tenant_id", "owner": "owner_id", "tags": { "region": "region" } },
              "storage": { "table": "bi_db.orders" },
              "fields": {
                "order_id": { "types": ["STRING"], "nullable": false },
                "region": { "types": ["STRING"], "nullable": false },
                "tenant_id": { "types": ["STRING"] },
                "owner_id": { "types": ["STRING"] },
                "updated_at": { "types": ["STRING"], "semantic": { "type": "TEMPORAL_DATE" } },
                "phone": { "types": ["STRING"], "sensitive": { "level": "CONFIDENTIAL", "keepSuffix": 4 } }
              }
            }
            """.trimIndent(),
        )
        val dataset = files(config).load(ORDERS)
        dataset.title.assert().isEqualTo("Orders")
        dataset.identity.assert().isEqualTo(QueryField("order_id"))
        dataset.eventTime.assert().isEqualTo(QueryField("updated_at"))
        dataset.scope.owner.assert().isEqualTo(QueryField("owner_id"))
        dataset.scope.tags.assert().isEqualTo(mapOf("region" to QueryField("region")))
        dataset.profile.tags.assert().isEqualTo(dataset.scope.tags)
        dataset.storage.get("table").stringValue().assert().isEqualTo("bi_db.orders")
        dataset.declaration.fields[QueryField("phone")]!!.maskRule
            .assert().isEqualTo(DeclarationValue.Set(MaskRule(SensitivityLevel.CONFIDENTIAL, Mask(keepSuffix = 4))))
    }

    @Test
    fun `a dataset file is refused when it is malformed or missing`(@TempDir config: Path) {
        val invalid = mapOf(
            "unknown" to """{ "scope": { "tenant": "t" }, "fields": { "t": {} }, "table": "x" }""",
            "notenant" to """{ "scope": {}, "fields": { "t": {} } }""",
            "badlevel" to """{ "scope": { "tenant": "t" }, "fields": { "t": { "sensitive": { "level": "HIGH" } } } }""",
            "badkeep" to """{ "scope": { "tenant": "t" }, "fields": { "t": { "sensitive": { "level": "DISPLAY", "keepPrefix": -1 } } } }""",
            "undeclared" to """{ "scope": { "tenant": "missing" }, "fields": { "t": {} } }""",
        )
        invalid.forEach { (name, json) -> write(config, "analytics.$name", json) }
        invalid.keys.forEach { name ->
            assertThrows<QuerySchemaUnavailableException> {
                files(config).load(MaterializedNamedAggregate("analytics", name))
            }
        }
        assertThrows<QuerySchemaUnavailableException> { files(config).load(OTHER) }
    }

    @Test
    fun `the working directory file replaces the classpath one`(@TempDir config: Path) {
        files(config).load(CLASSPATH_ORDERS).title.assert().isEqualTo("From the classpath")
        write(
            config,
            "analytics.classpath_orders",
            """{ "title": "From config", "scope": { "tenant": "t" }, "fields": { "t": {} } }"""
        )
        val loaded = files(config).loadAll(listOf(CLASSPATH_ORDERS))
        loaded[CLASSPATH_ORDERS]!!.title.assert().isEqualTo("From config")
    }

    @Test
    fun `every malformed part of a dataset file is refused`(@TempDir config: Path) {
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
            """{ $scope, "fields": { "t": 1 } }""",
            """{ $scope, $fields, "title": 1 }""",
            """{ $scope, $fields, "identity": 1 }""",
            """{ $scope, "fields": { "t": { "sensitive": 1 } } }""",
            """{ $scope, "fields": { "t": { "sensitive": { "level": "DISPLAY", "strategy": "x" } } } }""",
            """{ $scope, "fields": { "t": { "sensitive": { "level": 1 } } } }""",
            """{ $scope, "fields": { "t": { "sensitive": { "level": "DISPLAY", "keepSuffix": "1" } } } }""",
        )
        invalid.forEachIndexed { index, json -> write(config, "analytics.malformed$index", json) }
        invalid.indices.forEach { index ->
            assertThrows<QuerySchemaUnavailableException>(invalid[index]) {
                files(config).load(MaterializedNamedAggregate("analytics", "malformed$index"))
            }
        }
    }

    @Test
    fun `a dataset declared twice on the classpath is refused`(@TempDir first: Path, @TempDir second: Path) {
        listOf(first, second).forEach { root ->
            val directory = Files.createDirectories(root.resolve("META-INF/wow/dataset"))
            Files.writeString(
                directory.resolve("analytics.twice.json"),
                """{ "scope": { "tenant": "t" }, "fields": { "t": {} } }"""
            )
        }
        val loader = java.net.URLClassLoader(arrayOf(first.toUri().toURL(), second.toUri().toURL()), null)
        val files = QueryDatasetFiles(WowResourceLocator(configDirectory = first.resolve("none"), classLoader = loader))
        assertThrows<IllegalArgumentException> { files.load(MaterializedNamedAggregate("analytics", "twice")) }
            .message.assert().contains("more than once")
    }

    @Test
    fun `a dataset without a tenant column is declared platform-level`(@TempDir config: Path) {
        write(config, "analytics.erp", """{ "scope": { "platform": true }, "fields": { "sku": {} } }""")
        val dataset = files(config).load(MaterializedNamedAggregate("analytics", "erp"))
        dataset.scope.platform.assert().isTrue()
        dataset.scope.tenant.assert().isNull()
        dataset.scope.fields.assert().isEmpty()
        dataset.profile.fixedTenantId.assert().isEqualTo("(0)")
        assertThrows<IllegalArgumentException> {
            QueryDataset.of(ORDERS) { field("sku") { types(QueryValueType.STRING) } }
        }.message.assert().contains("platform-level")
        assertThrows<IllegalArgumentException> { QueryDatasetScope(QueryField("t"), platform = true) }
        orders().scope.fixedTenantId.assert().isNull()
        orders().profile.fixedTenantId.assert().isNull()
    }

    @Test
    fun `the builder sets every profile fact and the storage binding`() {
        val storage = JsonSerializer.createObjectNode().put("table", "orders")
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
        val copied = dataset.copy(title = null)
        copied.title.assert().isNull()
        assertThrows<IllegalArgumentException> {
            QueryDataset(ORDERS, QueryDatasetScope(QueryField("tenant_id")), QuerySchemaDeclaration(emptyMap()))
        }
    }

    private fun files(config: Path) = QueryDatasetFiles(WowResourceLocator(configDirectory = config))

    private fun write(config: Path, key: String, json: String) {
        val directory = Files.createDirectories(config.resolve("wow").resolve("dataset"))
        Files.writeString(directory.resolve("$key.json"), json)
    }

    companion object {
        val ORDERS = MaterializedNamedAggregate("analytics", "orders")
        val OTHER = MaterializedNamedAggregate("analytics", "missing")
        val CLASSPATH_ORDERS = MaterializedNamedAggregate("analytics", "classpath_orders")

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
            sensitive("phone", SensitivityLevel.CONFIDENTIAL, Mask(keepSuffix = 4))
        }
    }
}
