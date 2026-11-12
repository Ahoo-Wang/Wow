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

package me.ahoo.wow.postgres.query

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.manifest.AccessAttributeManifest
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.StateManifest
import me.ahoo.wow.postgres.PostgresIdentifiers
import me.ahoo.wow.postgres.snapshot.PostgresSnapshotTables
import me.ahoo.wow.query.index.QueryIndexDeclaration
import me.ahoo.wow.query.index.QueryIndexPlan
import me.ahoo.wow.query.schema.EventsQuerySchemas
import me.ahoo.wow.query.schema.QueryModel
import me.ahoo.wow.query.schema.QueryModelCompiler
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QuerySchemaContext
import me.ahoo.wow.spi.Ddl
import org.junit.jupiter.api.Test

class PostgresQueryIndexesTest {
    private val schema = QueryTestSchemas.SCHEMA

    @Test
    fun `an index leads with the scope and keys the compiler's expressions`() {
        val index = PostgresQueryIndexes.plan(
            "shop",
            "order",
            QueryIndexPlan.of(
                schema,
                listOf(QueryIndexDeclaration("ix_order_state_name", listOf("state.name", "ownerId", "state.amount"))),
            ),
        ).single()
        index.name.assert().isEqualTo("wow_snapshot__shop__order_ix_order_state_name")
        val name = PostgresSnapshotQueryExpressions.value(QueryField("state.name"), PostgresQueryType.TEXT)
        val amount = PostgresSnapshotQueryExpressions.value(QueryField("state.amount"), PostgresQueryType.NUMERIC)
        index.ddl.assert().isEqualTo(
            "CREATE INDEX CONCURRENTLY IF NOT EXISTS \"wow_snapshot__shop__order_ix_order_state_name\" ON \"wow_snapshot__shop__order\" " +
                "(tenant_id, ($name) NULLS FIRST, owner_id NULLS FIRST, ($amount) NULLS FIRST, aggregate_id) WHERE NOT deleted",
        )
        // Pinned: a change here means an edit of the DDL text, which rebuilds every declared index of every deployment.
        index.fingerprint.assert().isEqualTo("wow:737e78848e0e429f8d62c589bbd460ca")
    }

    /** The `events` model of `shop.order`, whose Manifest declares a single and a multiple access attribute. */
    private val events: QueryModelSchema by lazy {
        val type = AggregateType("shop", "order")
        val manifest = Manifest(
            contexts = listOf(
                ContextManifest(
                    "shop",
                    aggregates = listOf(
                        AggregateManifest(
                            "order",
                            state = StateManifest(Manifest.schemaRef("shop.OrderState")),
                            access = listOf(
                                AccessAttributeManifest("region", AccessAttributeManifest.Type.STRING),
                                AccessAttributeManifest("teams", AccessAttributeManifest.Type.STRING, multiple = true),
                            ),
                        ),
                    ),
                ),
            ),
            defs = mapOf("shop.OrderState" to mapOf("type" to "object")),
        )
        runBlocking {
            QueryModelCompiler.of(events = EventsQuerySchemas(manifest))
                .compile(QuerySchemaContext(type, QueryModel.EVENTS), PostgresEventStorageAdapter)
                .schema()
        }
    }

    @Test
    fun `a log indexes each access attribute as its events are compiled, partial on the attribute holding a value`() {
        val declarations = QueryIndexDeclaration.BUILT_IN_ACCESS +
            listOf("region", "teams").map { QueryIndexDeclaration("access_$it", listOf("access.$it")) }
        val indexes = PostgresQueryIndexes.planLog("shop", "order", QueryIndexPlan.of(events, declarations))
        val log = "\"wow_log__shop__order\""
        fun value(name: String) = PostgresEventQueryExpressions.value(
            QueryField("access.$name"),
            PostgresQueryType.TEXT
        )
        indexes.map { it.name }.assert().containsExactly(
            "wow_log__shop__order_access_owner",
            "wow_log__shop__order_access_space",
            "wow_log__shop__order_access_region",
            "wow_log__shop__order_access_teams",
        )
        indexes.forEach { it.table.assert().isEqualTo(log) }
        indexes.first().ddl.assert().isEqualTo(
            "CREATE INDEX CONCURRENTLY IF NOT EXISTS \"wow_log__shop__order_access_owner\" ON $log " +
                "(tenant_id, (${value("owner")}) NULLS FIRST) WHERE (${value("owner")}) IS NOT NULL",
        )
        val teams = PostgresEventQueryExpressions.node(QueryField("access.teams"))
        indexes.last().ddl.assert().isEqualTo(
            "CREATE INDEX CONCURRENTLY IF NOT EXISTS \"wow_log__shop__order_access_teams\" ON $log " +
                "USING gin ($teams jsonb_path_ops) WHERE $teams IS NOT NULL",
        )
    }

    @Test
    fun `a key of many values alone is a GIN index of its node, the containments' operator class`() {
        val index = PostgresQueryIndexes.plan(
            "shop",
            "order",
            QueryIndexPlan.of(schema, listOf(QueryIndexDeclaration("ix_keywords", listOf("state.keywords")))),
        ).single()
        index.ddl.assert().isEqualTo(
            "CREATE INDEX CONCURRENTLY IF NOT EXISTS \"wow_snapshot__shop__order_ix_keywords\" ON \"wow_snapshot__shop__order\" " +
                "USING gin ((state #> '{keywords}') jsonb_path_ops) WHERE NOT deleted",
        )
    }

    @Test
    fun `a key the backend cannot index is refused`() {
        fun refuses(key: String) = assertThrownBy<IllegalArgumentException> {
            PostgresQueryIndexes.plan(
                "shop",
                "order",
                QueryIndexPlan.of(schema, listOf(QueryIndexDeclaration("ix", listOf(key))))
            )
        }
        refuses("state.unknown")
        refuses("state.mixed")
        // Many values beside another key: a GIN index of many values holds it alone.
        assertThrownBy<IllegalArgumentException> {
            PostgresQueryIndexes.plan(
                "shop",
                "order",
                QueryIndexPlan.of(schema, listOf(QueryIndexDeclaration("ix", listOf("state.keywords", "state.name")))),
            )
        }.hasMessageContaining("GIN")
        assertThrownBy<IllegalArgumentException> { QueryIndexDeclaration("", listOf("state.name")) }
        assertThrownBy<IllegalArgumentException> { QueryIndexDeclaration("ix", emptyList()) }
    }

    @Test
    fun `names stay within the identifier limit and distinct`() {
        val long = "x".repeat(70)
        PostgresIdentifiers.limited(long).length.assert().isEqualTo(PostgresIdentifiers.MAX_LENGTH)
        PostgresIdentifiers.limited(long + "a").assert().isNotEqualTo(PostgresIdentifiers.limited(long + "b"))
        PostgresIdentifiers.limited("short").assert().isEqualTo("short")
        PostgresSnapshotTables("a".repeat(60), "order").let { tables ->
            tables.ownerIndex.length.assert().isEqualTo(PostgresIdentifiers.MAX_LENGTH)
            tables.ownerIndex.assert().isNotEqualTo(tables.spaceIndex)
        }
    }

    @Test
    fun `the options default to managing the indexes`() {
        PostgresQueryOptions().indexDdl.assert().isEqualTo(Ddl.AUTO)
        assertThrownBy<IllegalArgumentException> { PostgresQueryOptions(fetchSize = 0) }
    }
}
