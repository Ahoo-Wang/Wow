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
 * The entry points a service's contract jar links to keep their ABI lines (AGENTS.md, Binary Compatibility; D483 ⑯):
 * a `-` line on one of them would pass `checkKotlinAbi` with a label like any binary break, though no minor may
 * change it.
 */
class ContractEntryPointsTest {
    private val repositoryRoot: File =
        File(requireNotNull(System.getProperty(REPOSITORY_ROOT)) { "System property $REPOSITORY_ROOT is not set." })
            .canonicalFile

    @Test
    fun `every entry point of a contract jar should keep its line in its dump`() {
        ContractEntryPoints.violations(repositoryRoot).assert().isEmpty()
    }

    @Test
    fun `a changed, removed or renamed entry point should be named, and so should a missing dump`() {
        val listed = listOf(
            "# A comment.",
            "== a.api",
            "public final class x/A {",
            "\tpublic fun <init> (Ljava/lang/String;)V",
            "\tpublic final fun of (Ljava/lang/String;)Lx/A;",
            "public final class x/B {",
            "== b.api",
            "public final class x/C {",
        ).joinToString("\n")
        val dump = listOf(
            "public final class x/A {",
            "\tpublic fun <init> (Ljava/lang/String;I)V",
            "\tpublic final fun of (Ljava/lang/String;)Lx/A;",
            "}",
            "",
            "public final class x/B : x/A {",
            "}",
        ).joinToString("\n")
        ContractEntryPoints.violations(listed) { dump.takeIf { _ -> it == "a.api" } }.assert().containsExactly(
            "a.api: x/A: public fun <init> (Ljava/lang/String;)V",
            "a.api: public final class x/B {",
            "b.api: no such dump",
            "b.api: public final class x/C {",
        )
    }

    private companion object {
        const val REPOSITORY_ROOT = "wow-test.repository.root"
    }
}
