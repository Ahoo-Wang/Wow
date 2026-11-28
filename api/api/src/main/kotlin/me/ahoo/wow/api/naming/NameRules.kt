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

/**
 * The naming rules of a model, one definition the compiler, the kernel's model DSL and the Kotlin contract
 * generator all ask, so that none accepts a name another cannot use; the TypeScript generator's copy
 * (`typescript/wow-generator/src/manifest/nameRules.ts`) is held equal to it by the cases of
 * `typescript/wow-generator/test/fixtures/name-rules.json`, which a test of each reads.
 *
 * - A **name** ([NAME_PATTERN]) is an aggregate's, a command's, an event's, a processor's or a handler's, written
 *   with `@Name` or taken from the class or the function: a letter, then letters, digits, `_` and `-`. A dot is never
 *   part of one, since it joins the names (`context.aggregate.Command`, `processor.handler`).
 * - A **code** ([CODE_PATTERN]) is a rejection code (`@ErrorCode`): segments joined by dots, each a letter or a
 *   digit, then letters, digits, `_` and `-`; a local code is one segment.
 * - A **framework name** ([FRAMEWORK_NAME_PATTERN]) is one of Wow's own, which no domain declares: `wow`, then names
 *   after dots (`wow.lifecycle.DeleteAggregate`, `wow.snapshot`).
 *
 * The rules are ASCII, and narrower than "an identifier can be taken from it": a name is also a segment of a topic
 * (`wow.<context>.<aggregate>.state`), of a URL path and of a `Wow-Wait` hop, which take nothing else, so widening a
 * rule to what Kotlin or TypeScript reads as an identifier would admit names those cannot carry.
 */
@InternalWowApi
object NameRules {
    /** What a name matches. */
    const val NAME_PATTERN: String = "[A-Za-z][A-Za-z0-9_-]*"

    /** What a segment of a code matches. */
    const val CODE_SEGMENT_PATTERN: String = "[A-Za-z0-9][A-Za-z0-9_-]*"

    /** What a code matches: its segments, joined by dots. */
    const val CODE_PATTERN: String = "$CODE_SEGMENT_PATTERN(?:\\.$CODE_SEGMENT_PATTERN)*"

    /** What a name of the framework's own matches. */
    const val FRAMEWORK_NAME_PATTERN: String = "wow(?:\\.$NAME_PATTERN)+"

    private val NAME = Regex(NAME_PATTERN)
    private val CODE_SEGMENT = Regex(CODE_SEGMENT_PATTERN)
    private val FRAMEWORK_NAME = Regex(FRAMEWORK_NAME_PATTERN)

    /** Whether [name] is a name. */
    fun isName(name: String): Boolean = NAME.matches(name)

    /** Whether [code] is a code. */
    fun isCode(code: String): Boolean = code.split('.').all(CODE_SEGMENT::matches)

    /** Whether [name] is a name of the framework's own. */
    fun isFrameworkName(name: String): Boolean = FRAMEWORK_NAME.matches(name)

    /**
     * Why [name] is no name, as what follows its subject in a sentence (`<subject> is not a name: …`, without a full
     * stop); null when it is one.
     */
    fun nameProblem(name: String): String? = if (isName(name)) {
        null
    } else {
        "is not a name: a name starts with a letter and holds only letters, digits, '_' and '-' ($NAME_PATTERN), " +
            "such as order-item; a dot separates a context, an aggregate and a type, and the generated contracts " +
            "take an identifier from every name"
    }

    /**
     * Why [code] is no code, as what follows its subject in a sentence (`<subject> is not a code: …`, without a full
     * stop), naming the segment at fault; null when it is one.
     */
    fun codeProblem(code: String): String? {
        val segments = code.split('.')
        val at = segments.indexOfFirst { !CODE_SEGMENT.matches(it) }
        if (at < 0) {
            return null
        }
        val fault = when {
            segments[at].isNotEmpty() -> "its segment \"${segments[at]}\" is not one"
            segments.size == 1 -> "it is empty"
            at == segments.lastIndex -> "its last segment is empty (it ends in a dot)"
            at == 0 -> "its first segment is empty (it starts with a dot)"
            else -> "its segment ${at + 1} is empty (two dots in a row)"
        }
        return "is not a code: $fault; a code is segments joined by dots, each starting with a letter or a digit and " +
            "holding only letters, digits, '_' and '-' ($CODE_SEGMENT_PATTERN), such as shop.cart.out-of-stock"
    }
}
