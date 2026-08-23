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

package me.ahoo.wow.webflux.route.query

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.LogicalField
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.RewritableCondition
import me.ahoo.wow.api.query.SearchFilter
import me.ahoo.wow.api.query.toCondition
import me.ahoo.wow.api.query.toFilterExpression
import me.ahoo.wow.openapi.CommonComponent
import me.ahoo.wow.openapi.aggregate.command.CommandComponent
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.junit.jupiter.api.Test
import org.springframework.mock.web.reactive.function.server.MockServerRequest

class DefaultRewriteRequestConditionTest {

    @Test
    fun `should not rewrite condition when no tenant or owner headers`() {
        val request = MockServerRequest.builder().build()
        val originalCondition = Condition("id")
        val originalFilter = originalCondition.toFilterExpression()
        val result = DefaultRewriteRequestCondition.rewrite(
            MOCK_AGGREGATE_METADATA,
            request,
            originalFilter
        )
        result.assert().isSameAs(originalFilter)
    }

    @Test
    fun `should append tenant condition from header`() {
        val tenantId = "tenant-123"
        val request = MockServerRequest.builder()
            .header(CommandComponent.Header.TENANT_ID, tenantId)
            .build()
        val originalCondition = Condition("id")
        val result = DefaultRewriteRequestCondition.rewrite(
            MOCK_AGGREGATE_METADATA,
            request,
            ListQuery(condition = originalCondition)
        )
        result.filter.assert().isNotSameAs(originalCondition.toFilterExpression())
    }

    @Test
    fun `should append owner condition from path variable`() {
        val ownerId = "owner-123"
        val request = MockServerRequest.builder()
            .pathVariable(MessageRecords.OWNER_ID, ownerId)
            .build()
        val originalCondition = Condition("id")
        val result = DefaultRewriteRequestCondition.rewrite(
            MOCK_AGGREGATE_METADATA,
            request,
            ListQuery(condition = originalCondition)
        )
        result.filter.assert().isNotSameAs(originalCondition.toFilterExpression())
    }

    @Test
    fun `should append space condition from header`() {
        val spaceId = "space-123"
        val request = MockServerRequest.builder()
            .header(CommonComponent.Header.SPACE_ID, spaceId)
            .build()
        val originalCondition = Condition("id")
        val result = DefaultRewriteRequestCondition.rewrite(
            MOCK_AGGREGATE_METADATA,
            request,
            ListQuery(condition = originalCondition)
        )
        result.filter.assert().isNotSameAs(originalCondition.toFilterExpression())
    }

    @Test
    fun `generic query rewrite should preserve typed search fields`() {
        val search = SearchFilter(
            query = "wow",
            fields = linkedSetOf(LogicalField("state.name"), LogicalField("state.description")),
        )

        val rewritten = DefaultRewriteRequestCondition.rewrite(
            MOCK_AGGREGATE_METADATA,
            MockServerRequest.builder().build(),
            ListQuery(search),
        )

        rewritten.filter.assert().isEqualTo(search)
    }

    @Suppress("DEPRECATION")
    @Test
    fun `new filter rewrite should delegate to a legacy implementation`() {
        val legacy = object : RewriteRequestCondition {
            override fun <Q : RewritableCondition<Q>> rewrite(
                aggregateMetadata: me.ahoo.wow.modeling.metadata.AggregateMetadata<*, *>,
                request: org.springframework.web.reactive.function.server.ServerRequest,
                rewritableCondition: Q,
            ): Q = rewritableCondition.appendCondition(Condition.eq("legacy", true))
        }

        val rewritten = legacy.rewrite(
            MOCK_AGGREGATE_METADATA,
            MockServerRequest.builder().build(),
            MatchAllFilter,
        )

        rewritten.toCondition().assert().isEqualTo(Condition.eq("legacy", true))
    }
}
