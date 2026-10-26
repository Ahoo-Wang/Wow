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
 * The rule of a bounded context's name (decision D169): lower-case letters, digits and hyphens, starting with a
 * letter ([PATTERN], `[a-z][a-z0-9-]*`), such as `shop` or `eshop-catalog`. A dot is never part of one: it separates
 * the context from the aggregate and the type in every name built from it (`context.aggregate.Command`, the topics
 * `wow.<context>.<aggregate>.state`, the host's `wow.aggregates.[<context>.<aggregate>]` keys), which a dotted
 * context name would make ambiguous.
 *
 * The compiler refuses a `@BoundedContext` (or the KSP option `wow.context`) that breaks it, and reading a Manifest
 * ([ManifestTree.read]), a module's fragment or a peer's, refuses a context that does.
 */
object ContextName {
    /** What a context name matches. */
    val PATTERN: Regex = Regex("[a-z][a-z0-9-]*")

    /** Whether [name] is a valid context name. */
    fun isValid(name: String): Boolean = PATTERN.matches(name)

    /** Why [name] is not a valid context name; null when it is. */
    fun problem(name: String): String? = if (isValid(name)) {
        null
    } else {
        "context name \"$name\" is not valid: a context name is lower-case letters, digits and hyphens, starting " +
            "with a letter (${PATTERN.pattern}), such as eshop-catalog; a dot separates the context from the " +
            "aggregate in type names such as context.aggregate.Command"
    }
}
