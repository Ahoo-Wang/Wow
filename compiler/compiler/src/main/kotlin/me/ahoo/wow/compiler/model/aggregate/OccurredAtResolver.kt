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

package me.ahoo.wow.compiler.model.aggregate

import com.google.devtools.ksp.isPrivate
import com.google.devtools.ksp.isProtected
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import me.ahoo.wow.compiler.model.aggregate.AggregateIdResolver.hasAnnotation
import me.ahoo.wow.compiler.model.symbol.JavaBeans
import me.ahoo.wow.compiler.model.symbol.JavaRecords
import me.ahoo.wow.compiler.model.symbol.declarations
import me.ahoo.wow.compiler.model.symbol.hierarchyProperties
import me.ahoo.wow.compiler.model.symbol.jsonIgnored
import me.ahoo.wow.compiler.model.symbol.jsonIgnoredAt
import me.ahoo.wow.compiler.model.symbol.jsonIgnoredProperties
import me.ahoo.wow.compiler.model.symbol.jsonNaming
import me.ahoo.wow.compiler.model.symbol.wireName
import me.ahoo.wow.compiler.model.symbol.wireNameAt

/**
 * The property of a command that holds when what it records happened: [property], its name in Kotlin, which the
 * generated model reads; [json], its name in the command's JSON body, which the Manifest gives senders
 * ([me.ahoo.wow.manifest.CommandManifest.occurredAtProperty]); [nullable] when the command may declare none.
 */
internal data class OccurredAtBinding(val property: String, val json: String, val nullable: Boolean)

/**
 * Finds a command's `@OccurredAt` property: one its class declares, or overrides, or an interface or a superclass of
 * it declares. Its problems:
 *
 * - more than one property is annotated;
 * - it is no `Long` or `Long?`: a time is epoch milliseconds;
 * - the command does not create its aggregate (`@CreateAggregate`): only a creation declares when it occurred;
 * - it is private or protected, so the generated model cannot read it;
 * - Wow's JSON does not write it (`@JsonIgnore`, or the class's `@JsonIgnoreProperties` names it), so a sender's
 *   body would not hold it.
 */
internal object OccurredAtResolver {
    private const val OCCURRED_AT = "me.ahoo.wow.api.annotation.OccurredAt"
    private val LONGS = setOf("kotlin.Long", "java.lang.Long")

    fun of(command: KSClassDeclaration, creation: String, problems: MutableList<String>): OccurredAtBinding? {
        val name = command.simpleName.asString()
        val annotated = command.hierarchyProperties().filter { it.annotated() }.distinctBy { it.simpleName.asString() }.toList()
        // A Java record's component, which KSP shows as no property: read where its annotation stands (#6839).
        val components = JavaRecords.components(command).filter { component ->
            component.sites.any { it.hasAnnotation(OCCURRED_AT) }
        }
        if (annotated.size + components.size > 1) {
            val names = annotated.map { it.simpleName.asString() } + components.map { it.name }
            problems += "command $name has several @OccurredAt properties (${names.joinToString()}): one property " +
                "holds when it occurred"
            return null
        }
        components.singleOrNull()?.let { return componentBinding(command, it, creation, problems) }
        val property = annotated.singleOrNull() ?: return null
        val propertyName = property.simpleName.asString()
        val where = "command $name's @OccurredAt property $propertyName"
        val type = property.type.resolve()
        checkTime(where, name, type.declaration.qualifiedName?.asString(), creation, problems)
        // A Java bean's private field is read through its public getter, as Kotlin reads it as the property (#6839).
        if ((property.isPrivate() || property.isProtected()) && JavaBeans.getterOf(property, command) == null) {
            problems += "$where is not visible to the generated model: make it public or internal"
        }
        val ignored = command.jsonIgnoredProperties(problems)
        val wireName = property.wireName(problems, command.jsonNaming(problems))
        if (property.jsonIgnored(problems) || propertyName in ignored || wireName in ignored) {
            problems += "$where is not written by Wow's JSON (@JsonIgnore, @JsonIgnoreProperties): a sender's body " +
                "holds when the command occurred"
        }
        return OccurredAtBinding(propertyName, wireName, type.isMarkedNullable)
    }

    /**
     * The binding of the Java record [command]'s `@OccurredAt` [component]: read by its name, as Kotlin reads a record's
     * component, and named as Jackson writes it. Its problems are a property's.
     */
    private fun componentBinding(
        command: KSClassDeclaration,
        component: JavaRecords.Component,
        creation: String,
        problems: MutableList<String>,
    ): OccurredAtBinding {
        val name = command.simpleName.asString()
        val where = "command $name's @OccurredAt record component ${component.name}"
        checkTime(where, name, component.type, creation, problems)
        val ignored = command.jsonIgnoredProperties(problems)
        val wireName = wireNameAt(component.name, component.sites, problems, command.jsonNaming(problems))
        if (jsonIgnoredAt(component.sites, problems) || component.name in ignored || wireName in ignored) {
            problems += "$where is not written by Wow's JSON (@JsonIgnore, @JsonIgnoreProperties): a sender's body " +
                "holds when the command occurred"
        }
        // A Java Long is a platform type: a sender may leave it out.
        return OccurredAtBinding(component.name, wireName, nullable = true)
    }

    /**
     * Adds the problems of the `@OccurredAt` member [where] of the command [name], a property's and a record component's
     * alike, to [problems]: its [type] is a Long, and the command creates its aggregate, as its [creation] says.
     */
    private fun checkTime(where: String, name: String, type: String?, creation: String, problems: MutableList<String>) {
        if (type !in LONGS) {
            problems += "$where is a $type: a time is a Long of epoch milliseconds"
        }
        if (creation != "REQUIRED") {
            problems += "$where: only a command that creates its aggregate (@CreateAggregate) declares when it " +
                "occurred, and $name ${if (creation == "ALLOWED") "may also act on one that exists" else "does not"}"
        }
    }

    /** Whether this property or a declaration it overrides, or its getter, is `@OccurredAt`. */
    private fun KSPropertyDeclaration.annotated(): Boolean =
        declarations().any { property ->
            property.hasAnnotation(OCCURRED_AT) || property.getter?.hasAnnotation(OCCURRED_AT) == true
        }
}
