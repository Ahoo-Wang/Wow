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

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.query.CaseFolding
import me.ahoo.wow.api.query.ContainsFilter
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.QueryErrorCodes
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.StartsWithFilter
import me.ahoo.wow.api.query.StringComparison
import me.ahoo.wow.postgres.PostgresFixture
import me.ahoo.wow.query.admission.QueryAdmission
import me.ahoo.wow.query.backend.list
import me.ahoo.wow.query.error.QueryError
import me.ahoo.wow.query.schema.BeanQuerySchemaSource
import me.ahoo.wow.query.schema.DefaultQueryModelSchemaProvider
import me.ahoo.wow.query.schema.QueryModel
import me.ahoo.wow.query.schema.QuerySchemaContext
import me.ahoo.wow.query.schema.QuerySchemaRegistration
import me.ahoo.wow.query.schema.QueryValueType
import me.ahoo.wow.query.schema.querySchema
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * An ignore-case `contains` or `startsWith` is matched with `~` and the core's folding pattern of its operand, which
 * PostgreSQL refuses past what its automaton takes (SQLSTATE 2201B, "too complex"), so a long one was answered as a
 * storage failure (#7211). A pattern past [PostgresFilterCompiler.MAX_PATTERN_BYTES] is refused as the storage's
 * limit, a query the caller can mend; one at the bound, of the widest class the folding writes, is answered. A text
 * with an unpaired surrogate, which pgjdbc writes as `?`, a quantifier in a pattern, is refused by admission. Over the
 * snapshot table a service queries, with the storage it is queried through.
 */
class PostgresIgnoreCasePatternTest {
    private val type = AggregateType("fold_" + UUID.randomUUID().toString().replace("-", "").take(12), "order")
    private val name = QueryField("state.name")

    private fun ids(filter: FilterExpression): List<String> = runBlocking {
        val store = PostgresFixture.snapshotStore(type.context)
        listOf("a" to """{"name":"Ann"}""", "b" to """{"name":"Bob"}""", "q" to """{"name":"a?"}""").forEach {
            store.save(QueryFixtures.snapshot(type, it.first, it.second))
        }
        val storage = PostgresSnapshotQueryStorage(PostgresFixture.dataSource, type.context)
        val binding = checkNotNull(storage.binding(type))
        val context = QuerySchemaContext(type, QueryModel.SNAPSHOT)
        val schema = DefaultQueryModelSchemaProvider(
            context,
            listOf(BeanQuerySchemaSource(listOf(QuerySchemaRegistration(context, DECLARATION)))),
            binding.storage,
        ).schema()
        binding.backend.list(QueryAdmission.Trusted.list(ListQuery(filter, limit = 10), schema)).toList()
            .map { it.path("aggregateId").stringValue() }.sorted()
    }

    private fun refusal(filter: FilterExpression): QueryError =
        runCatching { ids(filter) }.exceptionOrNull() as QueryError

    @Test
    fun `an ignore-case pattern past the bound should be refused as the storage's limit, not a storage failure`() {
        listOf(
            ContainsFilter(name, "a".repeat(LONG), StringComparison.CASE_INSENSITIVE),
            StartsWithFilter(name, "a".repeat(LONG), StringComparison.CASE_INSENSITIVE),
        ).forEach { filter ->
            refusal(filter).violations.single().code.assert().isEqualTo(QueryErrorCodes.STORAGE_UNSUPPORTED)
        }
        // A case-sensitive match of the same operand is a LIKE, which has no such bound.
        ids(ContainsFilter(name, "a".repeat(LONG))).assert().isEmpty()
    }

    @Test
    fun `an ignore-case pattern at the bound, of the widest class the folding writes, should be answered`() {
        val widest = WIDE.maxBy { CaseFolding.pattern(it.toString()).toByteArray().size }
        val width = CaseFolding.pattern(widest.toString()).toByteArray().size
        val operand = widest.toString().repeat(PostgresFilterCompiler.MAX_PATTERN_BYTES / width)
        val bytes = CaseFolding.pattern(operand).toByteArray().size
        bytes.assert().isLessThanOrEqualTo(PostgresFilterCompiler.MAX_PATTERN_BYTES)
        ids(ContainsFilter(name, operand, StringComparison.CASE_INSENSITIVE)).assert().isEmpty()
        ids(ContainsFilter(name, "aN", StringComparison.CASE_INSENSITIVE)).assert().containsExactly("a")
    }

    @Test
    fun `a text with an unpaired surrogate should be refused, never matched as a question mark`() {
        // pgjdbc would send `a?`: as a pattern every name matches it, as a LIKE the name `a?` does.
        listOf(
            ContainsFilter(name, "a\uD800", StringComparison.CASE_INSENSITIVE),
            ContainsFilter(name, "a\uD800"),
            EqualFilter(name, "a\uD800"),
        ).forEach { filter ->
            refusal(filter).violations.single().code.assert().isEqualTo(QueryErrorCodes.VALUE_MISMATCH)
        }
    }

    private companion object {
        /** An operand longer than any pattern bound: its folding pattern is four times its length. */
        const val LONG = 200_000

        /** Letters whose folding classes are among the widest the core writes, in UTF-8 bytes. */
        const val WIDE = "aksſKÅåσςΣθϑ"

        val DECLARATION = querySchema {
            field("state.name") { types(QueryValueType.STRING) }
        }
    }
}
