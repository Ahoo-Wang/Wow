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
import me.ahoo.wow.architecture.ArchitectureFixtures.writeSource
import me.ahoo.wow.architecture.CaseSetRules.CLOSED
import me.ahoo.wow.architecture.CaseSetRules.OPEN
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Each public enum and sealed type says whether a minor release may add a case to it (D418 ⑥). */
class CaseSetRulesTest {
    private val repositoryRoot: File =
        File(requireNotNull(System.getProperty(REPOSITORY_ROOT)) { "System property $REPOSITORY_ROOT is not set." })
            .canonicalFile

    @Test
    fun `every public enum and sealed type should say whether a case may be added`() {
        CaseSetRules.violations(repositoryRoot).assert().isEmpty()
    }

    @Test
    fun `case set rule should catch a type with neither sentence or both, and an enum it cannot read`(
        @TempDir root: File,
    ) {
        val spi = ArchitectureRules.SPI
        File(root, "${spi.dir}/api").mkdirs()
        val enums = listOf("Wrapped", "Closed", "Neither", "Holder\$Kind", "Holder\$Companion\$Mode", "Inline")
        val types = listOf("Open", "Both", "Holder").map { "public final class me/ahoo/wow/spi/$it {\n}\n" }
        val enumTypes = enums.map { "public final class me/ahoo/wow/spi/$it : java/lang/Enum {\n}\n" }
        File(root, "${spi.dir}/api/${spi.name}.api").writeText((types + enumTypes).joinToString("\n"))
        root.writeSource(
            spi.dir,
            "me/ahoo/wow/spi/Types.kt",
            """
            package me.ahoo.wow.spi

            /** $OPEN */
            sealed interface Open

            /**
             * An outcome. Cases may be added in a minor release, so a `when` over it
             * keeps an `else` branch.
             */
            @WowSpi
            enum class Wrapped { A }

            /** $CLOSED */
            enum class Closed { A }

            /** A kind. */
            enum class Neither { A }

            /** $OPEN $CLOSED */
            sealed class Both

            internal enum class Hidden { A }

            class Holder {
                /** A kind. */
                enum class Kind { A }

                companion object {
                    enum class Mode { A }
                }
            }

            /** $OPEN */
            @WowSpi enum class Inline { A }
            """.trimIndent(),
        )

        CaseSetRules.violations(root, emptyMap()).assert().containsExactly(
            "${spi.dir}/src/main/kotlin/me/ahoo/wow/spi/Types.kt:17: Neither: neither sentence",
            "${spi.dir}/src/main/kotlin/me/ahoo/wow/spi/Types.kt:20: Both: both sentences",
            "${spi.dir}/src/main/kotlin/me/ahoo/wow/spi/Types.kt:26: Holder.Kind: neither sentence",
            "${spi.dir}/src/main/kotlin/me/ahoo/wow/spi/Types.kt:29: Holder.Companion.Mode: neither sentence",
            "${spi.dir}/api/${spi.name}.api: me/ahoo/wow/spi/Inline: declared in the ABI dump, not found in the sources",
        )
    }

    @Test
    fun `case set rule should skip a pending module, and catch an entry whose module is done or gone`(
        @TempDir root: File,
    ) {
        val kernel = ArchitectureRules.KERNEL
        val runtime = ArchitectureRules.RUNTIME
        listOf(kernel to "Done", runtime to "Undone").forEach { (module, type) ->
            val path = module.dir.substringAfter('/')
            File(root, "${module.dir}/api").mkdirs()
            File(root, "${module.dir}/api/${module.name}.api")
                .writeText("public final class me/ahoo/wow/$path/$type {\n}\n")
            val kdoc = if (type == "Done") OPEN else "A type."
            root.writeSource(
                module.dir,
                "me/ahoo/wow/$path/$type.kt",
                "package me.ahoo.wow.$path\n\n/** $kdoc */\nenum class $type\n",
            )
        }

        val pending = mapOf(
            kernel.dir to "a part that landed",
            runtime.dir to "a part still to land",
            "gone/module" to "a module removed",
        )
        CaseSetRules.violations(root, pending).assert()
            .containsExactly("stale entry: core/kernel", "stale entry: gone/module")
    }

    private companion object {
        const val REPOSITORY_ROOT = "wow-test.repository.root"
    }
}
