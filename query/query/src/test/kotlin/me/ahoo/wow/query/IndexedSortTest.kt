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

package me.ahoo.wow.query

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.CursorQuery
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.QueryErrorCodes
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.query.admission.QueryBudget
import me.ahoo.wow.query.backend.cursor
import me.ahoo.wow.query.backend.list
import me.ahoo.wow.query.backend.paged
import me.ahoo.wow.query.error.QueryRequestException
import me.ahoo.wow.query.memory.InMemoryQueryStorage
import me.ahoo.wow.query.memory.InMemoryQueryStorageAdapter
import me.ahoo.wow.query.policy.QueryEntryPolicy
import me.ahoo.wow.query.schema.DefaultQueryModelSchemaProvider
import me.ahoo.wow.query.schema.FixedQueryModelSchemaProvider
import me.ahoo.wow.query.schema.LogicalQuerySchema
import me.ahoo.wow.query.schema.ManifestFixtures
import me.ahoo.wow.query.schema.ManifestQuerySchemaSource
import me.ahoo.wow.query.schema.QueryCapability
import me.ahoo.wow.query.schema.QueryFieldBindingTemplate
import me.ahoo.wow.query.schema.QueryModel
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QueryPathSegment
import me.ahoo.wow.query.schema.QuerySchemaContext
import me.ahoo.wow.query.schema.QueryStorageAdapter
import me.ahoo.wow.query.schema.QueryStorageFacts
import me.ahoo.wow.query.schema.QueryValueBindings
import me.ahoo.wow.query.schema.describe
import org.junit.jupiter.api.Test

/**
 * The storage's "indexed" fact (#4338): it never gates a capability, the descriptor publishes it, and a budget may
 * treat an unindexed sort as expensive.
 */
class IndexedSortTest {
    /** The in-memory facts, with `state.buyer` sorted without an index and `state.note` with one. */
    private object IndexedAdapter : QueryStorageAdapter {
        override suspend fun facts(logicalSchema: LogicalQuerySchema): QueryStorageFacts {
            val facts = InMemoryQueryStorageAdapter.facts(logicalSchema)
            val indexed = mapOf("state.buyer" to false, "state.note" to true)
            return QueryStorageFacts(
                bindings = facts.bindings.mapValues { (path, native) ->
                    val name = path.segments.joinToString(".") { (it as? QueryPathSegment.Property)?.name ?: "*" }
                    val flag = indexed[name] ?: return@mapValues native
                    QueryValueBindings(
                        native.bindings.mapValues { (capability, template) ->
                            if (capability == QueryCapability.SORT || capability == QueryCapability.CURSOR_SORT) {
                                QueryFieldBindingTemplate(
                                    template.physicalPath,
                                    template.storageTypes,
                                    template.physicalScope,
                                    flag
                                )
                            } else {
                                template
                            }
                        },
                        native.projectionPath,
                        native.responsePath,
                    )
                },
                storage = facts.storage,
            )
        }
    }

    private suspend fun schema(): QueryModelSchema = DefaultQueryModelSchemaProvider(
        QuerySchemaContext(ManifestFixtures.ORDER, QueryModel.SNAPSHOT),
        listOf(ManifestQuerySchemaSource(ManifestFixtures.manifest(), ManifestFixtures.STRATEGIES)),
        IndexedAdapter,
    ).schema()

    @Test
    fun `the descriptor marks a sort the storage runs without an index`() {
        runTest {
            val schema = schema()
            schema.field(BUYER)?.sortIndexed().assert().isFalse()
            schema.field(NOTE)?.sortIndexed().assert().isTrue()
            schema.field(QueryField("state.currency"))?.sortIndexed().assert().isNull()
            val fields = schema.describe(null, null).fields.associateBy { it.path }
            fields.getValue("state.buyer").sort.run {
                paged.assert().isTrue()
                indexed.assert().isFalse()
            }
            fields.getValue("state.note").sort.indexed.assert().isTrue()
            fields.getValue("state.currency").sort.indexed.assert().isNull()
            // The fact is part of the schema's content.
            schema.version.assert().isNotEqualTo(ManifestFixtures.orderSchema().version)
        }
    }

    @Test
    fun `an unindexed sort is allowed by default, and expensive when the budget says so`() {
        runTest {
            val schema = schema()
            val backend = InMemoryQueryStorage().create(ManifestFixtures.ORDER).backend
            fun gateway(budget: QueryBudget) = DefaultQueryGateway(
                ManifestFixtures.ORDER,
                backend,
                FixedQueryModelSchemaProvider(schema),
                entryPolicy = QueryEntryPolicy(http = budget),
            )
            val http = QueryCaller(entry = QueryEntry.HTTP)
            val byBuyer = listOf(Sort(BUYER, Sort.Direction.ASC))
            // Permissive by default, even on an entry that refuses expensive operators.
            gateway(QueryBudget("HTTP", allowExpensiveOperators = false))
                .list(ListQuery(MatchAllFilter, sort = byBuyer, limit = 10), http).toList().assert().isEmpty()
            // Flag on, expensive allowed: still permitted.
            gateway(QueryBudget("HTTP", unindexedSortExpensive = true))
                .list(ListQuery(MatchAllFilter, sort = byBuyer, limit = 10), http).toList().assert().isEmpty()

            val strict = gateway(QueryBudget("HTTP", allowExpensiveOperators = false, unindexedSortExpensive = true))
            val error = runCatching {
                strict.list(ListQuery(MatchAllFilter, sort = byBuyer, limit = 10), http).toList()
            }.exceptionOrNull() as QueryRequestException
            error.code.assert().isEqualTo(QueryErrorCodes.EXPENSIVE_OPERATOR_DISABLED)
            error.message.assert().contains("unindexed sort on [state.buyer]")
            // An indexed sort passes, and so does a cursor whose appended tie-breakers say nothing.
            strict.list(ListQuery(MatchAllFilter, sort = listOf(Sort(NOTE, Sort.Direction.ASC)), limit = 10), http)
                .toList().assert().isEmpty()
            strict.cursor(CursorQuery(MatchAllFilter, sort = listOf(Sort(NOTE, Sort.Direction.ASC))), http)
                .list.assert().isEmpty()
            runCatching { strict.cursor(CursorQuery(MatchAllFilter, sort = byBuyer), http) }
                .exceptionOrNull().assert().isInstanceOf(QueryRequestException::class.java)
        }
    }

    private companion object {
        val BUYER = QueryField("state.buyer")
        val NOTE = QueryField("state.note")
    }
}
