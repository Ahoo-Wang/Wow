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

package me.ahoo.wow.compiler.model.symbol

import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSFunctionDeclaration

/**
 * The function that carries [annotation]: this one, or the nearest one it overrides. An annotation is not inherited
 * by an override, so an override that leaves it out still is the handler its superclass declared,
 * with that declaration's annotation arguments. Null when none of them carries it.
 */
internal fun KSFunctionDeclaration.annotatedIn(annotation: String): KSFunctionDeclaration? =
    generateSequence(this) { it.findOverridee() as? KSFunctionDeclaration }.firstOrNull { function ->
        function.annotations.any { it.isOf(annotation) }
    }

/** The [annotation] of this function, or of the nearest one it overrides ([annotatedIn]). */
internal fun KSFunctionDeclaration.annotationIn(annotation: String): KSAnnotation? =
    annotatedIn(annotation)?.annotations?.firstOrNull { it.isOf(annotation) }

private fun KSAnnotation.isOf(qualifiedName: String): Boolean =
    annotationType.resolve().declaration.qualifiedName?.asString() == qualifiedName
