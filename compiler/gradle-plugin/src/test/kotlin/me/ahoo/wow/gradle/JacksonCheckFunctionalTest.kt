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
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The Jackson gate on a domain module built with the plugin (Gradle TestKit), the minors read from the wow-spi of its
 * class path: Wow's Jackson, Spring Boot's, passes; a Jackson Wow was not verified on fails, naming it, and so do Wow's
 * Jackson modules at two minors; `check` runs it.
 */
class JacksonCheckFunctionalTest {
    @TempDir
    lateinit var projectDir: File

    private val compileClasspath: List<String> =
        requireNotNull(System.getProperty("wow-test.fixture.compileClasspath")) {
            "System property wow-test.fixture.compileClasspath is not set."
        }.split(File.pathSeparator)

    /** The Jackson Wow is built with, Spring Boot's: the version of the fixture's own jackson-databind. */
    private val bootJackson: String = compileClasspath.map(::File).map(File::getName)
        .single { it.startsWith("jackson-databind-") }.removePrefix("jackson-databind-").removeSuffix(".jar")

    private fun write(path: String, content: String) {
        File(projectDir, path).apply { parentFile.mkdirs() }.writeText(content.trimIndent())
    }

    private fun build(jackson: String) {
        write(
            "build.gradle.kts",
            """
            plugins {
                id("org.jetbrains.kotlin.jvm")
                id("me.ahoo.wow")
            }

            repositories {
                ${FixtureRepositories.central}
                maven { url = uri("${System.getProperty("wow-test.fixture.repository")}") }
            }

            dependencies {
                implementation(files(${compileClasspath.joinToString(", ") { "\"${it.replace("\\", "/")}\"" }}))
                // Wow's Jackson modules as an application resolves them, by their coordinates.
                testImplementation("tools.jackson.core:jackson-databind:$bootJackson")
                testImplementation("tools.jackson.module:jackson-module-kotlin:$bootJackson")
            }
            $jackson
            """,
        )
    }

    private fun runner(vararg tasks: String): GradleRunner = GradleRunner.create()
        .withProjectDir(projectDir)
        .withPluginClasspath()
        .withArguments(*tasks, "--stacktrace")
        .forwardOutput()

    @Test
    fun `the gate should pass Wow's Jackson and refuse an unverified minor and two minors, naming them`() {
        write("settings.gradle.kts", """rootProject.name = "shop"""")
        write("gradle.properties", "org.gradle.jvmargs=-Xmx1g")

        // Wow's own, Spring Boot's.
        build(jackson = "")
        runner(WowPlugin.JACKSON_CHECK_TASK).build().task(":${WowPlugin.JACKSON_CHECK_TASK}")?.outcome
            .assert().isEqualTo(TaskOutcome.SUCCESS)

        // A minor below every one Wow is verified on, all of Jackson's modules aligned on it.
        build(jackson = """dependencies { testImplementation(enforcedPlatform("tools.jackson:jackson-bom:3.0.3")) }""")
        runner("check").buildAndFail().output.assert().contains(
            "The testRuntimeClasspath of project ':' resolves Jackson 3.0.3, and Wow's JSON is verified on Jackson",
            "Keep Jackson at a version Wow is verified on, Spring Boot's, on every class path: ${JacksonVersions.GUIDE}",
        )

        // Wow's Jackson modules at two minors: the Kotlin module forced below the others, which Jackson's Gradle
        // metadata would otherwise align.
        build(
            jackson = """
                configurations.testRuntimeClasspath {
                    resolutionStrategy.force("tools.jackson.module:jackson-module-kotlin:3.0.3")
                }
            """,
        )
        runner(WowPlugin.JACKSON_CHECK_TASK).buildAndFail().output.assert().contains(
            "The testRuntimeClasspath of project ':' resolves the Jackson modules Wow's JSON runs on at different " +
                "minors: jackson-core $bootJackson, jackson-databind $bootJackson, jackson-module-kotlin 3.0.3.",
        )
    }
}
