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

package me.ahoo.wow.spring.boot.query

import io.mockk.every
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.http.query.HttpQueries
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.StateManifest
import me.ahoo.wow.query.admission.QueryBudget
import me.ahoo.wow.query.backend.QueryBackendStorages
import me.ahoo.wow.query.engine.ManifestStateSchemas
import me.ahoo.wow.query.index.QueryIndexPlan
import me.ahoo.wow.query.memory.InMemoryQueryStorage
import me.ahoo.wow.query.schema.QueryModel
import me.ahoo.wow.query.schema.QueryModelCompiler
import me.ahoo.wow.query.schema.QuerySchemaCatalog
import me.ahoo.wow.query.schema.QuerySchemaContext
import me.ahoo.wow.query.store.QueryIndexReport
import me.ahoo.wow.spi.config.WowConfigurationException
import me.ahoo.wow.spring.boot.BANK
import me.ahoo.wow.spring.boot.HostQueryFixtures.ACCOUNT
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration

/** The query plane's settings and the indexes and schemas it prepares for the stores before the host starts. */
class HostQueryIndexesTest {
    @Test
    fun `the properties should default to the query engine's defaults`() {
        val properties = QueryProperties()
        val policy = properties.entryPolicy()
        policy.http.maxListSize.assert().isEqualTo(QueryBudget.HTTP_DEFAULT.maxListSize)
        policy.http.countLimit.assert().isEqualTo(QueryBudget.DEFAULT_COUNT_LIMIT)
        policy.http.allowExactCount.assert().isFalse()
        policy.inProcessTimeout.assert().isEqualTo(QueryBudget.DEFAULT_TIMEOUT)
        policy.requireExplicitEntry.assert().isFalse()
        properties.sensitivity.policy().displayComparable.assert().isTrue()
        // Unset: on wherever the store is in memory.
        properties.memory.enabled.assert().isTrue()
        properties.budgets.http.defaultListSize.assert().isEqualTo(HttpQueries.DEFAULT_LIST_SIZE)
        policy.inProcess!!.maxListSize.assert().isEqualTo(QueryBudget.IN_PROCESS_DEFAULT.maxListSize)
        policy.inProcess!!.label.assert().isEqualTo(QueryBudget.IN_PROCESS_LABEL)
        properties.budgets.inProcess.defaultListSize.assert().isEqualTo(HttpQueries.DEFAULT_LIST_SIZE)
        properties.unboundedTimeout = Duration.ZERO
        properties.entryPolicy().inProcessTimeout.assert().isNull()
    }

    @Test
    fun `an events backend should manage the indexes of the logs on the stores that manage them only`() {
        val plan = QueryIndexPlan.of(runBlocking { catalogSchema() }, emptyList())
        EventQueryBackend.NONE.managesIndexes.assert().isFalse()
        EventQueryBackend.NONE.managesIndexes(ACCOUNT).assert().isFalse()
        EventQueryBackend.NONE.manageIndexes(ACCOUNT, plan).assert().isEqualTo(QueryIndexReport.NONE)
        // Given indexes and no say per type, every type's.
        val every = EventQueryBackend(indexes = { _, _ -> QueryIndexReport.NONE }) { null }
        every.managesIndexes(ACCOUNT).assert().isTrue()
        val prepared = ArrayList<AggregateType>()
        val created = QueryIndexReport(created = listOf("access_owner"))
        val backend = EventQueryBackend(
            prepare = { prepared += it },
            indexes = { _, _ -> created },
            manages = { it == ACCOUNT },
        ) { null }
        backend.managesIndexes(ACCOUNT).assert().isTrue()
        backend.managesIndexes(AggregateType(BANK, "other")).assert().isFalse()
        backend.prepare(ACCOUNT)
        prepared.assert().containsExactly(ACCOUNT)
        backend.manageIndexes(ACCOUNT, plan).assert().isEqualTo(created)
    }

    @Test
    fun `a backend without index management should report nothing`() {
        SnapshotQueryBackend.NONE.managesIndexes.assert().isFalse()
        SnapshotQueryBackend(InMemoryQueryStorage()).managesIndexes.assert().isFalse()
        SnapshotQueryBackend(InMemoryQueryStorage())
            .manageIndexes(ACCOUNT, QueryIndexPlan.of(runBlocking { catalogSchema() }, emptyList()))
            .assert().isEqualTo(QueryIndexReport.NONE)
    }

