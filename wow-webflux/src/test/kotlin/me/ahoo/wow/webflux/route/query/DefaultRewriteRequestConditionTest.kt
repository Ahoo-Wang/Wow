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
        val result = DefaultRewriteRequestCondition.rewrite(
            MOCK_AGGREGATE_METADATA,
            request,
            originalCondition
        )
        result.assert().isSameAs(originalCondition)
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
        result.condition.assert().isNotSameAs(originalCondition)
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
        result.condition.assert().isNotSameAs(originalCondition)
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
        result.condition.assert().isNotSameAs(originalCondition)
    }
}
