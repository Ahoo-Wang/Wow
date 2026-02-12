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

import me.ahoo.wow.infra.reflection.MergedAnnotation.Companion.toMergedAnnotation
import kotlin.reflect.KAnnotatedElement
import kotlin.reflect.KClass

/**
 * Utility object for scanning annotations on Kotlin reflection elements.
 * Provides methods to find annotations that are inherited from parent classes,
 * interfaces, or overridden members, not just directly declared annotations.
 *
 * This scanner uses MergedAnnotation to collect annotations from the entire
 * inheritance hierarchy, making it useful for frameworks that need to discover
 * annotations from base classes or interfaces.
 */
object AnnotationScanner {
    /**
     * Scans for all annotations of the specified type on the annotated element,
     * including inherited annotations from parent classes and interfaces.
     *
     * @param A the type of annotation to scan for
     * @param annotationClass the KClass representing the annotation type
     * @return a list of all matching annotations found on the element
     *
     * @sample
     * ```
     * class MyClass {
     *     @MyAnnotation
     *     fun myMethod() {}
     * }
     *
     * val method = MyClass::class.members.find { it.name == "myMethod" }!!
     * val annotations = method.scanAnnotations(MyAnnotation::class)
     * ```
     */
    @Suppress("UNCHECKED_CAST")
    fun <A : Annotation> KAnnotatedElement.scanAnnotations(annotationClass: KClass<A>): List<A> =
        this
            .toMergedAnnotation()
            .mergedAnnotations
            .filter { it.annotationClass == annotationClass } as List<A>

    /**
     * Scans for the first annotation of the specified type on the annotated element.
     * Returns null if no matching annotation is found.
     *
     * @param A the type of annotation to scan for
     * @param annotationClass the KClass representing the annotation type
     * @return the first matching annotation, or null if none found
     *
     * @sample
     * ```
     * val annotation = myClass.scanAnnotation(Deprecated::class)
     * if (annotation != null) {
     *     println("Class is deprecated: ${annotation.message}")
     * }
     * ```
     */
    fun <A : Annotation> KAnnotatedElement.scanAnnotation(annotationClass: KClass<A>): A? =
        scanAnnotations(annotationClass).firstOrNull<A>()

    /**
     * Scans for the first annotation of the specified reified type on the annotated element.
     * This is a convenience method that uses reified generics to avoid specifying the class explicitly.
     *
     * @param A the type of annotation to scan for
     * @return the first matching annotation, or null if none found
     *
     * @sample
     * ```
     * val deprecated = myClass.scanAnnotation<Deprecated>()
     * ```
     */
    inline fun <reified A : Annotation> KAnnotatedElement.scanAnnotation(): A? = scanAnnotation(A::class)

    /**
     * Scans for all annotations of the specified reified type on the annotated element.
     * This is a convenience method that uses reified generics to avoid specifying the class explicitly.
     *
     * @param A the type of annotation to scan for
     * @return a list of all matching annotations found on the element
     *
     * @sample
     * ```
     * val annotations = myClass.scanAnnotations<SuppressWarnings>()
     * ```
     */
    inline fun <reified A : Annotation> KAnnotatedElement.scanAnnotations(): List<A> = scanAnnotations(A::class)
}
