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

import me.ahoo.wow.infra.reflection.IntimateAnnotationElement.Companion.toIntimateAnnotationElement
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KAnnotatedElement
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KProperty
import kotlin.reflect.full.superclasses

/**
 * 获取继承自父类或者接口的注解
 */
class MergedAnnotation(val element: KAnnotatedElement) {

    val mergedAnnotations: Set<Annotation> by lazy {
        when (element) {
            is KProperty<*> -> {
                element.inheritedAnnotations()
            }

            is KClass<*> -> {
                element.inheritedAnnotations()
            }

            else -> {
                element.annotations.toSet()
            }
        }
    }

    companion object {
        private val cache: ConcurrentHashMap<KAnnotatedElement, MergedAnnotation> = ConcurrentHashMap()
        fun KAnnotatedElement.toMergedAnnotation(): MergedAnnotation {
            return cache.computeIfAbsent(this) {
                MergedAnnotation(it)
            }
        }

        fun KClass<*>.inheritedClass(scanned: LinkedHashSet<KClass<*>> = linkedSetOf()): Set<KClass<*>> {
            val existed = scanned.any { it == this }
            if (existed) {
                return scanned
            }
            scanned.add(this)
            superclasses.forEach {
                it.inheritedClass(scanned)
            }
            return scanned
        }

        fun KClass<*>.inheritedAnnotations(): Set<Annotation> {
            val merged: LinkedHashSet<Annotation> = linkedSetOf()
            inheritedClass().forEach {
                merged.addAll(it.annotations)
            }
            return merged
        }

        fun KProperty<*>.inheritedAnnotations(): Set<Annotation> {
            val intimatePropertyAnnotationElement = this.toIntimateAnnotationElement()
            val declaringClass = intimatePropertyAnnotationElement.declaringClass
            declaringClass ?: return intimatePropertyAnnotationElement.inheritedAnnotations
            val merged: LinkedHashSet<Annotation> = linkedSetOf()
            merged.addAll(intimatePropertyAnnotationElement.inheritedAnnotations)
            declaringClass.inheritedClass().flatMap {
                it.members
            }.filter {
                it.name == this.name
            }.flatMap {
                it.toIntimateAnnotationElement().inheritedAnnotations
            }.forEach {
                merged.add(it)
            }
            return merged
        }

        fun KFunction<*>.inheritedAnnotations(): Set<Annotation> {
            val intimatePropertyAnnotationElement = this.toIntimateAnnotationElement()
            TODO()
        }
    }
}

