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

package me.ahoo.wow.infra.reflection

import java.lang.reflect.AnnotatedElement
import java.util.concurrent.ConcurrentHashMap

private data class AnnotatedElementTargetAnnotationTypeKey<T : Annotation>(
    val annotatedElement: AnnotatedElement,
    val targetAnnotationType: Class<T>
)

object AnnotationScanner {
    private val EMPTY = Any()
    private val cache: ConcurrentHashMap<AnnotatedElementTargetAnnotationTypeKey<*>, Any> = ConcurrentHashMap()

    private fun <T : Annotation> scan(
        annotation: Annotation,
        targetAnnotationType: Class<T>,
        scanned: MutableSet<Annotation> = mutableSetOf()
    ): T? {
        if (targetAnnotationType.isInstance(annotation)) {
            @Suppress("UNCHECKED_CAST")
            return annotation as T
        }
        scanned.add(annotation)
        for (it in annotation.annotationClass.annotations) {
            if (scanned.contains(it)) {
                continue
            }
            scanned.add(it)
            val matched = scan(it, targetAnnotationType, scanned)
            if (matched != null) {
                return matched
            }
        }
        return null
    }

    fun <T : Annotation> scan(annotatedElement: AnnotatedElement, targetAnnotationType: Class<T>): T? {
        val cacheKey = AnnotatedElementTargetAnnotationTypeKey(annotatedElement, targetAnnotationType)
        val annotation =
            cache.computeIfAbsent(cacheKey) { _ ->
                for (it in annotatedElement.annotations) {
                    val matched = scan(it, targetAnnotationType)
                    if (matched != null) {
                        return@computeIfAbsent matched
                    }
                }
                EMPTY
            }

        return if (annotation == EMPTY) {
            null
        } else {
            @Suppress("UNCHECKED_CAST")
            annotation as T
        }
    }

    @Deprecated("Use KAnnotatedElement.scanAnnotation instead.", ReplaceWith("KAnnotatedElement.scanAnnotation"))
    inline fun <reified T : Annotation> AnnotatedElement.scan(): T? {
        val targetAnnotationType = T::class.java
        return scan(this, targetAnnotationType)
    }
}
