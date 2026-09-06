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

package me.ahoo.wow.api.query.mask

import java.lang.annotation.Inherited
import kotlin.reflect.KClass

@Target(AnnotationTarget.ANNOTATION_CLASS)
@Retention(AnnotationRetention.RUNTIME)
@Inherited
@MustBeDocumented
annotation class Masking(val strategy: KClass<out MaskStrategy<*>>)

/**
 * Compiles [annotation] only during schema construction.
 * The returned [CompiledMask] must be thread-safe, non-blocking, and non-null.
 */
interface MaskStrategy<A : Annotation> {
    fun compile(annotation: A): CompiledMask
}

/**
 * Reusable result of [MaskStrategy.compile].
 * [mask] may be invoked concurrently, must not block, and returns a non-null value.
 */
fun interface CompiledMask {
    fun mask(value: String): String
}

@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY_GETTER, AnnotationTarget.ANNOTATION_CLASS)
@Retention(AnnotationRetention.RUNTIME)
@Inherited
@MustBeDocumented
@Masking(FullMaskStrategy::class)
annotation class Mask

@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY_GETTER, AnnotationTarget.ANNOTATION_CLASS)
@Retention(AnnotationRetention.RUNTIME)
@Inherited
@MustBeDocumented
@Masking(KeepMaskStrategy::class)
annotation class KeepMask(val prefix: Int = 0, val suffix: Int = 0)

object FullMaskStrategy : MaskStrategy<Mask> {
    override fun compile(annotation: Mask): CompiledMask = CompiledMask { value ->
        "*".repeat(value.codePointCount(0, value.length))
    }
}

object KeepMaskStrategy : MaskStrategy<KeepMask> {
    override fun compile(annotation: KeepMask): CompiledMask {
        val prefix = annotation.prefix
        val suffix = annotation.suffix
        require(prefix >= 0 && suffix >= 0)
        return CompiledMask { value -> keepMask(value, prefix, suffix) }
    }
}

private fun keepMask(value: String, prefix: Int, suffix: Int): String {
    val size = value.codePointCount(0, value.length)
    if (prefix >= size || suffix >= size - prefix) return "*".repeat(size)

    val prefixEnd = value.offsetByCodePoints(0, prefix)
    val suffixStart = value.offsetByCodePoints(value.length, -suffix)
    return buildString(prefixEnd + value.length - suffixStart + size - prefix - suffix) {
        append(value, 0, prefixEnd)
        repeat(size - prefix - suffix) { append('*') }
        append(value, suffixStart, value.length)
    }
}
