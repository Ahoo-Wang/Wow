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
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Design rule K1: each `wow.*` key has one reader, the properties bean that binds it, and one default, the property's.
 * No production code reads a `wow.*` key from the `Environment` or a `Binder` beside it, nor through a placeholder.
 */
class ConfigurationReadersTest {
    private val repositoryRoot: File =
        File(requireNotNull(System.getProperty(REPOSITORY_ROOT)) { "System property $REPOSITORY_ROOT is not set." })
            .canonicalFile

    @Test
    fun `no production code should read a wow key past its properties bean`() {
        ArchitectureRules.wowKeyReaders(repositoryRoot, ALLOWED).assert()
            .describedAs("Read the key from its properties bean (WowProperties.of(environment) before the beans).")
            .isEmpty()
    }

    @Test
    fun `every allowed reader should still read a key`() {
        ArchitectureRules.staleKeyReaders(repositoryRoot, ALLOWED).assert()
            .describedAs("Remove these entries: their files read no wow.* key past a bean any more.")
            .isEmpty()
    }

    @Test
    fun `the rule should catch a literal, a constant and a placeholder`(@TempDir root: File) {
        File(root, "${ArchitectureRules.RUNTIME.dir}/src/main/kotlin/me/ahoo/wow/runtime/Reads.kt").apply {
            parentFile.mkdirs()
        }.writeText(
            """
            package me.ahoo.wow.runtime

            const val KEY: String = "wow.tenant.default"
            val a = environment.getProperty("wow.cluster.router")
            val b = environment.getProperty(KEY, "x")
            val c = Binder.get(environment).bind("spring.application.name", String::class.java)
            @Value("${'$'}{wow.wait.max-timeout:10s}") val d = 1
            // environment.getProperty("wow.cluster.router") in a comment
            """.trimIndent(),
        )
        ArchitectureRules.wowKeyReaders(root).map { it.substringAfter("Reads.kt:").substringBefore(':') }
            .assert().containsExactly("4", "5", "7")
    }

    private companion object {
        const val REPOSITORY_ROOT = "wow.repository.root"

        /** The files that may, each with its reason. */
        val ALLOWED: Map<String, String> = mapOf(
            "extensions/cosid/src/main/kotlin/me/ahoo/wow/cosid/WowCosIdAutoConfiguration.kt" to
                "module imports no Spring Boot (D118): it reads its key, CosIdProperties holding the one default",
        )
    }
}
