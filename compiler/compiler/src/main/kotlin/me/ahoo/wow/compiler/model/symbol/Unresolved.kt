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
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSTypeReference

/**
 * The problem of a symbol that never validated, deferred to the end of KSP: nothing of it was generated. It names the
 * types of the symbol that did not resolve, as its source writes them, where it finds them. Sources that compile
 * without such an error point to KSP's environment, not to them: KSP runs overlapping in a Gradle daemon have failed
 * to read their class path (the Wow Gradle plugin's `KspRuns` runs a build's one at a time).
 */
internal fun KSAnnotated.neverValidated(): String {
    val name = (this as? KSDeclaration)?.qualifiedName?.asString() ?: toString()
    val types = unresolvedTypes()
    val which = if (types.isEmpty()) "a type it names" else types.joinToString(", ")
    return "$name never validated, so nothing of it was generated: $which did not resolve on the class path KSP " +
        "read. If the sources compile without it, KSP's environment failed to read the class path: rerun the build, " +
        "or upgrade KSP"
}

/**
 * The types this symbol names that do not resolve, in order: those of its annotations, and of a class's type
 * parameters, supertypes and members, a function's parameters and return type, or a property's type, with their type
 * arguments.
 */
private fun KSAnnotated.unresolvedTypes(): List<String> = sortedSetOf<String>().also { collect(this, it) }.toList()

private fun collect(symbol: KSAnnotated, found: MutableSet<String>) {
    symbol.annotations.forEach { collect(it.annotationType, found) }
    when (symbol) {
        is KSClassDeclaration -> {
            symbol.typeParameters.forEach { parameter -> parameter.bounds.forEach { collect(it, found) } }
            symbol.superTypes.forEach { collect(it, found) }
            symbol.declarations.forEach { collect(it, found) }
        }
        is KSFunctionDeclaration -> {
            symbol.parameters.forEach { parameter ->
                parameter.annotations.forEach { collect(it.annotationType, found) }
                collect(parameter.type, found)
            }
            symbol.returnType?.let { collect(it, found) }
        }
        is KSPropertyDeclaration -> collect(symbol.type, found)
    }
}

private fun collect(reference: KSTypeReference, found: MutableSet<String>) {
    val type = reference.resolve()
    if (type.isError) {
        // KSP renders a reference it did not resolve as written or as `<ERROR TYPE: X>`.
        found += reference.toString().removeSurrounding("<ERROR TYPE: ", ">")
        return
    }
    type.arguments.forEach { argument -> argument.type?.let { collect(it, found) } }
}
