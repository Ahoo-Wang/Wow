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

import me.ahoo.wow.infra.reflection.IntimateAnnotationElement.Companion.flatRepeatableAnnotation
import me.ahoo.wow.infra.reflection.IntimateAnnotationElement.Companion.inheritedAnnotations
import me.ahoo.wow.infra.reflection.IntimateAnnotationElement.Companion.toIntimateAnnotationElement
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KAnnotatedElement
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KProperty
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.full.declaredMemberProperties
import kotlin.reflect.full.declaredMembers
import kotlin.reflect.full.superclasses
import kotlin.reflect.jvm.javaMethod

/**
 * Finds annotations inherited from a superclass or interface.
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

        private val KClass<*>.directParents: List<KClass<*>>
            get() = superclasses.filter { it != this && it != Any::class }

        private fun mergeInheritedAnnotations(
            local: Set<Annotation>,
            inherited: Sequence<Annotation>,
        ): Set<Annotation> {
            val localTypes = local.mapTo(hashSetOf()) { it.annotationClass }
            return linkedSetOf<Annotation>().apply {
                addAll(local)
                inherited.filterNot { it.annotationClass in localTypes }.forEach(::add)
            }
        }

        private fun KClass<*>.effectiveAnnotations(
            localAnnotations: (KClass<*>) -> Set<Annotation>,
            memo: MutableMap<KClass<*>, Set<Annotation>>,
        ): Set<Annotation> = memo.getOrPut(this) {
            mergeInheritedAnnotations(
                localAnnotations(this),
                effectiveParentAnnotations(memo, localAnnotations),
            )
        }

        private fun KClass<*>.effectiveParentAnnotations(
            memo: MutableMap<KClass<*>, Set<Annotation>> = mutableMapOf(),
            localAnnotations: (KClass<*>) -> Set<Annotation>,
        ): Sequence<Annotation> = directParents.asSequence().flatMap { parent ->
            parent.effectiveAnnotations(localAnnotations, memo).asSequence()
        }

        fun KClass<*>.inheritedAnnotations(): Set<Annotation> = inheritedAnnotations {
            it.toIntimateAnnotationElement().inheritedAnnotations
        }

        internal fun KClass<*>.inheritedAnnotations(
            localAnnotations: (KClass<*>) -> Set<Annotation>,
        ): Set<Annotation> = effectiveAnnotations(localAnnotations, mutableMapOf())

        fun KProperty<*>.inheritedAnnotations(): Set<Annotation> {
            val intimateAnnotationElement = this.toIntimateAnnotationElement()
            val declaringClass = intimateAnnotationElement.declaringClass
            declaringClass ?: return intimateAnnotationElement.inheritedAnnotations
            return mergeInheritedAnnotations(
                intimateAnnotationElement.inheritedAnnotations,
                declaringClass.effectiveParentAnnotations { parent ->
                    parent.declaredMembers.asSequence()
                        .filter { it.name == this.name }
                        .flatMap { it.toIntimateAnnotationElement().inheritedAnnotations.asSequence() }
                        .toCollection(linkedSetOf())
                },
            )
        }

        private val KFunction<*>.jvmName: String
            get() = javaMethod?.name ?: name

        private fun KFunction<*>.sameSignature(other: Method): Boolean {
            val method = javaMethod ?: return false
            return method.name == other.name && method.parameterTypes.contentEquals(other.parameterTypes)
        }

        private fun KFunction<*>.sameSignature(other: KFunction<*>): Boolean {
            if (this.jvmName != other.jvmName) {
                return false
            }
            if (this.parameters.size != other.parameters.size) {
                return false
            }
            if (this.parameters.size <= 1) {
                return true
            }

            // The first parameter is the receiver and is not part of the declared signature.
            for (i in 1 until this.parameters.size) {
                if (this.parameters[i].type != other.parameters[i].type) {
                    return false
                }
            }
            return true
        }

        private fun KClass<*>.declaredFunctionAnnotations(
            matches: (KFunction<*>) -> Boolean,
        ): Set<Annotation> = (
            declaredFunctions.asSequence() +
                declaredMemberProperties.asSequence().map { property -> property.getter }
            ).filter(matches)
            .flatMap { it.toIntimateAnnotationElement().inheritedAnnotations.asSequence() }
            .toCollection(linkedSetOf())

        fun Method.inheritedAnnotations(): Set<Annotation> {
            val local = annotations.asSequence()
                .flatMap { it.flatRepeatableAnnotation() }
                .flatMap { it.inheritedAnnotations().asSequence() }
                .toCollection(linkedSetOf())
            return mergeInheritedAnnotations(
                local,
                declaringClass.kotlin.effectiveParentAnnotations { parent ->
                    parent.declaredFunctionAnnotations { it.sameSignature(this) }
                },
            )
        }

        fun KFunction<*>.inheritedAnnotations(): Set<Annotation> {
            val intimateAnnotationElement = this.toIntimateAnnotationElement()
            val declaringClass = intimateAnnotationElement.declaringClass
            declaringClass ?: return intimateAnnotationElement.inheritedAnnotations
            return mergeInheritedAnnotations(
                intimateAnnotationElement.inheritedAnnotations,
                declaringClass.effectiveParentAnnotations { parent ->
                    parent.declaredFunctionAnnotations { it.sameSignature(this) }
                },
            )
        }
    }
}
