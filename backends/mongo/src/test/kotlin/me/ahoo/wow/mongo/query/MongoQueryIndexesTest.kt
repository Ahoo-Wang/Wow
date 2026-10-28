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

package me.ahoo.wow.mongo.query

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.query.index.QueryIndexDeclaration
import me.ahoo.wow.query.index.QueryIndexPlan
import me.ahoo.wow.spi.Ddl
import org.junit.jupiter.api.Test

class MongoQueryIndexesTest {
    private val schema = MongoQueryTestSchemas.SCHEMA

    private fun plan(vararg declarations: QueryIndexDeclaration): List<MongoQueryIndex> =
        MongoQueryIndexes.plan("shop", "order", QueryIndexPlan.of(schema, declarations.toList()))

    @Test
    fun `the scope indexes and each declared one lead with the tenant over the snapshots not deleted`() {
        val indexes =
            plan(QueryIndexDeclaration("ix_order_state_name", listOf("state.name", "ownerId", "state.amount")))
        indexes.map { it.toString() }.assert().containsExactly(
            """wow_snapshot__shop__order.owner_ix {"tenant_id": 1, "owner_id": 1} partialFilterExpression {"deleted": false}""",
            """wow_snapshot__shop__order.space_ix {"tenant_id": 1, "space_id": 1} partialFilterExpression {"deleted": false}""",
            """wow_snapshot__shop__order.ix_order_state_name """ +
                """{"tenant_id": 1, "state.name": 1, "owner_id": 1, "state.amount": 1} partialFilterExpression {"deleted": false}""",
        )
        indexes.last().collection.assert().isEqualTo("wow_snapshot__shop__order")
        // The tenant leads once; a multi-valued key is indexed (multikey) when it is the only one.
        plan(QueryIndexDeclaration("ix_keywords", listOf("tenantId", "state.keywords"))).last().keys
            .assert().containsExactly("tenant_id", "state.keywords")
    }

    @Test
    fun `a key or a name the collection cannot take is refused`() {
        fun refuses(vararg declarations: QueryIndexDeclaration) =
            assertThrownBy<IllegalArgumentException> { plan(*declarations) }
        refuses(QueryIndexDeclaration("ix", listOf("state.unknown")))
        refuses(QueryIndexDeclaration("ix", listOf("state.issuedAt")))
        refuses(QueryIndexDeclaration("ix", listOf("state.keywords", "state.scores")))
        refuses(QueryIndexDeclaration("owner_ix", listOf("state.name")))
        refuses(QueryIndexDeclaration("_id_", listOf("state.name")))
        refuses(QueryIndexDeclaration("ix", listOf("state.name")), QueryIndexDeclaration("ix", listOf("state.status")))
        assertThrownBy<IllegalArgumentException> { QueryIndexDeclaration("", listOf("state.name")) }
        assertThrownBy<IllegalArgumentException> { QueryIndexDeclaration("ix", emptyList()) }
    }

    @Test
    fun `the options default to managing the indexes`() {
        MongoQueryOptions().indexDdl.assert().isEqualTo(Ddl.AUTO)
        MongoQueryOptions().fetchSize.assert().isEqualTo(MongoQueryOptions.DEFAULT_FETCH_SIZE)
        assertThrownBy<IllegalArgumentException> { MongoQueryOptions(fetchSize = 0) }
    }
}
