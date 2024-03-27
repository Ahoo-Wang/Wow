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

import kotlin.reflect.KAnnotatedElement
import kotlin.reflect.KClass
import kotlin.reflect.KMutableProperty
import kotlin.reflect.KProperty
import kotlin.reflect.jvm.javaField

object AnnotationScanner {
    fun KAnnotatedElement.intimateAnnotations(): List<Annotation> {
        val found = mutableListOf<Annotation>()
        found.addAll(annotations)
        if (this is KProperty<*>) {
            found.addAll(getter.annotations)
            if (javaField != null) {
                found.addAll(javaField!!.annotations)
            }
        }

        if (this is KMutableProperty<*>) {
            found.addAll(this.setter.annotations)
        }
        return found
    }

    fun KAnnotatedElement.allAnnotations(scanned: MutableList<Annotation> = mutableListOf()): List<Annotation> {
        for (annotation in intimateAnnotations()) {
            val existed = scanned.any { it.annotationClass == annotation.annotationClass }
            if (!existed) {
                scanned.add(annotation)
                annotation.annotationClass.allAnnotations(scanned)
            }
        }
        return scanned
    }

    fun <A : Annotation> KAnnotatedElement.scanAnnotation(annotationClass: KClass<A>): A? {
        @Suppress("UNCHECKED_CAST")
        return allAnnotations().firstOrNull { it.annotationClass == annotationClass } as A?
    }

    inline fun <reified A : Annotation> KAnnotatedElement.scanAnnotation(): A? {
        return scanAnnotation(A::class)
    }
}
