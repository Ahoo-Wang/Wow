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

import me.ahoo.wow.infra.accessor.function.declaringClass
import java.lang.reflect.Field
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KAnnotatedElement
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KMutableProperty
import kotlin.reflect.KProperty
import kotlin.reflect.full.hasAnnotation
import kotlin.reflect.jvm.javaField

/**
 * IntimateAnnotationElement.
 *
 */
class IntimateAnnotationElement(val element: KAnnotatedElement) {

    companion object {
        private const val REPEATABLE_CONTAINER_SIMPLE_NAME = "Container"
        private const val REPEATABLE_CONTAINER_ENDS_WITH = "${'$'}$REPEATABLE_CONTAINER_SIMPLE_NAME"

        private val cache: ConcurrentHashMap<KAnnotatedElement, IntimateAnnotationElement> = ConcurrentHashMap()

        fun KAnnotatedElement.toIntimateAnnotationElement(): IntimateAnnotationElement {
            return cache.computeIfAbsent(this) {
                IntimateAnnotationElement(it)
            }
        }

        private fun Annotation.isRepeatableContainer(): Boolean {
            val containerClass = this.annotationClass.java
            return containerClass.simpleName == REPEATABLE_CONTAINER_SIMPLE_NAME &&
                containerClass.name.endsWith(REPEATABLE_CONTAINER_ENDS_WITH)
        }

        fun Annotation.flatRepeatableAnnotation(): List<Annotation> {
            if (isRepeatableContainer()) {
                try {
                    @Suppress("UNCHECKED_CAST")
                    val value = this.annotationClass.java.getMethod("value").invoke(this) as Array<Annotation>
                    return value.toList()
                } catch (ignore: Exception) {
                    // ignore
                }
            }
            return listOf(this)
        }

        fun Annotation.inheritedAnnotations(scanned: LinkedHashSet<Annotation> = linkedSetOf()): Set<Annotation> {
            val existed = scanned.any { it == this }
            if (existed) {
                return scanned
            }
            scanned.add(this)
            this.annotationClass.annotations.filter {
                it.annotationClass.hasAnnotation<java.lang.annotation.Inherited>()
            }.flatMap {
                it.inheritedAnnotations(scanned)
            }
            return scanned
        }
    }

    val property: KProperty<*>? = element as? KProperty<*>
    val getter: KProperty.Getter<*>? = property?.getter
    val setter: KMutableProperty.Setter<*>? = if (property is KMutableProperty<*>) {
        property.setter
    } else {
        null
    }
    val javaField: Field? = property?.javaField
    val function: KFunction<*>? = if (element is KFunction<*>) {
        element
    } else {
        getter ?: setter
    }
    val declaringClass: KClass<*>? by lazy {
        function?.declaringClass ?: javaField?.declaringClass?.kotlin
    }
    val intimatedAnnotations: LinkedHashSet<Annotation> by lazy {
        val annotations = linkedSetOf<Annotation>()
        annotations.addAll(element.annotations)
        if (getter != null) {
            annotations.addAll(getter.annotations)
        }
        if (setter != null) {
            annotations.addAll(setter.annotations)
        }
        if (javaField != null) {
            annotations.addAll(javaField.annotations)
        }
        val merged = linkedSetOf<Annotation>()
        annotations.forEach {
            merged.addAll(it.flatRepeatableAnnotation())
        }
        merged
    }

    val inheritedAnnotations: Set<Annotation> by lazy {
        val merged = linkedSetOf<Annotation>()
        intimatedAnnotations.forEach {
            it.inheritedAnnotations(merged)
        }
        merged
    }
}
