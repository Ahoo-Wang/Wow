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

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.query.QueryExecutionException
import me.ahoo.wow.query.schema.QueryCapability
import me.ahoo.wow.query.schema.QueryStorageType
import me.ahoo.wow.query.schema.QueryValueKind
import me.ahoo.wow.query.schema.QueryValueSchema
import me.ahoo.wow.query.schema.QueryValueType
import org.junit.jupiter.api.Test

class PostgresSnapshotQueryExpressionsTest {
    @Test
    fun `typed values guard the JSON type and cast deterministically`() {
        PostgresSnapshotQueryExpressions.value(QueryField("state.a.b"), PostgresQueryType.TEXT).assert().isEqualTo(
            "((CASE WHEN jsonb_typeof((state #> '{a,b}')) = 'string' THEN state #>> '{a,b}' END) COLLATE \"C\")",
        )
        PostgresSnapshotQueryExpressions.value(
            QueryField("state.amount"),
            PostgresQueryType.NUMERIC
        ).assert().isEqualTo(
            "(CASE WHEN jsonb_typeof((state #> '{amount}')) = 'number' THEN (state #>> '{amount}')::numeric END)",
        )
        PostgresSnapshotQueryExpressions.value(QueryField("state.flag"), PostgresQueryType.BOOLEAN).assert().isEqualTo(
            "(CASE WHEN jsonb_typeof((state #> '{flag}')) = 'boolean' THEN (state #>> '{flag}')::boolean END)",
        )
        PostgresSnapshotQueryExpressions.value(QueryField("tags.region"), PostgresQueryType.JSONB).assert()
            .isEqualTo("(tags #> '{region}')")
        PostgresSnapshotQueryExpressions.value("e1.v", emptyList(), PostgresQueryType.INTEGER).assert()
            .isEqualTo("(CASE WHEN jsonb_typeof(e1.v) = 'number' THEN (e1.v #>> '{}')::numeric END)")
        // The same field and type always give the same text, which the expression indexes rely on.
        PostgresSnapshotQueryExpressions.value(QueryField("state.amount"), PostgresQueryType.NUMERIC).assert()
            .isEqualTo(PostgresSnapshotQueryExpressions.value(QueryField("state.amount"), PostgresQueryType.NUMERIC))
    }

    @Test
    fun `system fields are their columns`() {
        PostgresSnapshotQueryExpressions.value(
            QueryField("ownerId"),
            PostgresQueryType.TEXT
        ).assert().isEqualTo("owner_id")
        PostgresSnapshotQueryExpressions.value(QueryField("state"), PostgresQueryType.JSONB).assert().isEqualTo("state")
        PostgresSnapshotQueryExpressions.node(
            QueryField("ownerId")
        ).assert().isEqualTo("coalesce(to_jsonb(owner_id), 'null'::jsonb)")
        PostgresSnapshotQueryExpressions.node(QueryField("tags")).assert().isEqualTo("tags")
        assertThrownBy<QueryExecutionException> {
            PostgresSnapshotQueryExpressions.value(QueryField("ownerId.x"), PostgresQueryType.TEXT)
        }
        assertThrownBy<QueryExecutionException> { PostgresSnapshotQueryExpressions.node(QueryField("version.x")) }
        assertThrownBy<QueryExecutionException> { PostgresSnapshotQueryExpressions.node(QueryField("other")) }
    }

    @Test
    fun `a path segment outside the query alphabet never reaches the SQL text`() {
        listOf("a'b", "a,b", "a}b", "a b", "a\"b", "").forEach { segment ->
            assertThrownBy<QueryExecutionException> {
                PostgresSnapshotQueryExpressions.node("state", listOf(segment))
            }
        }
        PostgresSnapshotQueryExpressions.node(
            "state",
            listOf("@type", "a-b", "0")
        ).assert().isEqualTo("(state #> '{@type,a-b,0}')")
    }

    @Test
    fun `a value type maps to its PostgreSQL type`() {
        fun scalar(vararg types: QueryValueType) = QueryValueSchema(QueryValueKind.SCALAR, valueTypes = types.toSet())
        PostgresQueryType.of(scalar(QueryValueType.STRING)).assert().isEqualTo(PostgresQueryType.TEXT)
        PostgresQueryType.of(
            scalar(QueryValueType.INTEGER, QueryValueType.DECIMAL)
        ).assert().isEqualTo(PostgresQueryType.NUMERIC)
        PostgresQueryType.of(scalar(QueryValueType.BOOLEAN)).assert().isEqualTo(PostgresQueryType.BOOLEAN)
        PostgresQueryType.of(
            scalar(QueryValueType.STRING, QueryValueType.INTEGER)
        ).assert().isEqualTo(PostgresQueryType.JSONB)
        PostgresQueryType.of(QueryValueSchema(QueryValueKind.ARRAY, items = scalar(QueryValueType.STRING))).assert()
            .isEqualTo(PostgresQueryType.TEXT)
        PostgresQueryType.of(QueryValueSchema(QueryValueKind.OBJECT)).assert().isEqualTo(PostgresQueryType.JSONB)
        PostgresQueryType.of(setOf(QueryStorageType("numeric"))).assert().isEqualTo(PostgresQueryType.NUMERIC)
        PostgresQueryType.of(setOf(QueryStorageType("keyword"))).assert().isNull()
        PostgresQueryType.of(null).assert().isNull()
    }

    @Test
    fun `the storage grants what the compiler can run`() {
        val schema = QueryTestSchemas.SCHEMA
        fun capabilities(path: String) = checkNotNull(schema.field(QueryField(path))).capabilities
        capabilities("state.name").assert().contains(
            QueryCapability.EXACT_MATCH,
            QueryCapability.LITERAL_MATCH,
            QueryCapability.RANGE,
            QueryCapability.CURSOR_SORT,
            QueryCapability.AGGREGATE_TERMS,
        ).doesNotContain(QueryCapability.FULL_TEXT_TERMS, QueryCapability.AGGREGATE_NUMERIC)
        capabilities("state.createdAt").assert().contains(QueryCapability.RANGE, QueryCapability.AGGREGATE_TEMPORAL)
        capabilities("state.code").assert().contains(QueryCapability.RANGE)
        capabilities("state.issuedAt").assert().containsExactly(QueryCapability.PRESENCE)
        capabilities("state.mixed").assert().containsExactly(QueryCapability.PRESENCE)
        capabilities("state.keywords").assert().contains(QueryCapability.EXACT_MATCH, QueryCapability.RANGE)
            .doesNotContain(QueryCapability.SORT, QueryCapability.AGGREGATE_TERMS)
        capabilities("state.lines").assert().contains(QueryCapability.ELEMENT_SCOPE)
        capabilities("lastEventTime").assert().contains(QueryCapability.CURSOR_SORT, QueryCapability.AGGREGATE_TEMPORAL)
        checkNotNull(schema.field(QueryField("version"))).binding(QueryCapability.RANGE)?.storageTypes.assert()
            .isEqualTo(setOf(PostgresQueryType.INTEGER.storageType))
    }
}
