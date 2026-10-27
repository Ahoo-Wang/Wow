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

/** Keeps design IDs out of the production sources, the READMEs and the user documentation: they state the reason. */
class DesignIdsTest {
    private val repositoryRoot: File =
        File(requireNotNull(System.getProperty(REPOSITORY_ROOT)) { "System property $REPOSITORY_ROOT is not set." })
            .canonicalFile

    @Test
    fun `production sources, READMEs and user documentation should cite no design ID`() {
        DesignIdRule.designIds(repositoryRoot).assert().isEmpty()
    }

    @Test
    fun `design-ID rule should read sources and READMEs, and skip the design records and the 9x fixtures`() {
        listOf(
            "core/runtime/src/main/kotlin/me/ahoo/wow/runtime/Node.kt",
            "example/java-smoke/src/main/java/me/ahoo/wow/example/java/Account.java",
            "backends/elasticsearch/README.md",
            "deploy/eshop/README.md",
            "documentation/docs/en/guide/aggregates.md",
            "documentation/docs/zh/operate/security.md",
        ).forEach { DesignIdRule.designIdScope(it).assert().describedAs(it).isTrue() }
        listOf(
            "core/runtime/src/test/kotlin/me/ahoo/wow/runtime/NodeTest.kt",
            "documentation/designs/README.md",
            "documentation/designs/07-migration.md",
            "test/benchmarks/README.md",
            "typescript/wow-view-engine/docs/design/README.md",
            "skills/wow-migrate/evals/h5-inputs/view-store/wow-view-store-api/src/main/kotlin/Api.kt",
            "documentation/docs/public/logo.svg",
        ).forEach { DesignIdRule.designIdScope(it).assert().describedAs(it).isFalse() }
    }

    @Test
    fun `design-ID rule should catch every form of ID in comments and strings`(@TempDir root: File) {
        File(root, "core/runtime/src/main/kotlin/me/ahoo/wow/runtime/Ids.kt").apply { parentFile.mkdirs() }
            .writeText(
                """
                package me.ahoo.wow.runtime

                // A reason, stated: no ID on this line, nor version 1.2 nor ODD100.
                // Partitioned by tenant (decision D277).
                /** The Catalog's port (design §5.2). */
                /* As in 07 §3, and roadmap 7.2c, and work package 6.7. */
                val a = "Refused (R5.B-13)."
                val b = "Fixed in A2-4, Q-11 and R3.4-2."
                """.trimIndent(),
            )
        File(root, "README.md").writeText("# Wow\n\nNo IDs here.\n\nBut finding F23 here.\n")
        File(root, "build/generated/src/main/kotlin/Gen.kt").apply { parentFile.mkdirs() }.writeText("// D277\n")
        DesignIdRule.designIds(root).map { it.substringBeforeLast(": ").substringAfterLast(':') }
            .assert().containsExactly("5", "4", "5", "6", "7", "8")
    }

    private companion object {
        const val REPOSITORY_ROOT = "wow.repository.root"
    }
}
