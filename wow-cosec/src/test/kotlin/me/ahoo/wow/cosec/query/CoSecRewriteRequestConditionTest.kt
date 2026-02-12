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

package me.ahoo.wow.cosec.query

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.Operator
import me.ahoo.wow.cosec.extractor.CoSecCommandBuilderExtractor.SPACE_ID_KEY
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.query.dsl.condition
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.junit.jupiter.api.Test
import org.springframework.mock.web.reactive.function.server.MockServerRequest

class CoSecRewriteRequestConditionTest {

    @Test
    fun resolveSpaceId() {
        val spaceId = generateGlobalId()
        val request = MockServerRequest.builder().header(SPACE_ID_KEY, spaceId).build()
        val condition = CoSecRewriteRequestCondition.rewrite(MOCK_AGGREGATE_METADATA, request, condition { })
        condition.assert().isNotNull()
        condition.operator.assert().isEqualTo(Operator.SPACE_ID)
        condition.value.assert().isEqualTo(spaceId)
    }
}
