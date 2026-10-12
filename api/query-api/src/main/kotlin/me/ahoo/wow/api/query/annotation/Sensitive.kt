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

package me.ahoo.wow.api.query.annotation

import kotlin.reflect.KClass

/**
 * How much the query subsystem protects a [Sensitive] field's raw value. Both levels mask the value in every query
 * result; they differ in what a query may do with the raw value.
 */
enum class SensitivityLevel {
    /**
     * Masked in results. Filters and paged sorts still compare the raw value, so range conditions can approach it
     * step by step; the query subsystem can be configured to turn that comparison off. Grouping, `ANY`, field
     * metrics, arithmetic references and cursor sorts are rejected.
     */
    DISPLAY,

    /** Masked in results and never compared: no filter, sort, search, grouping or metric may reference the field. */
    CONFIDENTIAL,
}

/**
 * Declares a sensitive field of a query model: its [level] and how query results [mask] it.
 *
 * Sensitivity is declared only on the domain field, never in a declaration file or by a string path, so renaming the
 * field cannot silently drop the protection. The field must be a JVM `String` that serializes as a JSON string.
 *
 * ```kotlin
 * data class AccountState(
 *     @field:Sensitive(SensitivityLevel.CONFIDENTIAL)
 *     val password: String,
 *     @field:Sensitive(SensitivityLevel.DISPLAY, mask = Mask(keepPrefix = 3, keepSuffix = 4))
 *     val phone: String?,
 * )
 * ```
 *
 * It can also annotate an annotation class, which then declares the same sensitivity wherever it is used.
 *
 * On a value type — a Kotlin value class or a type with a `@JsonValue` member, serialized as a string — it protects
 * every property declared with that type (or a collection of it), in states and event payloads alike, so one value
 * keeps one level everywhere. A property may repeat or tighten the type's level, never loosen it:
 *
 * ```kotlin
 * @JvmInline
 * @Sensitive(SensitivityLevel.DISPLAY, mask = Mask(keepPrefix = 3, keepSuffix = 4))
 * value class PhoneNumber(val value: String)
 * ```
 *
 * Any other class carrying `@Sensitive` fails the schema build.
 */
@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY_GETTER, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
annotation class Sensitive(
    val level: SensitivityLevel,
    val mask: Mask = Mask(),
)

/**
 * How query results mask a [Sensitive] value.
 *
 * The built-in mask keeps [keepPrefix] leading and [keepSuffix] trailing Unicode code points and replaces every other
 * code point with one `*`; the default keeps nothing. A value too short to keep both edges is masked completely.
 * A custom [strategy] replaces the built-in mask; [keepPrefix] and [keepSuffix] must then stay `0`.
 */
@Target
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
annotation class Mask(
    val keepPrefix: Int = 0,
    val keepSuffix: Int = 0,
    /**
     * A custom strategy: a Kotlin `object` or a public class with a no-argument constructor. [MaskStrategy] itself,
     * the default, selects the built-in mask.
     */
    val strategy: KClass<out MaskStrategy> = MaskStrategy::class,
)

/** Masks one string value of a query result. It must not return `null` and should keep an empty string empty. */
fun interface MaskStrategy {
    fun mask(value: String): String
}

/** The built-in mask: keeps [keepPrefix] leading and [keepSuffix] trailing code points and masks the rest. */
class KeepMaskStrategy(private val keepPrefix: Int = 0, private val keepSuffix: Int = 0) : MaskStrategy {
    init {
        require(keepPrefix >= 0 && keepSuffix >= 0) { "Mask keepPrefix and keepSuffix must not be negative." }
    }

    override fun mask(value: String): String {
        val codePoints = value.codePoints().toArray()
        val size = codePoints.size
        if (keepPrefix >= size || keepSuffix >= size - keepPrefix) return "*".repeat(size)
        return buildString {
            codePoints.take(keepPrefix).forEach(::appendCodePoint)
            append("*".repeat(size - keepPrefix - keepSuffix))
            codePoints.takeLast(keepSuffix).forEach(::appendCodePoint)
        }
    }
}
