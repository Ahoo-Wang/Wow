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

/** The rule that keeps design IDs out of what users read: the production sources, the READMEs and the user documentation. */
object DesignIdRule {
    /**
     * A design ID: a decision or a review finding as [DECISION_OR_REVIEW_ID] reads them, a finding of a review round
     * (`R5.B-13`), or a design section, roadmap item or work package named by its number (`design §5.2`, `07 §3`,
     * `roadmap 7.2c`, `work package 6.7`, `decision D254`).
     */
    private val DESIGN_ID = Regex(
        ArchitectureRules.DECISION_OR_REVIEW_ID.pattern + """|\bR\d\.[A-Z]+-\d+\b""" +
            """|\b(?:[Dd]ecisions?|[Ff]indings?|[Rr]eviews?|[Ss]pikes?|[Rr]oadmap|[Ww]ork packages?|[Dd]esign)\s+""" +
            """(?:[A-Z]{0,3}\d|§)|\b\d{2}\s*§\s*\d""",
    )

    /** The folders no repository walk enters: build output, dependencies, tool state and local worktrees. */
    private val UNWALKED = setOf("build", "node_modules", ".git", ".gradle", ".idea", ".claude", ".kotlin")

    /**
     * Whether the design-ID rule reads [path], relative to the repository root: every production source (`src/main`,
     * Kotlin or Java), every README and every page of the user documentation (`documentation/docs`), which their
     * readers take as they are and cannot look an ID up from. Out of it, each for its reason:
     * - `documentation/designs`: the design records themselves, where the IDs live;
     * - a README under `test/` or under a package's `docs/design`: contributor notes of unpublished modules, and a
     *   package's own design records;
     * - `skills/wow-migrate/evals/`: the 9.x sources the migration skill's evals start from, kept as 9.x wrote them.
     */
    internal fun designIdScope(path: String): Boolean = when {
        path.startsWith("documentation/designs/") || path.startsWith("skills/wow-migrate/evals/") -> false
        PRODUCTION_SOURCE.containsMatchIn(path) -> true
        path.startsWith("documentation/docs/") -> path.endsWith(".md")
        path.substringAfterLast('/') == "README.md" -> !path.startsWith("test/") && "/docs/design/" !in path
        else -> false
    }

    private val PRODUCTION_SOURCE = Regex("""(^|/)src/main/(kotlin|java)/.+\.(kt|java)$""")

    /**
     * The lines of the production sources, READMEs and user documentation ([designIdScope]) that cite a design ID ([DESIGN_ID]): the text
     * states the reason instead, and the IDs stay in `documentation/designs`. Comments count as much as strings.
     */
    fun designIds(repositoryRoot: File): List<String> =
        repositoryRoot.walkTopDown()
            .onEnter { it == repositoryRoot || it.name !in UNWALKED }
            .filter { it.isFile }
            .map { it to it.relativeTo(repositoryRoot).invariantSeparatorsPath }
            .filter { (_, path) -> designIdScope(path) }
            .flatMap { (file, path) ->
                file.readLines().withIndex()
                    .filter { (_, line) -> DESIGN_ID.containsMatchIn(line) }
                    .map { (index, line) -> "$path:${index + 1}: ${line.trim()}" }
            }
            .sorted()
            .toList()
}
