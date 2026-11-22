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

package me.ahoo.wow.http

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.http.query.HttpQueries
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.manifest.QueryManifest
import me.ahoo.wow.manifest.QueryRoutes
import me.ahoo.wow.manifest.copyWith
import me.ahoo.wow.query.memory.InMemoryQueryStorage
import org.junit.jupiter.api.Test

/** The edges of the query routes: a list without a default, `If-None-Match: *`, a model no route serves. */
class QueryRoutesEdgesTest {
    @Test
    fun `a list naming no limit should be refused by the budget when the default list size is off`() {
        val node = QueryNode(defaultListSize = 0)
        node.save("c-1")
        node.query("""{"kind":"list","filter":{"op":"match-all"}}""")
            .assertQueryProblem(400, "wow.query.invalid")
            .violations().assert().containsExactly(me.ahoo.wow.api.query.QueryErrorCodes.SIZE_OUT_OF_RANGE)
    }

    @Test
    fun `If-None-Match star should match any descriptor`() {
        val node = QueryNode()
        node.describe(headers = mapOf("If-None-Match" to "*")).status.assert().isEqualTo(304)
        node.describe(QueryRoutes.EVENTS, headers = mapOf("If-None-Match" to "*")).status.assert().isEqualTo(304)
    }

    @Test
    fun `a model the routes do not know should have no route`() {
        val manifest = queryManifest().let { manifest ->
            manifest.copyWith(
                contexts = manifest.contexts.map { context ->
                    context.copyWith(
                        aggregates = context.aggregates.map {
                            val projection = QueryManifest("projection", QueryRoutes.of(CONTEXT, "cart", "projection"))
                            it.copyWith(queries = it.queries + projection)
                        },
                    )
                },
            )
        }
        val storage = InMemoryQueryStorage()
        val node = HttpNode(manifest = manifest) { log ->
            HttpQueries(listOf(cartSnapshots(storage)), listOf(cartEvents(log)), displayShared = true)
        }
        node.post("/shop/cart/projection/query", """{"kind":"list","filter":{"op":"match-all"}}""")
            .status.assert().isEqualTo(404)
        node.post("/shop/cart/snapshot/query", """{"kind":"list","filter":{"op":"match-all"}}""")
            .status.assert().isEqualTo(200)
    }

    @Test
    fun `the queries should hold one gateway per aggregate type`() {
        val storage = InMemoryQueryStorage()
        val log = InMemoryEventLog()
        assertThrownBy<IllegalArgumentException> { HttpQueries(defaultListSize = -1, displayShared = true) }
        // No query served, nothing shared: a construction states whether it shares, and the empty one does not.
        HttpQueries.NONE.displayShared.assert().isFalse()
        assertThrownBy<IllegalArgumentException> {
            HttpQueries(listOf(cartSnapshots(storage), cartSnapshots(storage)), displayShared = true)
        }
        assertThrownBy<IllegalArgumentException> {
            HttpQueries(events = listOf(cartEvents(log), cartEvents(log)), displayShared = true)
        }
        val snapshots = cartSnapshots(storage)
        assertThrownBy<IllegalArgumentException> {
            HttpQueries(policies = listOf(snapshots, cartSnapshots(storage)), displayShared = true)
        }
        // A policy gateway is for a type whose snapshots are not queried (review R3.3-3).
        assertThrownBy<IllegalArgumentException> {
            HttpQueries(listOf(snapshots), policies = listOf(snapshots), displayShared = true)
        }
        HttpQueries(listOf(snapshots), displayShared = true).visibility(cartType).assert().isSameAs(snapshots)
        HttpQueries(
            policies = listOf(snapshots),
            displayShared = true
        ).visibility(cartType).assert().isSameAs(snapshots)
        HttpQueries.NONE.snapshots(cartType).assert().isNull()
        HttpQueries.NONE.events(cartType).assert().isNull()
        HttpQueries.NONE.visibility(cartType).assert().isNull()
    }
}
