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

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import tools.jackson.databind.PropertyNamingStrategies
import tools.jackson.databind.annotation.JsonNaming

// What Jackson, and so Wow's JSON, makes of a property, as its annotations say: the name it writes it under, and whether
// it writes it at all. The query fields of a state and the property of a command that holds its aggregate's id follow it.
// A Jackson annotation with an argument without a value is one of the problems ([argumentsOf]).

/**
 * The name Jackson writes this property under: its `@JsonProperty` value, else its own name as the [naming] strategy
 * of the class being written translates it ([jsonNaming]).
 */
internal fun KSPropertyDeclaration.wireName(
    problems: MutableList<String>,
    naming: PropertyNamingStrategies.NamingBase? = null,
): String = jacksonHolders()
    .mapNotNull { it.argumentsOf(JsonProperty::class, problems)?.string(AnnotationArguments.VALUE) }
    .firstOrNull(String::isNotEmpty)
    // A NamingBase translates every name alike, whatever the mapper and the member.
    ?: simpleName.asString().let { naming?.nameForField(null, null, it) ?: it }

/**
 * The naming strategy Jackson writes this class's properties with: the `@JsonNaming` of the class, else of the nearest
 * supertype that has one, as Jackson finds it; null when none has. The processor runs the strategy class itself, so a
 * path is the name Jackson writes, not a copy of its rule: one of Jackson's `PropertyNamingStrategies`. Another (an
 * application's own, which the processor cannot load) is one of [problems].
 */
internal fun KSClassDeclaration.jsonNaming(problems: MutableList<String>): PropertyNamingStrategies.NamingBase? {
    val owner = (sequenceOf(this) + getAllSuperTypes().mapNotNull { it.declaration as? KSClassDeclaration })
        .firstOrNull { it.annotations.any { annotation -> annotation.isOf(JsonNaming::class.java.name) } }
        ?: return null
    val strategy = owner.argumentsOf(JsonNaming::class, problems)?.type(AnnotationArguments.VALUE)?.declaration
        ?: return null
    val name = strategy.binaryName()
    val loaded = runCatching { Class.forName(name, true, JsonNaming::class.java.classLoader) }.getOrNull()
    if (loaded == null || !PropertyNamingStrategies.NamingBase::class.java.isAssignableFrom(loaded)) {
        problems += "@JsonNaming of ${owner.qualifiedName?.asString()} names $name: the model names its properties " +
            "as Jackson writes them only with one of Jackson's PropertyNamingStrategies, which it runs; name each " +
            "property with @JsonProperty instead"
        return null
    }
    return loaded.getDeclaredConstructor().newInstance() as PropertyNamingStrategies.NamingBase
}

/** Whether this property is `@JsonIgnore`d, on itself, its getter or its primary-constructor parameter. */
internal fun KSPropertyDeclaration.jsonIgnored(problems: MutableList<String>): Boolean =
    jacksonHolders().mapNotNull { it.argumentsOf(JsonIgnore::class, problems)?.boolean(AnnotationArguments.VALUE) }
        .any { it }

/** The properties this class's `@JsonIgnoreProperties` names, which Jackson writes none of. */
internal fun KSClassDeclaration.jsonIgnoredProperties(problems: MutableList<String>): Set<String> =
    argumentsOf(JsonIgnoreProperties::class, problems)?.strings(AnnotationArguments.VALUE).orEmpty().toSet()

/** Where a Jackson annotation of this property stands: the property, its getter and its primary-constructor parameter. */
private fun KSPropertyDeclaration.jacksonHolders(): List<KSAnnotated> {
    val name = simpleName.asString()
    val parameter = (parentDeclaration as? KSClassDeclaration)?.primaryConstructor?.parameters
        ?.firstOrNull { it.name?.asString() == name }
    return listOfNotNull(this, getter, parameter)
}
