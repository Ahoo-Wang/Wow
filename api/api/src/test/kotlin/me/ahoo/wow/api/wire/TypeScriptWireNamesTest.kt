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

package me.ahoo.wow.api.wire

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.Ownership
import me.ahoo.wow.api.query.AggregationDatePart
import me.ahoo.wow.api.query.AggregationDateUnit
import me.ahoo.wow.api.query.AggregationExpressionOperator
import me.ahoo.wow.api.query.AggregationFunction
import me.ahoo.wow.api.query.AggregationGroupScope
import me.ahoo.wow.api.query.ComparisonOperator
import me.ahoo.wow.api.query.DateDiffUnit
import me.ahoo.wow.api.query.DeletionState
import me.ahoo.wow.api.query.FilterOperator
import me.ahoo.wow.api.query.PagingMode
import me.ahoo.wow.api.query.QueryValueKind
import me.ahoo.wow.api.query.RunningFunction
import me.ahoo.wow.api.query.SearchMode
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.StringComparison
import me.ahoo.wow.api.query.TotalMode
import me.ahoo.wow.api.query.WindowFunction
import me.ahoo.wow.api.query.annotation.SensitivityLevel
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.api.result.Stage
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * The wire names of the Wow enumerations that `typescript/wow-client` declares by hand, which its
 * `test/wireNames.test.ts` holds the client's values to: it fails when the committed fixture no longer says what
 * [WireNames] writes. A value renamed here changes the fixture, and the client then fails until it follows, so a
 * server and a client that spell a value apart (a page comparing `SUCCEEDED` to a server writing `succeeded`) are
 * caught in the unit tests of both languages rather than in a browser. The Manifest's enumerations are not listed:
 * the client's Manifest types are generated from the Manifest's schema and held to it there.
 *
 * Regenerate the fixture, never edit it by hand:
 * `./gradlew :api:test --tests "*TypeScriptWireNamesTest" --rerun -Dwow-test.snapshot.update=true`.
 */
class TypeScriptWireNamesTest {
    companion object {
        private val FIXTURE: Path =
            Path.of("..", "..", "typescript", "wow-client", "test", "fixtures", "wire-names.json")

        /** The enumerations the client declares: those of a command's result, then the query protocol's. */
        private val ENUMERATIONS: List<Class<out Enum<*>>> = listOf(
            CommandResult.Status::class.java,
            Stage::class.java,
            FunctionKind::class.java,
            Ownership::class.java,
            FilterOperator::class.java,
            StringComparison::class.java,
            SearchMode::class.java,
            DeletionState::class.java,
            Sort.Direction::class.java,
            TotalMode::class.java,
            PagingMode::class.java,
            QueryValueKind::class.java,
            AggregationDatePart::class.java,
            AggregationDateUnit::class.java,
            DateDiffUnit::class.java,
            AggregationExpressionOperator::class.java,
            WindowFunction::class.java,
            RunningFunction::class.java,
            ComparisonOperator::class.java,
            AggregationFunction::class.java,
            AggregationGroupScope::class.java,
            SensitivityLevel::class.java,
        )

        /**
         * The values only a reader produces, for one a newer writer sent: the client reads such a value as any it
         * does not list (`| (string & {})`), so it declares none.
         */
        private val READER_ONLY: Set<Enum<*>> =
            setOf(CommandResult.Status.UNKNOWN, Stage.UNKNOWN, FunctionKind.UNKNOWN)

        /** `CommandResult.Status` for a nested enumeration, its simple name otherwise. */
        private fun nameOf(type: Class<out Enum<*>>): String = type.name.substringAfterLast('.').replace('$', '.')

        private fun render(): String {
            val entries = ENUMERATIONS.joinToString(",\n") { type ->
                val values = type.enumConstants.filterNot { it in READER_ONLY }
                    .joinToString(", ") { "\"${WireNames.of(it)}\"" }
                "    \"${nameOf(type)}\": [$values]"
            }
            return buildString {
                append("{\n")
                append("  \"source\": \"api TypeScriptWireNamesTest: WireNames.of each constant a writer writes\",\n")
                append("  \"enumerations\": {\n")
                append(entries)
                append("\n  }\n}\n")
            }
        }
    }

    @Test
    fun `the fixture should hold the wire names of the enumerations the TypeScript client declares`() {
        val actual = render()
        if (System.getProperty("wow-test.snapshot.update").equals("true", ignoreCase = true)) {
            FIXTURE.writeText(actual)
        }
        FIXTURE.readText().assert()
            .describedAs(
                "Regenerate $FIXTURE with ./gradlew :api:test --tests \"*TypeScriptWireNamesTest\" --rerun " +
                    "-Dwow-test.snapshot.update=true, then make typescript/wow-client follow until its wireNames.test.ts passes.",
            )
            .isEqualTo(actual)
    }
}
