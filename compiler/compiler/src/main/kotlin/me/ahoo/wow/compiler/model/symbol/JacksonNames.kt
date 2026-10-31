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
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration

// What Jackson, and so Wow's JSON, makes of a property, as its annotations say: the name it writes it under, and whether
// it writes it at all. The query fields of a state and the property of a command that holds its aggregate's id follow it.

private const val JSON_PROPERTY = "com.fasterxml.jackson.annotation.JsonProperty"
private const val JSON_IGNORE = "com.fasterxml.jackson.annotation.JsonIgnore"
private const val JSON_IGNORE_PROPERTIES = "com.fasterxml.jackson.annotation.JsonIgnoreProperties"

/** The name Jackson writes this property under: its `@JsonProperty` value, else its own name. */
internal fun KSPropertyDeclaration.wireName(): String =
    jacksonAnnotations().filter { it.qualifiedName() == JSON_PROPERTY }
        .firstNotNullOfOrNull { (it.argument("value") as? String)?.takeIf(String::isNotEmpty) }
        ?: simpleName.asString()

/** Whether this property is `@JsonIgnore`d, on itself, its getter or its primary-constructor parameter. */
internal fun KSPropertyDeclaration.jsonIgnored(): Boolean = jacksonAnnotations().any { it.isJsonIgnore() }

/** The properties this class's `@JsonIgnoreProperties` names, which Jackson writes none of. */
internal fun KSClassDeclaration.jsonIgnoredProperties(): Set<String> = annotations
    .filter { it.qualifiedName() == JSON_IGNORE_PROPERTIES }
    .flatMap { (it.argument("value") as? List<*>).orEmpty() }
    .filterIsInstance<String>()
    .toSet()

/** Jackson annotations on the property, its getter, or its primary-constructor parameter. */
private fun KSPropertyDeclaration.jacksonAnnotations(): List<KSAnnotation> {
    val name = simpleName.asString()
    val parameter = (parentDeclaration as? KSClassDeclaration)?.primaryConstructor?.parameters
        ?.firstOrNull { it.name?.asString() == name }?.annotations.orEmpty()
    return (annotations + getter?.annotations.orEmpty() + parameter)
        .filter { it.qualifiedName() == JSON_PROPERTY || it.qualifiedName() == JSON_IGNORE }
        .toList()
}

private fun KSAnnotation.isJsonIgnore(): Boolean = qualifiedName() == JSON_IGNORE && argument("value") as? Boolean ?: true

private fun KSAnnotation.qualifiedName(): String? = annotationType.resolve().declaration.qualifiedName?.asString()

private fun KSAnnotation.argument(name: String): Any? = arguments.firstOrNull { it.name?.asString() == name }?.value
