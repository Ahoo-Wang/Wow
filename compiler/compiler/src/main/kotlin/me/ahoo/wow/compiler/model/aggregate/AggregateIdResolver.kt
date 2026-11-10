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
import com.google.devtools.ksp.symbol.Origin
import me.ahoo.wow.compiler.model.symbol.jsonIgnored
import me.ahoo.wow.compiler.model.symbol.jsonIgnoredProperties
import me.ahoo.wow.compiler.model.symbol.jsonNaming
import me.ahoo.wow.compiler.model.symbol.suppresses
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

    /** The name a command suppresses the warning of an `id` it does not mark `@AggregateId` with ([unmarkedId]). */
    const val UNMARKED_AGGREGATE_ID = "UnmarkedAggregateId"

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
        val ignored = command.jsonIgnoredProperties(problems)
        val wireName = property.wireName(problems, command.jsonNaming(problems))
        if (property.jsonIgnored(problems) || propertyName in ignored || wireName in ignored) {
            problems += "$where is not written by Wow's JSON (@JsonIgnore, @JsonIgnoreProperties): it writes the " +
                "property that holds the aggregate's id, so a sender's body holds it"
        }
        if (unaddressed) {
            problems += "command $name is @Unaddressed and has an @AggregateId property: a transformer finds an " +
                "unaddressed command's aggregate, and the body names an @AggregateId command's"
        }
        return AggregateIdBinding(propertyName, wireName, nullable)
    }

    /**
     * The warning of a [command] that has a `String` property named `id`, stored or computed, its own or a supertype's
     * (9.x's `Identifier.id`), and no `@AggregateId` property: Wow 10 does not take a command's aggregate from its body
     * by a property's name, as 9.x did. None where the command suppresses it ([UNMARKED_AGGREGATE_ID]), nor where its
     * source is not compiled here: its author marks it, and a library's `@Suppress` does not reach this compilation. A
     * `@Suppress` without a value is one of [problems].
     */
    fun unmarkedId(command: KSClassDeclaration, problems: MutableList<String>): String? {
        if (command.origin !in SOURCE || command.suppresses(UNMARKED_AGGREGATE_ID, problems)) {
            return null
        }
        val properties = propertiesOf(command)
        val id = properties.any {
            it.simpleName.asString() == ID && it.type.resolve().declaration.qualifiedName?.asString() in STRINGS
        }
        if (!id || properties.any { it.annotated() }) {
            return null
        }
        val name = command.simpleName.asString()
        return "command $name has a String property $ID and no @AggregateId property: Wow 10 does not take a " +
            "command's aggregate from its body unless a property is marked @AggregateId. If $ID names $name's " +
            "aggregate, mark it @AggregateId, as a 9.x command whose body named its aggregate needs, a creation a " +
            "saga sends without an aggregate id among them. If 9.x bound it from the path, it only repeated the " +
            "route: remove it and read command.ref.id. Otherwise suppress this with " +
            "@Suppress(\"$UNMARKED_AGGREGATE_ID\")."
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

    /** The name of the property 9.x took a command's aggregate id from when no property was marked. */
    private const val ID = "id"

    /** The origins of a declaration whose source this compilation reads. */
    private val SOURCE: Set<Origin> = setOf(Origin.KOTLIN, Origin.JAVA)
}
