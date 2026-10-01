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

@file:Suppress("DEPRECATION")

package me.ahoo.wow.api.query.mask

import java.lang.annotation.Inherited
import kotlin.reflect.KClass

/*
 * The 9.1 masking annotations. A domain jar compiled against 9.1 still names them, and the JVM silently drops an
 * annotation whose class is missing, so removing them would serve those fields unmasked. Schema discovery reads every
 * annotation carrying @Masking as a `@Sensitive(SensitivityLevel.DISPLAY)` field masked by its strategy: the level
 * that keeps 9.1's behaviour (filters and sorts compare the raw value, the result is masked).
 */

/** Marks an annotation class as a mask rule compiled by [strategy]. */
@Deprecated(
    "Scheduled for removal in 10.0.0. Use @Sensitive.",
    ReplaceWith("Sensitive", "me.ahoo.wow.api.query.annotation.Sensitive"),
)
@Target(AnnotationTarget.ANNOTATION_CLASS)
@Retention(AnnotationRetention.RUNTIME)
@Inherited
@MustBeDocumented
annotation class Masking(val strategy: KClass<out MaskStrategy<*>>)

/**
 * Compiles [annotation] only during schema construction.
 * The returned [CompiledMask] must be thread-safe, non-blocking, and non-null.
 */
@Deprecated(
    "Scheduled for removal in 10.0.0. Use me.ahoo.wow.api.query.annotation.MaskStrategy.",
    ReplaceWith("MaskStrategy", "me.ahoo.wow.api.query.annotation.MaskStrategy"),
)
interface MaskStrategy<A : Annotation> {
    fun compile(annotation: A): CompiledMask
}

/**
 * Reusable result of [MaskStrategy.compile].
 * [mask] may be invoked concurrently, must not block, and returns a non-null value.
 */
@Deprecated(
    "Scheduled for removal in 10.0.0. Use me.ahoo.wow.api.query.annotation.MaskStrategy.",
    ReplaceWith("MaskStrategy", "me.ahoo.wow.api.query.annotation.MaskStrategy"),
)
fun interface CompiledMask {
    fun mask(value: String): String
}

/** Masks every code point: read as `@Sensitive(SensitivityLevel.DISPLAY)`. */
@Deprecated(
    "Scheduled for removal in 10.0.0. Use @Sensitive(SensitivityLevel.DISPLAY).",
    ReplaceWith(
        "Sensitive(SensitivityLevel.DISPLAY)",
        "me.ahoo.wow.api.query.annotation.Sensitive",
        "me.ahoo.wow.api.query.annotation.SensitivityLevel",
    ),
)
@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY_GETTER, AnnotationTarget.ANNOTATION_CLASS)
@Retention(AnnotationRetention.RUNTIME)
@Inherited
@MustBeDocumented
@Masking(FullMaskStrategy::class)
annotation class Mask

/** Keeps [prefix] and [suffix] code points: read as `@Sensitive(DISPLAY, mask = Mask(prefix, suffix))`. */
@Deprecated(
    "Scheduled for removal in 10.0.0. Use @Sensitive(SensitivityLevel.DISPLAY, mask = Mask(keepPrefix, keepSuffix)).",
    ReplaceWith(
        "Sensitive(SensitivityLevel.DISPLAY, mask = Mask(keepPrefix = prefix, keepSuffix = suffix))",
        "me.ahoo.wow.api.query.annotation.Sensitive",
        "me.ahoo.wow.api.query.annotation.SensitivityLevel",
        "me.ahoo.wow.api.query.annotation.Mask",
    ),
)
@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY_GETTER, AnnotationTarget.ANNOTATION_CLASS)
@Retention(AnnotationRetention.RUNTIME)
@Inherited
@MustBeDocumented
@Masking(KeepMaskStrategy::class)
annotation class KeepMask(val prefix: Int = 0, val suffix: Int = 0)

@Deprecated("Scheduled for removal in 10.0.0. Use @Sensitive.")
object FullMaskStrategy : MaskStrategy<Mask> {
    override fun compile(annotation: Mask): CompiledMask = CompiledMask { value ->
        "*".repeat(value.codePointCount(0, value.length))
    }
}

@Deprecated("Scheduled for removal in 10.0.0. Use me.ahoo.wow.api.query.annotation.KeepMaskStrategy.")
object KeepMaskStrategy : MaskStrategy<KeepMask> {
    override fun compile(annotation: KeepMask): CompiledMask {
        val strategy = me.ahoo.wow.api.query.annotation.KeepMaskStrategy(annotation.prefix, annotation.suffix)
        return CompiledMask(strategy::mask)
    }
}
