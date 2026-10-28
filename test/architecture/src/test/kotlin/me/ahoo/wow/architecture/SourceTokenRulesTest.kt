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

package me.ahoo.wow.architecture

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** The rules over what an edge's code does ([SourceTokenRules.ALL]). */
class SourceTokenRulesTest {
    private val repositoryRoot: File =
        File(requireNotNull(System.getProperty(REPOSITORY_ROOT)) { "System property $REPOSITORY_ROOT is not set." })

    @TestFactory
    fun `production sources should follow the source token rules`(): List<DynamicTest> =
        SourceTokenRules.ALL.map { rule ->
            DynamicTest.dynamicTest(rule.name) {
                rule.violations(repositoryRoot).assert().describedAs("Rule violated: ${rule.name}").isEmpty()
            }
        }

    @Test
    fun `a source token rule should catch a member read in code, not in comments or longer names`(@TempDir root: File) {
        File(root, "${ArchitectureRules.HTTP.dir}/src/main/kotlin/Edge.kt").apply {
            parentFile.mkdirs()
            writeText(
                """
                // caller.crossScope decides nothing here
                /** [Caller.crossScope] */
                fun a(caller: Caller) = caller.crossScope
                fun b(caller: Caller) = caller.crossScopeAware
                fun c(type: Type) = tenants.fixedTenant(type)
                """.trimIndent(),
            )
        }
        SourceTokenRules.HTTP_DECIDES_NO_READ_SCOPE.violations(root).assert().containsExactly(
            "${ArchitectureRules.HTTP.dir}/src/main/kotlin/Edge.kt:3: crossScope",
            "${ArchitectureRules.HTTP.dir}/src/main/kotlin/Edge.kt:5: fixedTenant",
        )
    }

    @Test
    fun `a backend should name a ddl mode only as a parameter's default`(@TempDir root: File) {
        File(root, "${ArchitectureRules.POSTGRES.dir}/src/main/kotlin/Store.kt").apply {
            parentFile.mkdirs()
            writeText(
                """
                /** Creates under [Ddl.AUTO]. */
                class Store(private val ddl: Ddl = Ddl.AUTO) {
                    fun a() = if (ddl == Ddl.VALIDATE) 1 else 2
                    fun b() = ddl.ensure("t", create = {}) { true }
                    fun c() = when (ddl) { Ddl.NONE -> Unit else -> Unit }
                }
                """.trimIndent(),
            )
        }
        SourceTokenRules.BACKENDS_BRANCH_NO_DDL_MODE.violations(root).assert().containsExactly(
            "${ArchitectureRules.POSTGRES.dir}/src/main/kotlin/Store.kt:3: Ddl.VALIDATE",
            "${ArchitectureRules.POSTGRES.dir}/src/main/kotlin/Store.kt:5: Ddl.NONE",
        )
    }

    @Test
    fun `the UNKNOWN rule should catch a source that produces one, not a branch that handles one`(@TempDir root: File) {
        File(root, "${ArchitectureRules.KERNEL.dir}/src/main/kotlin/Results.kt").apply {
            parentFile.mkdirs()
            writeText(
                """
                fun a(result: CommandResult) = when (result.status) {
                    CommandResult.Status.UNKNOWN -> 1
                    else -> 2
                }
                fun b() = result.copyWith(status = CommandResult.Status.UNKNOWN)
                fun d() = result.status == CommandResult.Status.UNKNOWN
                fun c() = Hop(Stage.UNKNOWN)
                """.trimIndent(),
            )
        }
        SourceTokenRules.NO_WRITER_PRODUCES_UNKNOWN.violations(root).assert().containsExactly(
            "${ArchitectureRules.KERNEL.dir}/src/main/kotlin/Results.kt:5: Status.UNKNOWN",
            "${ArchitectureRules.KERNEL.dir}/src/main/kotlin/Results.kt:7: Stage.UNKNOWN",
        )
    }

    private companion object {
        const val REPOSITORY_ROOT = "wow.repository.root"
    }
}
