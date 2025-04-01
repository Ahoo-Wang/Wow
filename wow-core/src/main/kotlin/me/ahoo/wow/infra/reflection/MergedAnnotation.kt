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
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.full.declaredMembers
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

            is KFunction<*> -> {
                element.inheritedAnnotations()
            }

            else -> {
                element.toIntimateAnnotationElement().inheritedAnnotations
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

        private fun KClass<*>.inheritedClass(
            root: KClass<*> = this,
            scanned: LinkedHashSet<KClass<*>> = linkedSetOf()
        ): Set<KClass<*>> {
            val existed = scanned.any { it == this }
            if (existed) {
                return scanned
            }
            if (this != root) {
                scanned.add(this)
            }
            superclasses.filter {
                it != this && it != Any::class
            }.forEach {
                it.inheritedClass(root, scanned)
            }
            return scanned
        }

        fun KClass<*>.inheritedAnnotations(): Set<Annotation> {
            val intimateAnnotationElement = this.toIntimateAnnotationElement()
            val merged: LinkedHashSet<Annotation> = linkedSetOf()
            merged.addAll(intimateAnnotationElement.inheritedAnnotations)
            inheritedClass().flatMap {
                it.toIntimateAnnotationElement().inheritedAnnotations
            }.forEach { inheritedAnnotation ->
                if (merged.all { it.annotationClass != inheritedAnnotation.annotationClass }) {
                    merged.add(inheritedAnnotation)
                }
            }
            return merged
        }

        fun KProperty<*>.inheritedAnnotations(): Set<Annotation> {
            val intimateAnnotationElement = this.toIntimateAnnotationElement()
            val declaringClass = intimateAnnotationElement.declaringClass
            declaringClass ?: return intimateAnnotationElement.inheritedAnnotations
            val merged: LinkedHashSet<Annotation> = linkedSetOf()
            merged.addAll(intimateAnnotationElement.inheritedAnnotations)
            declaringClass.inheritedClass().flatMap {
                it.declaredMembers
            }.filter {
                it.name == this.name
            }.flatMap {
                it.toIntimateAnnotationElement().inheritedAnnotations
            }.forEach { inheritedAnnotation ->
                if (merged.all { it.annotationClass != inheritedAnnotation.annotationClass }) {
                    merged.add(inheritedAnnotation)
                }
            }
            return merged
        }

        private fun KFunction<*>.sameSignature(other: KFunction<*>): Boolean {
            if (this.name != other.name) {
                return false
            }
            if (this.parameters.size != other.parameters.size) {
                return false
            }
            if (this.parameters.size <= 1) {
                return true
            }

            /**
             * 遍历比较参数类型,排除第一个参数
             */
            for (i in 1 until this.parameters.size) {
                if (this.parameters[i].type != other.parameters[i].type) {
                    return false
                }
            }
            return true
        }

        fun KFunction<*>.inheritedAnnotations(): Set<Annotation> {
            val intimateAnnotationElement = this.toIntimateAnnotationElement()
            val declaringClass = intimateAnnotationElement.declaringClass
            declaringClass ?: return intimateAnnotationElement.inheritedAnnotations
            val merged: LinkedHashSet<Annotation> = linkedSetOf()
            merged.addAll(intimateAnnotationElement.inheritedAnnotations)
            declaringClass.inheritedClass().flatMap {
                it.declaredFunctions
            }.filter {
                it.sameSignature(this)
            }.flatMap {
                it.toIntimateAnnotationElement().inheritedAnnotations
            }.forEach { inheritedAnnotation ->
                if (merged.all { it.annotationClass != inheritedAnnotation.annotationClass }) {
                    merged.add(inheritedAnnotation)
                }
            }
            return merged
        }
    }
}
