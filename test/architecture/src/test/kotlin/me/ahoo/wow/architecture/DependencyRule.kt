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
 * A source dependency rule: production sources of [modules] (optionally narrowed by [appliesToPackage]) must not
 * reference any package in [forbidden]. [modules] are directories relative to the repository root.
 *
 * Copied from wow-core's architecture tests (9.3.0, refactor design G3); it moves here for good when V10 removes
 * wow-core.
 *
 * The check scans source text, not bytecode: every `import` and every fully-qualified reference outside comments
 * counts, including class names in string literals (reflective lookups are dependencies too).
 */
data class DependencyRule(
    val name: String,
    val modules: List<String>,
    val forbidden: List<String>,
    val appliesToPackage: (String) -> Boolean = { true },
) {
    fun violations(repositoryRoot: File): List<String> =
        modules.flatMap { module -> sourceFiles(File(repositoryRoot, module)) }
            .flatMap { file ->
                val path = file.relativeTo(repositoryRoot).invariantSeparatorsPath
                fileViolations(file).map { "$path:$it" }
            }

    private fun fileViolations(file: File): List<String> {
        val code = stripComments(file.readText())
        val packageName = PACKAGE.find(code)?.groupValues?.get(1).orEmpty()
        if (!appliesToPackage(packageName)) {
            return emptyList()
        }
        return code.lines().flatMapIndexed { index, line ->
            QUALIFIED_NAME.findAll(line)
                .map { it.value }
                .filter { reference -> forbidden.any { reference == it || reference.startsWith("$it.") } }
                .map { "${index + 1}: $it" }
                .toList()
        }
    }

    companion object {
        private val PACKAGE = Regex("""^\s*package\s+([\w.]+)""", RegexOption.MULTILINE)
        private val QUALIFIED_NAME = Regex("""(?<![\w.])[a-z][\w]*(?:\.\w+)+""")
        private val BLOCK_COMMENT = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
        private val LINE_COMMENT = Regex("""(?m)(^|[^:"])//.*$""")

        private fun sourceFiles(moduleDir: File): List<File> =
            listOf("src/main/kotlin", "src/main/java")
                .map { File(moduleDir, it) }
                .filter { it.isDirectory }
                .flatMap { root -> root.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") } }
                .sortedBy { it.path }

        /** Removes comments but keeps line breaks so line numbers stay correct. */
        private fun stripComments(source: String): String =
            source
                .replace(BLOCK_COMMENT) { match -> "\n".repeat(match.value.count { it == '\n' }) }
                .replace(LINE_COMMENT, "$1")
    }
}
