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

package me.ahoo.wow.manifest.tools

/**
 * Qualified names as the generated Kotlin sources write them. A JVM package or class name may have a segment that is a
 * Kotlin hard keyword but a Java identifier (`object` of `com.example.object`): Kotlin reads such a segment only in
 * backticks, so the generated code quotes it, and a package that is a valid Java package name always compiles.
 */
internal object KotlinNames {
    /** The Kotlin hard keywords that are Java identifiers: a JVM name may have them as segments. */
    private val KEYWORDS = setOf("as", "fun", "in", "is", "object", "typealias", "typeof", "val", "var", "when")

    /** [name], a package or a binary class name (`a.b.Outer$Inner`), as Kotlin source writes it. */
    fun qualified(name: String): String = name.replace('$', '.').split('.')
        .joinToString(".") { segment -> if (segment in KEYWORDS) "`$segment`" else segment }
}
