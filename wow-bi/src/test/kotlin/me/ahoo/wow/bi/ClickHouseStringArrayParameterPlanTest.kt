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

package me.ahoo.wow.bi

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

class ClickHouseStringArrayParameterPlanTest {
    @Test
    fun `should split array literals below the safe HTTP form field size`() {
        val values = List(10_000) { index -> "desired_${index.toString().padStart(5, '0')}" }

        val plan = ClickHouseStringArrayParameterPlan.create(
            expression = "name",
            parameterName = "databaseTables",
            values = values,
        )

        plan.parameters.assert().hasSize(3)
        plan.parameters.keys.assert().containsExactly("databaseTables", "databaseTables1", "databaseTables2")
        plan.parameters.values.all { value ->
            value.toByteArray(Charsets.UTF_8).size <= ClickHouseStringArrayParameterPlan.MAX_PARAMETER_BYTES
        }.assert().isTrue()
        plan.parameters.values.joinToString().assert().contains("desired_00000", "desired_09999")
        plan.predicate.assert().contains(
            "name IN {databaseTables:Array(String)}",
            "name IN {databaseTables1:Array(String)}",
            "name IN {databaseTables2:Array(String)}",
            " OR ",
        )
        plan.arrayExpression.assert().contains(
            "arrayConcat(",
            "{databaseTables:Array(String)}",
            "{databaseTables2:Array(String)}",
        )
    }

    @Test
    fun `should preserve an empty array parameter and escape string literals`() {
        val emptyPlan = ClickHouseStringArrayParameterPlan.create("name", "names", emptyList())
        emptyPlan.predicate.assert().isEqualTo("name IN {names:Array(String)}")
        emptyPlan.parameters.assert().isEqualTo(mapOf("names" to "[]"))
        emptyPlan.arrayExpression.assert().isEqualTo("{names:Array(String)}")

        val escapedPlan = ClickHouseStringArrayParameterPlan.create(
            expression = "name",
            parameterName = "names",
            values = listOf("quote'", "slash\\", "你好"),
        )
        escapedPlan.parameters["names"].assert().isEqualTo("['quote''', 'slash\\\\', '你好']")
    }
}
