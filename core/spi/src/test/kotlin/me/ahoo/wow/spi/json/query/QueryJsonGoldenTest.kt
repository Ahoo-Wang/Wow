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

package me.ahoo.wow.spi.json.query

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.FilterOperator
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import tools.jackson.databind.JsonNode

/**
 * Holds the codec to the golden wire file, `json/query-golden.tsv`: every value writes exactly its line, and its line
 * reads back to an equal value.
 */
class QueryJsonGoldenTest {
    private val golden: Map<String, String> = requireNotNull(javaClass.getResource("/json/query-golden.tsv"))
        .readText()
        .lineSequence()
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .map { it.split('\t', limit = 2) }
        .associate { (name, json) -> name to json }

    @Test
    fun `the golden file should hold exactly the named values`() {
        val names = QueryJsonGoldenCases.all().map { it.first }
        names.distinct().assert().hasSize(names.size)
        golden.keys.assert().containsExactlyInAnyOrderElementsOf(names)
    }

    @Test
    fun `every filter operator should have a golden filter`() {
        val cases = QueryJsonGoldenCases.filterCases()
        cases.forEach { (name, filter) -> filter.operator.name.assert().isEqualTo(name) }
        cases.map { it.first }.assert().containsExactlyElementsOf(FilterOperator.entries.map { it.name })
    }

    @Test
    fun `the protocol's vocabulary should be lower-kebab, and a domain's values as written`() {
        val values = golden.values.flatMap { protocolValues(QueryProtocolJson.mapper.readTree(it)) }
        values.map { it.first }.distinct().assert().containsAll(PROTOCOL_KEYS)
        values.filterNot { (_, value) -> KEBAB.matches(value) }.assert().isEmpty()
        // A filter's operand is the domain's: an enum value travels as the domain writes it.
        golden.getValue("EQ").assert().contains("\"PAID\"")
    }

    private fun protocolValues(node: JsonNode): List<Pair<String, String>> = when {
        node.isObject -> node.properties().flatMap { (key, value) ->
            if (key in PROTOCOL_KEYS && value.isString) listOf(key to value.asString()) else protocolValues(value)
        }

        node.isArray -> node.values().flatMap(::protocolValues)
        else -> emptyList()
    }

    @TestFactory
    fun `every value should write its golden JSON`(): List<DynamicTest> = QueryJsonGoldenCases.all().map { (name, value) ->
        DynamicTest.dynamicTest(name) {
            QueryProtocolJson.mapper.writeValueAsString(value).assert().isEqualTo(golden.getValue(name))
        }
    }

    @TestFactory
    fun `every value should read back from its golden JSON`(): List<DynamicTest> =
        QueryJsonGoldenCases.all().map { (name, value) ->
            DynamicTest.dynamicTest(name) {
                val type = if (value is FilterExpression) FilterExpression::class.java else value.javaClass
                QueryProtocolJson.mapper.readValue(golden.getValue(name), type).assert().isEqualTo(value)
            }
        }

    private companion object {
        val KEBAB = Regex("[a-z][a-z0-9]*(-[a-z0-9]+)*")

        /** The keys whose values are the protocol's own words; a JDK `timeUnit` and a domain's operands are not. */
        val PROTOCOL_KEYS = setOf(
            "op", "type", "operator", "comparison", "direction", "function", "mode", "part", "period", "scope",
            "state", "stringComparison", "total", "unit",
        )
    }
}
