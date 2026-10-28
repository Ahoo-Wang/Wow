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

/** The main code's project dependencies, narrower than the module graph's (`ALLOWED_MAIN_PROJECT_DEPENDENCIES`). */
class MainProjectDependenciesTest {
    private val repositoryRoot: File =
        File(requireNotNull(System.getProperty(REPOSITORY_ROOT)) { "System property $REPOSITORY_ROOT is not set." })

    @TestFactory
    fun `main code should depend only on the projects its narrower rule allows`(): List<DynamicTest> =
        ArchitectureRules.ALLOWED_MAIN_PROJECT_DEPENDENCIES.map { (module, allowed) ->
            DynamicTest.dynamicTest(module.name) {
                ArchitectureRules.mainProjectDependencies(repositoryRoot, module).minus(allowed).assert()
                    .describedAs("${module.name}'s main code depends on projects outside $allowed")
                    .isEmpty()
            }
        }

    @Test
    fun `main project dependencies should leave out the test configurations`(@TempDir root: File) {
        File(root, ArchitectureRules.HTTP.dir).apply { mkdirs() }.resolve("build.gradle.kts").writeText(
            """
            dependencies {
                api(project(":runtime"))
                implementation(project(":openapi"))
                testImplementation(project(":client"))
                integrationTestImplementation(project(":manifest"))
            }
            """.trimIndent(),
        )
        ArchitectureRules.mainProjectDependencies(root, ArchitectureRules.HTTP).assert()
            .containsExactlyInAnyOrder("runtime", "openapi")
    }

    private companion object {
        const val REPOSITORY_ROOT = "wow.repository.root"
    }
}
