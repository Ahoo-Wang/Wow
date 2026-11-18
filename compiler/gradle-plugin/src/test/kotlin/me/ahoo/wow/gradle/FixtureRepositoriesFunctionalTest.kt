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

package me.ahoo.wow.gradle

import me.ahoo.test.asserts.assert
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The fixtures' remote repositories ([FixtureRepositories]) are never asked for a Wow module: a decoy repository,
 * declared as the fixtures declare Maven Central and holding `wow-bom` of the version under test, must not serve it.
 * Each fixture takes Wow's modules as files, so the BOM the plugin adds is the one it resolves by its coordinates.
 */
class FixtureRepositoriesFunctionalTest {
    private val bom = "me.ahoo.wow:wow-bom:${WowPlugin.version}"

    private fun classpath(property: String): String =
        requireNotNull(System.getProperty(property)) { "System property $property is not set." }
            .split(File.pathSeparator)
            .joinToString(", ") { "\"${it.replace("\\", "/")}\"" }

    /** A Maven repository holding `wow-bom` of the version under test, as Maven Central will once it is released. */
    private fun decoy(root: File): String {
        val pom = File(root, "me/ahoo/wow/wow-bom/${WowPlugin.version}/wow-bom-${WowPlugin.version}.pom")
        pom.parentFile.mkdirs()
        pom.writeText(
            "<project><modelVersion>4.0.0</modelVersion><groupId>me.ahoo.wow</groupId><artifactId>wow-bom</artifactId>" +
                "<version>${WowPlugin.version}</version><packaging>pom</packaging></project>\n",
        )
        return root.toURI().toString()
    }

    /** The compile class path of an api module whose repositories are [repositories], as `dependencies` prints it. */
    private fun compileClasspath(root: File, vararg repositories: String): String {
        File(root, "settings.gradle.kts").writeText("rootProject.name = \"api\"\n")
        File(root, "build.gradle.kts").writeText(
            """
            plugins {
                id("org.jetbrains.kotlin.jvm")
                id("${WowPlugin.API_ID}")
            }

            repositories {
                ${repositories.joinToString("\n                ")}
            }

            dependencies {
                implementation(files(${classpath("wow-test.fixture.compileClasspath")}))
                wowTools(files(${classpath("wow-test.fixture.toolsClasspath")}))
            }
            """.trimIndent(),
        )
        return GradleRunner.create()
            .withProjectDir(root)
            .withPluginClasspath()
            .withArguments("dependencies", "--configuration", "compileClasspath", "--stacktrace")
            .forwardOutput()
            .build()
            .output
    }

    @Test
    fun `a fixture's remote repository should not serve a Wow module, even of the version under test`(
        @TempDir root: File,
        @TempDir remote: File,
    ) {
        bomLines(compileClasspath(root, FixtureRepositories.remote(decoy(remote))))
            .assert().isNotEmpty().allMatch { it.endsWith(" FAILED") }
    }

    @Test
    fun `a fixture should take the Wow BOM from the fixture repository the build assembles`(
        @TempDir root: File,
        @TempDir remote: File,
    ) {
        val fixture = """maven { url = uri("${System.getProperty("wow-test.fixture.repository")}") }"""
        bomLines(compileClasspath(root, FixtureRepositories.remote(decoy(remote)), fixture))
            .assert().isNotEmpty().noneMatch { it.endsWith(" FAILED") }
    }

    /** The lines of a `dependencies` report that name the BOM, as `wow-bom:<version> -> <version>[ FAILED]`. */
    private fun bomLines(output: String): List<String> {
        val named = Regex("${Regex.escape(bom)}( |$)")
        return output.lines().map { it.trimEnd() }.filter { named.containsMatchIn(it) }
    }
}
