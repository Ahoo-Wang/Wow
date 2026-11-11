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

package me.ahoo.wow.compiler.model

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import me.ahoo.test.asserts.assert
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.Test

/**
 * The system types without fields have no companion object, so a bare name is no value: a use that forgot its
 * parentheses does not compile, where with a companion it would compile as that object and be wrong at run time.
 */
@OptIn(ExperimentalCompilerApi::class)
class BareSystemTypeTest {
    private val names = listOf(
        "DeleteAggregate",
        "RestoreAggregate",
        "AggregateDeleted",
        "AggregateRestored",
        "DisableJob",
        "TickJob",
        "JobDisabled",
    )

    private fun compile(uses: String) = KotlinCompilation().apply {
        inheritClassPath = true
        jvmTarget = "21"
        sources = listOf(
            SourceFile.kotlin(
                "Uses.kt",
                "import me.ahoo.wow.api.lifecycle.*\nimport me.ahoo.wow.api.schedule.*\n\n$uses\n",
            ),
        )
    }.compile()

    @Test
    fun `a system type's bare name does not compile as a value, nor as a when branch, and its constructor does`() {
        val bare = compile(
            names.joinToString("\n") { "val bare$it: Any = $it" } +
                "\nfun branch(body: Any) = when (body) {\n    DeleteAggregate -> 1\n    else -> 0\n}",
        )
        bare.exitCode.assert().isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR)
        // Each bare name is refused, the compiler naming the classifier that has no companion; the when branch too.
        names.forEach { name -> bare.messages.assert().contains("'class $name ").contains("does not have a companion") }
        bare.messages.lines().count { "does not have a companion" in it }.assert().isGreaterThan(names.size)
        val built = compile(names.joinToString("\n") { "val built$it: Any = $it()" })
        built.exitCode.assert().withFailMessage { built.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
    }
}
