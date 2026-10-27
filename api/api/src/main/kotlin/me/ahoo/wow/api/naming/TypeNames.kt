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

package me.ahoo.wow.api.naming

import me.ahoo.wow.api.annotation.InternalWowApi
import java.util.regex.Pattern

/**
 * The one rule that names aggregates and their command and event types: the compiler
 * applies it to the declarations it reads and the kernel's DSL to the classes it is given, so a model written by hand
 * stores the names the Manifest lists. It takes the names as strings, so both can call it.
 */
@InternalWowApi
object TypeNames {
    /** Splits before every upper-case letter, as 9.x's `PascalCaseStrategy` does (wow-metadata). */
    private val UPPERCASE_SEPARATOR: Pattern = Pattern.compile("(?=[A-Z])")

    /**
     * An aggregate's name: its [annotatedName] (`@Name`), else its [simpleName] in `snake_case`, as 9.x's
     * `AggregateRootResolver` names it.
     */
    fun aggregateName(simpleName: String, annotatedName: String?): String = annotatedName ?: pascalToSnake(simpleName)

    /**
     * The last part of a command or event type's name (`context.aggregate.<this>`): its [annotatedName] (`@Name`),
     * else its [simpleName].
     */
    fun typeName(simpleName: String, annotatedName: String?): String = annotatedName ?: simpleName

    /**
     * `PascalCase` to `snake_case` (`CartItem` → `cart_item`), the result of 9.x's
     * `NamingConverter.PASCAL_TO_SNAKE`, so an aggregate keeps the name 9.x gave it: every upper-case letter starts a
     * new word (`HTTPOrder` → `h_t_t_p_order`).
     */
    fun pascalToSnake(name: String): String = UPPERCASE_SEPARATOR.split(name).joinToString(separator = "_") {
        it.lowercase()
    }
}
