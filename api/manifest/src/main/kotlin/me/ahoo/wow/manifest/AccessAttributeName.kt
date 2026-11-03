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

package me.ahoo.wow.manifest

/**
 * The rule of an access attribute's name (`@Access`): lower camel case, a lower-case letter then letters and digits
 * ([PATTERN], `[a-z][A-Za-z0-9]*`), at most [MAX_LENGTH] characters, such as `department` or `sharedWith`, and not
 * one of the built-in attributes' names ([BUILT_IN]).
 *
 * The name is a segment of a query path (`access.<name>`, [QueryFieldNames.isSegment]) and, as a context's name
 * ([ContextName]), takes no dot, which separates the segments. It takes no `-` or `_` either, so it is a Kotlin and
 * TypeScript identifier and a column name that needs no quoting, and it is short enough for a backend to prefix it
 * within PostgreSQL's 63-byte identifiers. Within an aggregate the names differ ignoring case, since an unquoted
 * PostgreSQL identifier folds case.
 *
 * The compiler refuses an `@Access` name that breaks it, and reading a Manifest ([ManifestTree.read]) refuses an
 * attribute that does.
 */
object AccessAttributeName {
    /** What an access attribute's name matches. */
    val PATTERN: Regex = Regex("[a-z][A-Za-z0-9]*")

    /** The longest name an access attribute takes. */
    const val MAX_LENGTH: Int = 40

    /** The names of the built-in attributes every aggregate has, its scope's (`AccessAttribute.BUILT_IN` of `:api`). */
    val BUILT_IN: Set<String> = setOf("owner", "space")

    /** Whether [name] is a valid name of a declared access attribute. */
    fun isValid(name: String): Boolean = name.length <= MAX_LENGTH && PATTERN.matches(name) && name !in BUILT_IN

    /** Why [name] is not a valid name of a declared access attribute; null when it is. */
    fun problem(name: String): String? = when {
        name in BUILT_IN ->
            "access attribute name \"$name\" is a built-in attribute's: every aggregate has " +
                "${BUILT_IN.joinToString(" and ")}, its scope's"
        !isValid(name) ->
            "access attribute name \"$name\" is not valid: an access attribute's name is lower camel " +
                "case, a lower-case letter then letters and digits (${PATTERN.pattern}), of at most $MAX_LENGTH characters"
        else -> null
    }
}
