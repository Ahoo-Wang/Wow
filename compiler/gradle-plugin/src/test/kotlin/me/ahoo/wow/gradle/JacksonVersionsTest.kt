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
import me.ahoo.wow.gradle.JacksonVersions.Found
import me.ahoo.wow.gradle.JacksonVersions.Verified
import org.junit.jupiter.api.Test
import java.util.Properties

/** Whether a class path's Jackson is one Wow's JSON runs on, and what [CheckJackson] says when it is not. */
class JacksonVersionsTest {
    private val verified = Verified(
        minors = listOf("3.1", "3.2"),
        modules = listOf(CORE, DATABIND, KOTLIN),
    )

    private fun refusal(vararg found: Found): String? =
        JacksonVersions.refusal("project ':shop'", "runtimeClasspath", found.toList(), verified)

    @Test
    fun `Wow's modules at one verified minor should pass, a patch apart too`() {
        refusal(Found(CORE, "3.1.8", null), Found(DATABIND, "3.1.8", null), Found(KOTLIN, "3.1.8", null))
            .assert().isNull()
        refusal(Found(CORE, "3.2.1", null), Found(DATABIND, "3.2.3", "io.swagger.core.v3:swagger-core:3.0.0"))
            .assert().isNull()
    }

    @Test
    fun `a module Wow's JSON does not run on is the application's, at any version`() {
        val yaml = Found("tools.jackson.dataformat:jackson-dataformat-yaml", "3.3.0", null)
        refusal(Found(DATABIND, "3.2.3", null), yaml).assert().isNull()
    }

    @Test
    fun `a class path without jackson-databind should pass`() {
        refusal(Found(CORE, "3.0.3", null)).assert().isNull()
    }

    @Test
    fun `a minor Wow was not verified on should be refused, naming it, who asked for it and the change to make`() {
        refusal(Found(DATABIND, "3.3.0", "io.swagger.core.v3:swagger-core:3.1.0")).assert().isEqualTo(
            "The runtimeClasspath of project ':shop' resolves Jackson 3.3.0, asked for by " +
                "io.swagger.core.v3:swagger-core:3.1.0, and Wow's JSON is verified on Jackson 3.1 and 3.2 only. " +
                "Keep Jackson at a version Wow is verified on, Spring Boot's, on every class path: " +
                JacksonVersions.GUIDE,
        )
        refusal(Found(DATABIND, "3.0.3", null)).assert()
            .startsWith("The runtimeClasspath of project ':shop' resolves Jackson 3.0.3, and Wow's JSON")
    }

    @Test
    fun `Wow's modules at two minors should be refused, naming each`() {
        refusal(Found(CORE, "3.2.3", null), Found(DATABIND, "3.2.3", null), Found(KOTLIN, "3.1.8", null))
            .assert().isEqualTo(
                "The runtimeClasspath of project ':shop' resolves the Jackson modules Wow's JSON runs on at different " +
                    "minors: jackson-core 3.2.3, jackson-databind 3.2.3, jackson-module-kotlin 3.1.8. Align them " +
                    "on one version of tools.jackson:jackson-bom, one of Jackson 3.1 and 3.2: " + JacksonVersions.GUIDE,
            )
    }

    @Test
    fun `a module found should read back from its text, with or without who asked for it`() {
        listOf(Found(DATABIND, "3.2.3", "a:b:1"), Found(CORE, "3.1.8", null)).forEach { found ->
            Found.parse(found.format()).assert().isEqualTo(found)
        }
    }

    @Test
    fun `the verified minors and modules should be wow-spi's, and none when it names none`() {
        val properties = Properties().apply {
            setProperty("minors", "3.1, 3.2")
            setProperty("modules", "$CORE,$DATABIND")
        }
        Verified.of(properties).assert().isEqualTo(Verified(listOf("3.1", "3.2"), listOf(CORE, DATABIND)))
        Verified.of(Properties()).assert().isNull()
        Verified.of(Properties().apply { setProperty("minors", "3.1") }).assert().isNull()
    }

    private companion object {
        const val CORE = "tools.jackson.core:jackson-core"
        const val DATABIND = "tools.jackson.core:jackson-databind"
        const val KOTLIN = "tools.jackson.module:jackson-module-kotlin"
    }
}
