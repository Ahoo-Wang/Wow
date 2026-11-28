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

package me.ahoo.wow.api.naming

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.nio.file.Path

/**
 * The naming rules of a model (D484 ⑧), held to the table of cases the TypeScript generator's copy of them
 * (`typescript/wow-generator/src/manifest/nameRules.ts`, its `test/manifest/nameRules.test.ts`) reads too: a rule
 * that changes on one side fails the other's test, so the compiler and the two contract generators accept and refuse
 * the same names. The table is written by hand; a case added to it is a case of both.
 */
class NameRulesTest {
    companion object {
        private val TABLE: JsonNode = ObjectMapper().readTree(
            Path.of("..", "..", "typescript", "wow-generator", "test", "fixtures", "name-rules.json").toFile(),
        )

        private fun cases(rule: String, verdict: String): List<String> =
            TABLE.path(rule).path(verdict).values().map { it.asString() }.also { it.assert().isNotEmpty() }
    }

    @Test
    fun `a name should be accepted or refused as the shared table says`() {
        cases("name", "accepted").filterNot(NameRules::isName).assert().isEmpty()
        cases("name", "refused").filter(NameRules::isName).assert().isEmpty()
    }

    @Test
    fun `a code should be accepted or refused as the shared table says`() {
        cases("code", "accepted").filterNot(NameRules::isCode).assert().isEmpty()
        cases("code", "refused").filter(NameRules::isCode).assert().isEmpty()
    }

    @Test
    fun `a name of the framework's own should be accepted or refused as the shared table says`() {
        cases("frameworkName", "accepted").filterNot(NameRules::isFrameworkName).assert().isEmpty()
        cases("frameworkName", "refused").filter(NameRules::isFrameworkName).assert().isEmpty()
    }

    @Test
    fun `the patterns should be spelled as the shared table spells them, and the code's be its segments joined by dots`() {
        NameRules.NAME_PATTERN.assert().isEqualTo(TABLE.path("name").path("pattern").asString())
        NameRules.CODE_SEGMENT_PATTERN.assert().isEqualTo(TABLE.path("code").path("segmentPattern").asString())
        NameRules.FRAMEWORK_NAME_PATTERN.assert().isEqualTo(TABLE.path("frameworkName").path("pattern").asString())
        // The one pattern of a whole code, for a schema to take: it decides as the rule does.
        val code = Regex(NameRules.CODE_PATTERN)
        cases("code", "accepted").filterNot(code::matches).assert().isEmpty()
        cases("code", "refused").filter(code::matches).assert().isEmpty()
    }

    @Test
    fun `the framework's own names should be names of the framework`() {
        // What the rule exempts in a Manifest: the system commands and events, and the built-in subscriptions.
        val own = listOf(
            me.ahoo.wow.api.lifecycle.Lifecycle.COMMANDS.keys,
            me.ahoo.wow.api.lifecycle.Lifecycle.EVENTS.keys,
            me.ahoo.wow.api.schedule.Schedules.COMMANDS.keys,
            me.ahoo.wow.api.schedule.Schedules.EVENTS.keys,
        ).flatten()
        own.assert().isNotEmpty()
        own.filterNot(NameRules::isFrameworkName).assert().isEmpty()
    }

    @Test
    fun `a name's problem should state the rule, and a name should have none`() {
        NameRules.nameProblem("order-item").assert().isNull()
        NameRules.nameProblem("order item").assert().isEqualTo(
            "is not a name: a name starts with a letter and holds only letters, digits, '_' and '-' " +
                "([A-Za-z][A-Za-z0-9_-]*), such as order-item; a dot separates a context, an aggregate and a type, " +
                "and the generated contracts take an identifier from every name",
        )
    }

    @Test
    fun `a code's problem should name the segment at fault, and a code should have none`() {
        val rule = "; a code is segments joined by dots, each starting with a letter or a digit and holding only " +
            "letters, digits, '_' and '-' ([A-Za-z0-9][A-Za-z0-9_-]*), such as shop.cart.out-of-stock"
        NameRules.codeProblem("shop.cart.out-of-stock").assert().isNull()
        NameRules.codeProblem("empty").assert().isNull()
        mapOf(
            "shop.cart." to "its last segment is empty (it ends in a dot)",
            "shop." to "its last segment is empty (it ends in a dot)",
            ".closed" to "its first segment is empty (it starts with a dot)",
            "." to "its first segment is empty (it starts with a dot)",
            "shop..closed" to "its segment 2 is empty (two dots in a row)",
            "shop.cart..x" to "its segment 3 is empty (two dots in a row)",
            "shop.a b" to "its segment \"a b\" is not one",
            "-" to "its segment \"-\" is not one",
            "" to "it is empty",
        ).forEach { (code, fault) ->
            NameRules.codeProblem(code).assert().describedAs(code).isEqualTo("is not a code: $fault$rule")
        }
    }
}
