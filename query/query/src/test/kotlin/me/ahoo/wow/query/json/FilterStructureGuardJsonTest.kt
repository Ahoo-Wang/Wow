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

package me.ahoo.wow.query.json

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.FieldPredicate
import me.ahoo.wow.api.query.QueryField
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode

/** The JSON half of 9.x `FilterStructureGuardTest`: `withField` changes the written `field` and nothing else. */
class FilterStructureGuardJsonTest {
    private val mapper = QueryJson.mapper
    private val otherField = QueryField("other.path")

    @TestFactory
    fun `withField should replace only the field`(): List<DynamicTest> =
        QueryJsonGoldenCases.filterCases().map { it.second }.filterIsInstance<FieldPredicate>().map { predicate ->
            DynamicTest.dynamicTest(predicate.operator.name) {
                val moved = predicate.withField(otherField)
                val expected = mapper.valueToTree<ObjectNode>(predicate).put("field", otherField.path)
                mapper.valueToTree<JsonNode>(moved).assert().isEqualTo(expected)
            }
        }
}
