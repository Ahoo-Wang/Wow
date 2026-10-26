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

/** The rules the ABI dumps are held to, read from the dumps the build checks (`<module>/api/<module>.api`). */
class AbiDumpRulesTest {
    private val repositoryRoot: File =
        File(requireNotNull(System.getProperty(REPOSITORY_ROOT)) { "System property $REPOSITORY_ROOT is not set." })
            .canonicalFile

    @Test
    fun `a nested class should not outlive its hidden outer class in an ABI dump`() {
        ArchitectureRules.hiddenOuterViolations(repositoryRoot).assert().isEmpty()
    }

    @Test
    fun `hidden outer rule should catch a nested class whose outer class is left out`(@TempDir root: File) {
        val module = ArchitectureRules.KERNEL
        File(root, "${module.dir}/api").mkdirs()
        File(root, "${module.dir}/api/${module.name}.api").writeText(
            """
            public final class me/ahoo/wow/kernel/Shown {
            }

            public final class me/ahoo/wow/kernel/Shown${'$'}Companion {
            }

            public abstract interface class me/ahoo/wow/kernel/Hidden${'$'}Nested : me/ahoo/wow/kernel/Hidden {
            }
            """.trimIndent(),
        )
        ArchitectureRules.hiddenOuterViolations(root).assert()
            .containsExactly("${module.dir}/api/${module.name}.api: me/ahoo/wow/kernel/Hidden${'$'}Nested")
    }

    private companion object {
        const val REPOSITORY_ROOT = "wow.repository.root"
    }
}
