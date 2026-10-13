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
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.StateManifest
import org.junit.jupiter.api.Test

class MongoQueryIndexesTest {
    private val schema = MongoQueryTestSchemas.SCHEMA

    private fun plan(vararg declarations: QueryIndexDeclaration): List<MongoQueryIndex> =
        MongoQueryIndexes.plan("shop", "order", schema, declarations.toList())

    @Test
    fun `the scope indexes and each declared one lead with the tenant over the snapshots not deleted`() {
        val indexes = plan(QueryIndexDeclaration("ix_order_state_name", listOf("state.name", "ownerId", "state.amount")))
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
        refuses(QueryIndexDeclaration("snapshot_key", listOf("state.name")))
        refuses(QueryIndexDeclaration("_id_", listOf("state.name")))
        refuses(QueryIndexDeclaration("ix", listOf("state.name")), QueryIndexDeclaration("ix", listOf("state.status")))
        assertThrownBy<IllegalArgumentException> { QueryIndexDeclaration("", listOf("state.name")) }
        assertThrownBy<IllegalArgumentException> { QueryIndexDeclaration("ix", emptyList()) }
    }

    @Test
    fun `declarations are read from the state schema of the Manifest`() {
        fun manifest(state: Map<String, Any?>) = Manifest(
            contexts = listOf(
                ContextManifest(
                    "shop",
                    aggregates = listOf(AggregateManifest("order", state = StateManifest("#/\$defs/Order"))),
                ),
            ),
            defs = mapOf("Order" to state),
        )
        val declared = manifest(
            mapOf(
                QueryIndexDeclaration.KEYWORD to listOf(
                    mapOf("name" to "ix_order_state_name", "keys" to listOf("state.name", "ownerId")),
                ),
            ),
        )
        QueryIndexDeclaration.of(declared, "shop", "order").assert()
            .containsExactly(QueryIndexDeclaration("ix_order_state_name", listOf("state.name", "ownerId")))
        QueryIndexDeclaration.of(declared, "shop", "cart").assert().isEmpty()
        QueryIndexDeclaration.of(declared, "other", "order").assert().isEmpty()
        QueryIndexDeclaration.of(manifest(emptyMap()), "shop", "order").assert().isEmpty()
        QueryIndexDeclaration.of(Manifest(contexts = declared.contexts), "shop", "order").assert().isEmpty()
        listOf<Any>(
            "ix",
            listOf("ix"),
            listOf(mapOf("name" to "ix")),
            listOf(mapOf("name" to "ix", "keys" to listOf(1))),
            listOf(mapOf("name" to 1, "keys" to listOf("state.name"))),
        ).forEach { malformed ->
            assertThrownBy<IllegalArgumentException> {
                QueryIndexDeclaration.of(manifest(mapOf(QueryIndexDeclaration.KEYWORD to malformed)), "shop", "order")
            }
        }
    }

    @Test
    fun `the index mode is read from its setting`() {
        QueryIndexDdl.of("AUTO").assert().isEqualTo(QueryIndexDdl.AUTO)
        QueryIndexDdl.of("validate").assert().isEqualTo(QueryIndexDdl.VALIDATE)
        QueryIndexDdl.of("none").assert().isEqualTo(QueryIndexDdl.NONE)
        assertThrownBy<IllegalArgumentException> { QueryIndexDdl.of("create") }
        MongoQueryOptions().indexDdl.assert().isEqualTo(QueryIndexDdl.AUTO)
        MongoQueryOptions().fetchSize.assert().isEqualTo(MongoQueryOptions.DEFAULT_FETCH_SIZE)
        assertThrownBy<IllegalArgumentException> { MongoQueryOptions(fetchSize = 0) }
    }
}
