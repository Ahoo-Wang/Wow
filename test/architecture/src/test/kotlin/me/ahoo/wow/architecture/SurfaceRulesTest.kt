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
 * The rules that keep the public surface what Wow promises: the ABI dumps the build checks
 * (`<module>/api/<module>.api`), and the modules that keep no dump.
 */
class SurfaceRulesTest {
    private val repositoryRoot: File =
        File(requireNotNull(System.getProperty(REPOSITORY_ROOT)) { "System property $REPOSITORY_ROOT is not set." })
            .canonicalFile

    @Test
    fun `the migration tool should declare nothing public but its entry point`() {
        SurfaceRules.migrateDataPublicDeclarations(repositoryRoot).assert().isEmpty()
    }

    @Test
    fun `a nested class should not outlive its hidden outer class in an ABI dump`() {
        SurfaceRules.hiddenOuterViolations(repositoryRoot).assert().isEmpty()
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
        SurfaceRules.hiddenOuterViolations(root).assert()
            .containsExactly("${module.dir}/api/${module.name}.api: me/ahoo/wow/kernel/Hidden${'$'}Nested")
    }

    @Test
    fun `the public API should name no type that is not public itself`() {
        SurfaceRules.abiClosureViolations(repositoryRoot).assert().isEmpty()
    }

    @Test
    fun `closure rule should catch a public signature that names an internal type`(@TempDir root: File) {
        val module = ArchitectureRules.KERNEL
        File(root, "${module.dir}/api").mkdirs()
        File(root, "${module.dir}/api/${module.name}.api").writeText(
            """
            public final class me/ahoo/wow/kernel/Shown {
            	public final fun hidden ()Lme/ahoo/wow/kernel/Hidden;
            	public final fun shown ()Lme/ahoo/wow/kernel/Shown;
            	public synthetic fun <init> (Lme/ahoo/wow/kernel/Bridged;ILkotlin/jvm/internal/DefaultConstructorMarker;)V
            }
            """.trimIndent(),
        )
        SurfaceRules.abiClosureViolations(root).assert().contains(
            "${module.dir}/api/${module.name}.api: me/ahoo/wow/kernel/Shown: me/ahoo/wow/kernel/Hidden",
        ).noneMatch { "Shown: me/ahoo/wow/kernel/Shown" in it || "Bridged" in it }
        // Every allowed type is named by no dump here: each entry is stale.
        SurfaceRules.abiClosureViolations(root).filter { it.startsWith("stale entry: ") }.assert()
            .hasSize(SurfaceRules.ABI_CLOSURE_ALLOWED.size)
    }

    private companion object {
        const val REPOSITORY_ROOT = "wow.repository.root"
    }
}
