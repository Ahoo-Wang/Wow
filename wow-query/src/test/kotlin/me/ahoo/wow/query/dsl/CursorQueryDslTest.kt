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

package me.ahoo.wow.query.dsl

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.CursorPage
import me.ahoo.wow.api.query.CursorQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.MaterializedSnapshot
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.query.snapshot.toStateCursorPage
import me.ahoo.wow.query.snapshot.toStateDocumentCursorPage
import me.ahoo.wow.serialization.toJsonNode
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import tools.jackson.databind.node.ObjectNode

class CursorQueryDslTest {
    @Test
    fun `should build cursor query with all components`() {
        val query = cursorQuery {
            filter { id("id") }
            projection { include("state.value") }
            sort { "version".desc() }
            size(20)
            cursor("cursor")
        }

        query.filter.assert().isNotEqualTo(MatchAllFilter)
        query.projection.assert().isEqualTo(Projection(include = listOf(QueryField("state.value"))))
        query.sort.assert().containsExactly(Sort(QueryField("version"), Sort.Direction.DESC))
        query.size.assert().isEqualTo(20)
        query.cursor.assert().isEqualTo("cursor")
    }

    @Test
    fun `should build cursor query with defaults`() {
        val query = cursorQuery { }

        query.filter.assert().isSameAs(MatchAllFilter)
        query.projection.assert().isEqualTo(Projection.ALL)
        query.sort.assert().isEmpty()
        query.size.assert().isEqualTo(CursorQuery.DEFAULT_SIZE)
        query.cursor.assert().isNull()
    }

    @Test
    fun `state cursor conversions should preserve next cursor`() {
        val snapshot = MaterializedSnapshot(
            contextName = "context",
            aggregateName = "aggregate",
            tenantId = "tenant",
            aggregateId = "id",
            version = 1,
            eventId = "event",
            firstOperator = "operator",
            operator = "operator",
            firstEventTime = 1,
            eventTime = 1,
            state = "state",
            snapshotTime = 1,
            deleted = false,
        )
        val document = """{"state":{"value":"state"}}""".toJsonNode<ObjectNode>()

        Mono.just(CursorPage(listOf(snapshot), "next")).toStateCursorPage().block()!!.let { page ->
            page.list.assert().containsExactly("state")
            page.nextCursor.assert().isEqualTo("next")
        }
        Mono.just(CursorPage(listOf(document), "next")).toStateDocumentCursorPage().block()!!.let { page ->
            page.list.single().path("value").stringValue().assert().isEqualTo("state")
            page.nextCursor.assert().isEqualTo("next")
        }
    }
}