    @Test
    fun `an index that validate finds missing or invalid should refuse the start, naming the store's ddl`() {
        val account = AggregateManifest("account", state = StateManifest("#/\$defs/Account"))
        val manifest = Manifest(contexts = listOf(ContextManifest(BANK, aggregates = listOf(account))))
        val catalog = QuerySchemaCatalog(QueryBackendStorages(InMemoryQueryStorage()))
        fun backend(report: QueryIndexReport) = SnapshotQueryBackend(
            InMemoryQueryStorage(),
            indexes = { _, _ -> report },
            store = { "reads" },
        )
        val validated = QueryIndexReport(missing = listOf("ix_a"), invalid = listOf("ix_b"))
        val refused = assertThrows<WowConfigurationException> {
            QueryIndexesLifecycle(manifest, listOf(ACCOUNT), backend(validated), catalog) { "wow.stores.$it.ddl" }
                .start()
        }
        refused.key.assert().isEqualTo("wow.stores.reads.ddl")
        refused.message.assert().contains("ix_a", "ix_b", "$ACCOUNT")
        assertThrows<WowConfigurationException> {
            QueryIndexesLifecycle(manifest, listOf(ACCOUNT), backend(validated), catalog).start()
        }.key.assert().isEqualTo("wow.ddl")
        // What auto created is not missing any more.
        val created = QueryIndexReport(created = listOf("ix_a"), missing = listOf("ix_a"))
        QueryIndexesLifecycle(manifest, listOf(ACCOUNT), backend(created), catalog).apply {
            start()
            isRunning.assert().isTrue()
            reports.getValue(ACCOUNT).assert().isEqualTo(created)
        }
    }

    @Test
    fun `a state holding a sealed value should start, its discriminator indexed`() {
        // Each subtype as the Manifest writes it: its own properties, then its discriminator's value.
        fun subtype(name: String, extra: Map<String, Any?>) = mapOf(
            "allOf" to listOf(
                mapOf("type" to "object", "properties" to mapOf("type" to mapOf("type" to "string")) + extra),
                mapOf("type" to "object", "properties" to mapOf("type" to mapOf("const" to name))),
            ),
        )
        val holder = mapOf("oneOf" to listOf(mapOf("\$ref" to "#/\$defs/Rack"), mapOf("\$ref" to "#/\$defs/Crate")))
        val account = AggregateManifest("account", state = StateManifest("#/\$defs/Account"))
        val manifest = Manifest(
            contexts = listOf(ContextManifest(BANK, aggregates = listOf(account))),
            defs = mapOf(
                "Account" to mapOf(
                    "type" to "object",
                    "properties" to mapOf("holder" to holder),
                    "x-wow-index" to listOf(mapOf("name" to "ix_holder_type", "keys" to listOf("state.holder.type"))),
                ),
                "Rack" to subtype("Rack", mapOf("levels" to mapOf("type" to "integer"))),
                "Crate" to subtype("Crate", mapOf("lidded" to mapOf("type" to "boolean"))),
            ),
        )
        val catalog = QuerySchemaCatalog(
            QueryBackendStorages(InMemoryQueryStorage()),
            QueryModelCompiler.of(listOf(ManifestStateSchemas(manifest))),
        )
        val plans = mutableListOf<QueryIndexPlan>()
        val backend = SnapshotQueryBackend(
            InMemoryQueryStorage(),
            indexes = { _, plan -> QueryIndexReport().also { plans += plan } },
        )
        QueryIndexesLifecycle(manifest, listOf(ACCOUNT), backend, catalog).apply {
            start()
            isRunning.assert().isTrue()
        }
        plans.single().indexes.single().keys.single().field.assert().isEqualTo(QueryField("state.holder.type"))
    }

    @Test
    fun `an aggregate's storage should be prepared before its indexes are managed`() {
        // Finding F16: a node's index step on a table runs after its own schema step of the table.
        val account = AggregateManifest("account", state = StateManifest("#/\$defs/Account"))
        val manifest = Manifest(contexts = listOf(ContextManifest(BANK, aggregates = listOf(account))))
        val catalog = QuerySchemaCatalog(QueryBackendStorages(InMemoryQueryStorage()))
        val steps = mutableListOf<String>()
        val backend = SnapshotQueryBackend(
            InMemoryQueryStorage(),
            prepare = { steps += "prepare $it" },
            indexes = { type, _ -> QueryIndexReport().also { steps += "indexes $type" } },
        )
        QueryIndexesLifecycle(manifest, listOf(ACCOUNT), backend, catalog).start()
        steps.assert().containsExactly("prepare $ACCOUNT", "indexes $ACCOUNT")
    }

    @Test
    fun `the Manifest's state schemas should declare the aggregates that carry one, no other`() {
        fun manifest(defs: Map<String, Any?>) = Manifest(
            contexts = listOf(
                ContextManifest(
                    BANK,
                    aggregates = listOf(AggregateManifest("account", state = StateManifest("#/\$defs/Account"))),
                ),
            ),
            defs = defs,
        )
        val context = QuerySchemaContext(ACCOUNT, QueryModel.SNAPSHOT)
        val declared = ManifestStateSchemas(
            manifest(
                mapOf(
                    "Account" to mapOf("type" to "object", "properties" to mapOf("owner" to mapOf("type" to "string")))
                )
            ),
        )
        declared.declares(ACCOUNT).assert().isTrue()
        runBlocking { declared.load(context) }.assert().hasSize(1)
        val undeclared = ManifestStateSchemas(manifest(emptyMap()))
        undeclared.declares(ACCOUNT).assert().isFalse()
        undeclared.declares(AggregateType(BANK, "other")).assert().isFalse()
        runBlocking { undeclared.load(context) }.assert().isEmpty()
        runBlocking { undeclared.load(context) }.assert().isEmpty()
    }

    private suspend fun catalogSchema() =
        QuerySchemaCatalog(QueryBackendStorages(InMemoryQueryStorage())).schema(ACCOUNT, QueryModel.SNAPSHOT)
}
