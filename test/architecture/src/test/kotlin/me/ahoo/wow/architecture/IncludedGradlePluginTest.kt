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
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The included build of the Gradle plugin (build-logic/wow-gradle-plugin), through which the repository's own projects
 * apply it, is :gradle-plugin's sources compiled once more and nothing else: no source of its own, the same plugin ids
 * and classes, so it can never become a fork of the plugin a release publishes.
 */
class IncludedGradlePluginTest {
    private val repositoryRoot: File =
        File(requireNotNull(System.getProperty(REPOSITORY_ROOT)) { "System property $REPOSITORY_ROOT is not set." })
    private val included = File(repositoryRoot, INCLUDED)

    @Test
    fun `the included build should compile the plugin's sources and have none of its own`() {
        File(included, "src").exists().assert().describedAs("$INCLUDED/src").isFalse()
        val build = File(included, "build.gradle.kts").readText()
        SOURCE_DIR.findAll(build).map { it.groupValues[1] to it.groupValues[2] }.toList().assert()
            .containsExactlyInAnyOrder("kotlin" to "src/main/kotlin", "resources" to "src/main/resources")
        build.assert().contains("val plugin = rootDir.resolve(\"../../$PLUGIN\")")
        File(included, "settings.gradle.kts").readText().assert().doesNotContain("include(")
    }

    @Test
    fun `the included build should declare the published plugin's ids and classes`() {
        pluginsOf(File(included, "build.gradle.kts")).assert()
            .isEqualTo(pluginsOf(File(repositoryRoot, "$PLUGIN/build.gradle.kts")))
            .isNotEmpty()
    }

    @Test
    fun `the build should include the plugin's build for its plugins`() {
        File(repositoryRoot, "settings.gradle.kts").readText().assert()
            .contains("pluginManagement {\n    includeBuild(\"$INCLUDED\")\n}")
    }

    /** Each plugin a build script declares, its id to its implementation class. */
    private fun pluginsOf(buildScript: File): Map<String, String> =
        PLUGIN_DECLARATION.findAll(buildScript.readText()).associate { it.groupValues[1] to it.groupValues[2] }

    private companion object {
        const val REPOSITORY_ROOT = "wow-test.repository.root"
        const val INCLUDED = "build-logic/wow-gradle-plugin"
        const val PLUGIN = "compiler/gradle-plugin"
        val SOURCE_DIR = Regex("""(kotlin|resources)\.srcDir\(plugin\.resolve\("([^"]+)"\)\)""")
        val PLUGIN_DECLARATION = Regex("""id = "([\w.]+)"\s+implementationClass = "([\w.]+)"""")
    }
}
