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

// Not Wow source. A stand-in checkout holding one rule in each of the shapes
// Kotlin states a rule with, so `check-wow-conformance.mjs` is held to finding
// all of them. Two earlier drafts of that script reported success while seeing
// a third of the real rules.
package me.ahoo.wow.api.query

internal object Synthetic {
    fun plain(value: Int) {
        require(value > 0) { "Synthetic plain require." }
    }

    fun nestedParens(values: List<String>, other: List<String>) {
        require(values.isNotEmpty() && other.distinct().size == other.size) {
            "Synthetic require with parentheses in its condition."
        }
    }

    fun notNull(value: String?) {
        requireNotNull(value) { "Synthetic requireNotNull." }
    }

    fun checked(value: Int) {
        check(value > 0) { "Synthetic check." }
    }

    fun checkedNotNull(value: String?) {
        checkNotNull(value) { "Synthetic checkNotNull." }
    }

    fun errored(): Nothing = error("Synthetic error call.")

    fun thrown(): Nothing = throw IllegalArgumentException("Synthetic throw.")

    // A bracket that is not syntax. Balancing on raw characters closes the
    // call early at each of these, and the rule after it disappears.
    fun parenInString(value: String) {
        require(value != ")") { "Synthetic paren inside a string." }
    }

    fun parenInChar(value: Char) {
        require(value != ')') { "Synthetic paren inside a char." }
    }

    fun parenInBlockComment(value: Int) {
        require(value > 0 /* ) */) { "Synthetic paren inside a block comment." }
    }

    fun parenInLineComment(value: Int) {
        require(
            value > 0 // )
        ) { "Synthetic paren inside a line comment." }
    }

    // The template holds a string holding an unbalanced paren: only a lexer
    // that follows a string into its template and back out gets past it.
    fun parenInTemplate(value: String) {
        require(value != "${")"}") { "Synthetic paren inside a template." }
    }

    fun templateWithString(value: Int) {
        require(value > 0) { "Synthetic ${listOf("nested").first()} message." }
    }
}
