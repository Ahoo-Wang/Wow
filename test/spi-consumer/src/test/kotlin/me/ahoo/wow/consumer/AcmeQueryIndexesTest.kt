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

package me.ahoo.wow.consumer

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.ExperimentalWowApi
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.query.index.QueryIndexDeclaration
import me.ahoo.wow.query.index.QueryIndexPlan
import me.ahoo.wow.query.memory.InMemoryQueryStorage
import me.ahoo.wow.query.schema.LogicalQuerySchema
import me.ahoo.wow.query.schema.QueryModel
import me.ahoo.wow.query.schema.QueryValueKind
import me.ahoo.wow.query.schema.QueryValueSchema
import me.ahoo.wow.query.schema.QueryValueType
import me.ahoo.wow.query.store.StoreQueryOptions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Acme's indexes over the plan the core makes of an aggregate's declarations. The test builds the plan as the host
 * does, through Wow's internals; Acme's code reads it on the public vocabulary alone.
 */
@OptIn(InternalWowApi::class, ExperimentalWowApi::class)
class AcmeQueryIndexesTest {
    private val type = AggregateType("acme", "order")
    private val queries = checkNotNull(AcmeQueries(InMemoryQueryStorage()).snapshots(StoreQueryOptions.DEFAULT))

    private val logical = LogicalQuerySchema(
        QueryValueSchema(
            QueryValueKind.OBJECT,
            properties = mapOf(
                "state" to QueryValueSchema(
                    QueryValueKind.OBJECT,
                    properties = mapOf(
                        "status" to QueryValueSchema(QueryValueKind.SCALAR, valueTypes = setOf(QueryValueType.STRING)),
                        "tags" to QueryValueSchema(
                            QueryValueKind.ARRAY,
                            items = QueryValueSchema(QueryValueKind.SCALAR, valueTypes = setOf(QueryValueType.STRING)),
                        ),
                    ),
                ),
            ),
        ),
    )

    private fun plan(vararg declarations: QueryIndexDeclaration): QueryIndexPlan {
        val schema = runBlocking { AcmeStorageAdapter.facts(logical) }.compile(QueryModel.SNAPSHOT, logical, null)
        return QueryIndexPlan.of(schema, declarations.toList())
    }

    @Test
    fun `a declared index should be created once and kept`() {
        val status = plan(QueryIndexDeclaration("ix_status", listOf("state.status")))
        queries.manageIndexes(type, status).created.assert().containsExactly("ix_status")
        queries.manageIndexes(type, status).created.assert().isEmpty()
    }

    @Test
    fun `an index over many values should be refused by Acme, an unknown key by the plan`() {
        val tags = plan(QueryIndexDeclaration("ix_tags", listOf("state.tags")))
        assertThrows<IllegalArgumentException> { queries.manageIndexes(type, tags) }
        assertThrows<IllegalArgumentException> { plan(QueryIndexDeclaration("ix_none", listOf("state.none"))) }
    }
}
