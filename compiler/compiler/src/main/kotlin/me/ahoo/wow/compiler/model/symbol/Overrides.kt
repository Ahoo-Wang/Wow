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
import com.google.devtools.ksp.symbol.KSPropertyDeclaration

/**
 * This property and the declarations it overrides, nearest first: where its annotations are written, which every
 * reader of a property's annotations asks. An annotation is not inherited by an override, so an override that leaves
 * one out still means what its supertype's declaration says. And a property a class inherits with its type
 * substituted (a supertype's `List<T>` as `List<String>`) is a member KSP gives as the class's own: it keeps its
 * property- and getter-targeted annotations but none of its field-targeted ones (`@field:`) or its constructor
 * parameter's, which the supertype's declaration it overrides has, in the module's sources or a library alike. The
 * walk ends: each step is to a declaration of a strict supertype, and a class's supertypes form no cycle.
 */
internal fun KSPropertyDeclaration.declarations(): Sequence<KSPropertyDeclaration> =
    generateSequence(this) { it.findOverridee() }

/**
 * The function that carries [annotation]: this one, or the nearest one it overrides. An annotation is not inherited
 * by an override, so an override that leaves it out still is the handler its superclass declared,
 * with that declaration's annotation arguments. Null when none of them carries it.
 */
internal fun KSFunctionDeclaration.annotatedIn(annotation: String): KSFunctionDeclaration? =
    generateSequence(this) { it.findOverridee() as? KSFunctionDeclaration }.firstOrNull { function ->
        function.annotations.any { it.isOf(annotation) }
    }

/** Whether this function, or one it overrides, carries [annotation] ([annotatedIn]). */
internal fun KSFunctionDeclaration.isHandler(annotation: String): Boolean = annotatedIn(annotation) != null

/**
 * The name this subscription handler goes by in its subscription, `processor.name`: its `@Name` (or the one of the
 * function it overrides), else its own name. A blank `@Name`, or one without a value, is one of [problems], the
 * former described as [where].
 */
internal fun KSFunctionDeclaration.handlerName(where: String, problems: MutableList<String>): String {
    val named = annotatedIn(NAME)?.declaredName(problems)
    if (named != null && named.isBlank()) {
        problems += "$where has a blank @Name: a handler's @Name names its subscription"
    }
    return named?.takeIf { it.isNotBlank() } ?: simpleName.asString()
}

private const val NAME = "me.ahoo.wow.api.annotation.Name"

/** Whether this annotation is of the annotation class [qualifiedName]. */
internal fun KSAnnotation.isOf(qualifiedName: String): Boolean =
    annotationType.resolve().declaration.qualifiedName?.asString() == qualifiedName
