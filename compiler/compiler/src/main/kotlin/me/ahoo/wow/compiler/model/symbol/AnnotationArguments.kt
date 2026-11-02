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

import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSValueParameter
import kotlin.reflect.KClass

/**
 * An annotation of the annotation class [type], read from its [KSAnnotation]'s arguments by name rather than through a
 * proxy of the annotation class (`getAnnotationsByType`, which the compiler never calls). KSP leaves the value of an
 * argument null when it has not evaluated the constant expression the argument is, such as a constant of another
 * module, rather than failing; the proxy answers such an argument with the annotation's default, or with a null its
 * type does not allow, so the model would take a value nobody wrote. Here an argument given with no value, or a
 * required one not given, is [unread], and the model takes nothing from the annotation ([argumentsOf]). An argument
 * not given is the annotation's default, which KSP lists among the arguments, and among its default arguments too.
 *
 * KSP drops an element of an array argument it has not evaluated rather than leaving it null (`@Alias("a", X.B)` is
 * read as `["a"]` while `X.B` has no value), and no API of KSP tells such an element: an array is read as KSP lists it.
 */
internal class AnnotationArguments(private val type: KClass<out Annotation>, annotation: KSAnnotation) {
    /** The value of each argument by name: as given, else by default; null when it has none. */
    private val values: Map<String?, Any?> = (annotation.arguments + annotation.defaultArguments)
        .distinctBy { it.name?.asString() }
        .associate { it.name?.asString() to it.value }

    /**
     * The arguments the annotation class declares that have no value, by name; an array holding an element without one
     * too, should KSP leave it null rather than drop it.
     */
    val unread: List<String> = type.java.declaredMethods.filterNot { it.isSynthetic }.map { it.name }
        .filter { name -> values[name].let { it == null || it is List<*> && null in it } }.sorted()

    fun string(name: String): String = values.getValue(name) as String

    fun int(name: String): Int = values.getValue(name) as Int

    fun boolean(name: String): Boolean = values.getValue(name) as Boolean

    fun strings(name: String): List<String> = (values.getValue(name) as List<*>).map { it as String }

    /** The name of the enum entry the argument [name] holds: KSP gives an entry as its declaration, or its type. */
    fun enumName(name: String): String = when (val value = values.getValue(name)) {
        is KSType -> value.declaration.simpleName.asString()
        else -> (value as KSDeclaration).simpleName.asString()
    }

    /** What [unread] means for the model, of the annotation on [annotated]. */
    fun problem(annotated: KSAnnotated): String =
        "@${type.simpleName} of ${annotated.describe()}: KSP evaluated no value for its " +
            (if (unread.size == 1) "argument " else "arguments ") + unread.joinToString(" and ") +
            ", so the model takes nothing from it; an argument must be a constant KSP can read, a literal or a const val"

    private fun KSAnnotated.describe(): String = when (this) {
        is KSValueParameter -> "parameter ${name?.asString()} of ${(parent as? KSAnnotated)?.describe()}"
        // A constructor has no qualified name: it is its class's `<init>`.
        is KSDeclaration -> qualifiedName?.asString()
            ?: ((parentDeclaration?.describe()?.plus(".") ?: "") + simpleName.asString())
        else -> toString()
    }

    companion object {
        /** The one argument of `@Name`, `@Revision`, `@Alias`, `@Description` and `@Summary`. */
        const val VALUE = "value"
    }
}

/**
 * This declaration's annotation of the class [type], read by its arguments ([AnnotationArguments]); null when it has
 * none. One with an argument that has no value is null too, and one of [problems], naming this declaration, the
 * annotation and the argument: the model never takes a default in place of a value written.
 */
internal fun KSAnnotated.argumentsOf(
    type: KClass<out Annotation>,
    problems: MutableList<String>,
): AnnotationArguments? {
    val annotation = annotations.firstOrNull { it.isOf(type.java.name) } ?: return null
    val arguments = AnnotationArguments(type, annotation)
    if (arguments.unread.isNotEmpty()) {
        problems += arguments.problem(this)
        return null
    }
    return arguments
}
