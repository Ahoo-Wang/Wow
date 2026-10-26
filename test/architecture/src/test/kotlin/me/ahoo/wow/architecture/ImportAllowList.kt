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

import java.io.File

/**
 * A positive source rule (review R5.C-10): production sources of [modules] import only from [allowed] packages, the
 * Kotlin standard library and the JDK. Where a blacklist names what a module must not use and misses whatever no one
 * thought of, this names what it may, so a new dependency of a core module fails until it is added here with its
 * reason. The Wow packages it allows are those of the module itself and of the modules it may depend on, which makes
 * it the module graph's rule ([ArchitectureRules.ALLOWED_PROJECT_DEPENDENCIES]) at the level of the sources.
 */
data class ImportAllowList(val name: String, val modules: List<String>, val allowed: List<String>) {
    fun violations(repositoryRoot: File): List<String> =
        modules.flatMap { module ->
            listOf("src/main/kotlin", "src/main/java").map { File(File(repositoryRoot, module), it) }
                .filter { it.isDirectory }
                .flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension in setOf("kt", "java") } }
        }.sortedBy { it.path }.flatMap { file ->
            val path = file.relativeTo(repositoryRoot).invariantSeparatorsPath
            file.readLines().mapIndexedNotNull { index, line ->
                IMPORT.matchEntire(line.trim())?.groupValues?.get(1)
                    ?.takeUnless { target -> (STANDARD + allowed).any { target.within(it) } }
                    ?.let { "$path:${index + 1}: $it" }
            }
        }

    private companion object {
        /** An import, static or not, of a name, a package's members (`.*`) or an alias (`as`). */
        val IMPORT = Regex("""import\s+(?:static\s+)?([\w.]+?)(?:\.\*)?(?:\s+as\s+\w+)?\s*;?""")

        /** The Kotlin standard library and the JDK, which every module may use. */
        val STANDARD = listOf("kotlin", "java")

        fun String.within(prefix: String): Boolean = this == prefix || startsWith("$prefix.")
    }
}
