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

/** Keeps decision and review IDs out of the strings production code shows its users. */
class StringLiteralIdsTest {
    private val repositoryRoot: File =
        File(requireNotNull(System.getProperty(REPOSITORY_ROOT)) { "System property $REPOSITORY_ROOT is not set." })
            .canonicalFile

    @Test
    fun `production string literals should name no decision or review ID`() {
        ArchitectureRules.idsInStringLiterals(repositoryRoot).assert().isEmpty()
    }

    @Test
    fun `ID rule should catch an ID in a string but not in a comment or a name`(@TempDir root: File) {
        File(root, "${ArchitectureRules.RUNTIME.dir}/src/main/kotlin/me/ahoo/wow/runtime/Ids.kt").apply {
            parentFile.mkdirs()
        }.writeText(
            """
            package me.ahoo.wow.runtime

            // A comment may cite decision D254 and review R2.3-1.
            /* So may D1 and A5-14 in a block. */
            val ok = "D1 is too short, ODD100 is a word, and ${'$'}{"x"} is a template."
            val c = '"'
            val decision = "Refused (decision D254)."
            val review = "Fixed (review R2.3-1)."
            val raw = ${"\"\"\""}
                Q-13 in a raw string
            ${"\"\"\""}
            """.trimIndent(),
        )
        ArchitectureRules.idsInStringLiterals(root).map { it.substringAfter("Ids.kt:").substringBefore(':') }
            .assert().containsExactly("7", "8", "9")
    }

    private companion object {
        const val REPOSITORY_ROOT = "wow.repository.root"
    }
}
