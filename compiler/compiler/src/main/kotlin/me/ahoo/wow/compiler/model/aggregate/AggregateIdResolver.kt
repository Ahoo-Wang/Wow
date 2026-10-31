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

import com.google.devtools.ksp.KspExperimental
import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.isPrivate
import com.google.devtools.ksp.isProtected
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import me.ahoo.wow.compiler.model.symbol.jsonIgnored
import me.ahoo.wow.compiler.model.symbol.jsonIgnoredProperties
import me.ahoo.wow.compiler.model.symbol.wireName

/**
 * The property of a command that holds its aggregate's id: [property], its name in Kotlin, which the generated
 * model reads; [json], its name in the command's JSON body, which the Manifest gives senders that have no class
 * ([me.ahoo.wow.manifest.CommandManifest.idProperty]); [nullable] when a creation may leave it out.
 */
internal data class AggregateIdBinding(val property: String, val json: String, val nullable: Boolean)

/**
 * Finds a command's `@AggregateId` property: one its class declares, or overrides, or an interface or a superclass
 * of it declares, stored or computed. Its problems:
 *
 * - more than one property is annotated;
 * - it is no `String`, or a `String?` of a command that does not create its aggregate, which must name one;
 * - it is private or protected, so the generated model cannot read it;
 * - Wow's JSON does not write it (`@JsonIgnore`, or the class's `@JsonIgnoreProperties` names it), so a sender's
 *   body would not hold it;
 * - the command is `@Unaddressed`: its transformer finds its aggregate.
 */
internal object AggregateIdResolver {
    private const val AGGREGATE_ID = "me.ahoo.wow.api.annotation.AggregateId"
    private val STRINGS = setOf("kotlin.String", "java.lang.String")

    fun of(
        command: KSClassDeclaration,
        creation: String,
        unaddressed: Boolean,
        problems: MutableList<String>,
    ): AggregateIdBinding? {
        val name = command.simpleName.asString()
        val annotated = propertiesOf(command).filter { it.annotated() }.distinctBy { it.simpleName.asString() }.toList()
        if (annotated.size > 1) {
            problems += "command $name has several @AggregateId properties " +
                "(${annotated.joinToString { it.simpleName.asString() }}): one property holds its aggregate's id"
            return null
        }
        val property = annotated.singleOrNull() ?: return null
        val propertyName = property.simpleName.asString()
        val where = "command $name's @AggregateId property $propertyName"
        val type = property.type.resolve()
        val nullable = type.isMarkedNullable
        when {
            type.declaration.qualifiedName?.asString() !in STRINGS ->
                problems += "$where is a ${type.declaration.qualifiedName?.asString()}: an aggregate id is a String"

            nullable && creation == "NONE" ->
                problems += "$where is nullable: only a command that creates its aggregate may leave its id out, " +
                    "which admission then generates"
        }
        if (property.isPrivate() || property.isProtected() || property.getter?.isPrivateGetter() == true) {
            problems += "$where is not visible to the generated model: make it public or internal"
        }
        val ignored = command.jsonIgnoredProperties()
        if (property.jsonIgnored() || propertyName in ignored || property.wireName() in ignored) {
            problems += "$where is not written by Wow's JSON (@JsonIgnore, @JsonIgnoreProperties): it writes the " +
                "property that holds the aggregate's id, so a sender's body holds it"
        }
        if (unaddressed) {
            problems += "command $name is @Unaddressed and has an @AggregateId property: a transformer finds an " +
                "unaddressed command's aggregate, and the body names an @AggregateId command's"
        }
        return AggregateIdBinding(propertyName, property.wireName(), nullable)
    }

    /** The properties of [command] and its supertypes, the class's own first. */
    private fun propertiesOf(command: KSClassDeclaration): Sequence<KSPropertyDeclaration> =
        command.getAllProperties() + command.getAllSuperTypes()
            .mapNotNull { it.declaration as? KSClassDeclaration }
            .flatMap { it.getAllProperties() }

    /** Whether this property, its getter, or a property it overrides is `@AggregateId`. */
    @OptIn(KspExperimental::class)
    private fun KSPropertyDeclaration.annotated(): Boolean =
        generateSequence(this) { it.findOverridee() as? KSPropertyDeclaration }.take(MAX_OVERRIDES).any { property ->
            property.hasAnnotation(AGGREGATE_ID) || property.getter?.hasAnnotation(AGGREGATE_ID) == true
        }

    private fun com.google.devtools.ksp.symbol.KSPropertyGetter.isPrivateGetter(): Boolean =
        com.google.devtools.ksp.symbol.Modifier.PRIVATE in modifiers

    private fun com.google.devtools.ksp.symbol.KSAnnotation.qualifiedName(): String? =
        annotationType.resolve().declaration.qualifiedName?.asString()

    private fun KSAnnotated.hasAnnotation(qualifiedName: String): Boolean =
        annotations.any { it.qualifiedName() == qualifiedName }

    /** The overrides walked at most, a bound against a cycle the resolver should never give. */
    private const val MAX_OVERRIDES = 16
}
