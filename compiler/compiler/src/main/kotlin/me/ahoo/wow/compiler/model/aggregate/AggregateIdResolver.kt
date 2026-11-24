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

import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.isPrivate
import com.google.devtools.ksp.isProtected
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.Origin
import me.ahoo.wow.compiler.model.symbol.AnnotationSet
import me.ahoo.wow.compiler.model.symbol.JavaBeans
import me.ahoo.wow.compiler.model.symbol.JavaRecords
import me.ahoo.wow.compiler.model.symbol.declarations
import me.ahoo.wow.compiler.model.symbol.jsonIgnored
import me.ahoo.wow.compiler.model.symbol.jsonIgnoredAt
import me.ahoo.wow.compiler.model.symbol.jsonIgnoredProperties
import me.ahoo.wow.compiler.model.symbol.jsonNaming
import me.ahoo.wow.compiler.model.symbol.suppresses
import me.ahoo.wow.compiler.model.symbol.wireName
import me.ahoo.wow.compiler.model.symbol.wireNameAt

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
 * - it is private or protected, so the generated model cannot read it: a Java field is read by its public getter;
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
        val components = markedComponents(command)
        if (annotated.size + components.size > 1) {
            val names = annotated.map { it.simpleName.asString() } + components.map { it.name }
            problems += "command $name has several @AggregateId properties (${names.joinToString()}): one property " +
                "holds its aggregate's id"
            return null
        }
        components.singleOrNull()?.let { return componentBinding(command, it, creation, unaddressed, problems) }
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
        if (!property.readable(command)) {
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
     * The module's functions marked `@AggregateId` that are no Java record's component accessor, each with its
     * problem: the mark's `FUNCTION` target is there for a record's component, which javac carries to its accessor.
     */
    fun reportMisplaced(resolver: Resolver, logger: KSPLogger) =
        misplaced(resolver).forEach { (problem, function) -> logger.error(problem, function) }

    internal fun misplaced(resolver: Resolver): List<Pair<String, KSFunctionDeclaration>> =
        resolver.getSymbolsWithAnnotation(AGGREGATE_ID).filterIsInstance<KSFunctionDeclaration>()
            .filterNot { function ->
                val owner = function.parentDeclaration as? KSClassDeclaration
                owner != null && JavaRecords.isComponentSite(owner, function)
            }
            .map { function ->
                val where = function.qualifiedName?.asString() ?: function.simpleName.asString()
                AnnotationSet.commandPropertyOnly("function $where", AGGREGATE_ID_NAME) to function
            }.toList()

    /** The components of [command], a Java record, marked `@AggregateId`; none for any other command. */
    private fun markedComponents(command: KSClassDeclaration): List<JavaRecords.Component> =
        JavaRecords.components(command).filter { component -> component.sites.any { it.hasAnnotation(AGGREGATE_ID) } }

    /**
     * The binding of a Java record [command]'s marked [component]: read as Kotlin reads a record's component, by its
     * name, and written by Wow's JSON under its name. Its problems are a property's: no `String`, or the command
     * `@Unaddressed`.
     */
    private fun componentBinding(
        command: KSClassDeclaration,
        component: JavaRecords.Component,
        creation: String,
        unaddressed: Boolean,
        problems: MutableList<String>,
    ): AggregateIdBinding {
        val name = command.simpleName.asString()
        val where = "command $name's @AggregateId record component ${component.name}"
        if (component.type !in STRINGS) {
            problems += "$where is a ${component.type}: an aggregate id is a String"
        }
        if (unaddressed) {
            problems += "command $name is @Unaddressed and has an @AggregateId property: a transformer finds an " +
                "unaddressed command's aggregate, and the body names an @AggregateId command's"
        }
        // Named as Jackson writes the component (#6839): its @JsonProperty, else the record's @JsonNaming.
        val ignored = command.jsonIgnoredProperties(problems)
        val wireName = wireNameAt(component.name, component.sites, problems, command.jsonNaming(problems))
        if (jsonIgnoredAt(component.sites, problems) || component.name in ignored || wireName in ignored) {
            problems += "$where is not written by Wow's JSON (@JsonIgnore, @JsonIgnoreProperties): it writes the " +
                "component that holds the aggregate's id, so a sender's body holds it"
        }
        // A Java String is a platform type: a creation may leave it out, any other command must name one.
        return AggregateIdBinding(component.name, wireName, nullable = creation != "NONE")
    }

    /**
     * The report of a [command] that has a `String` property named `id`, stored or computed, its own or a supertype's
     * (9.x's `Identifier.id`), and no `@AggregateId` property: Wow 10 does not take a command's aggregate from its body
     * by a property's name, as 9.x did. 9.x read that `id` from the command object by reflection, so it bound one Wow's
     * JSON does not write (`@JsonIgnore`, `@JsonIgnoreProperties`) or the generated model cannot read (private) too:
     * such an `id` is reported as well, and the report says the mark alone will not do there. So is a Java record's
     * unmarked `id` component, which is marked on the component ([JavaRecords]). Its class may be
     * compiled here or in a library, an api module's jar, as a context's commands are. None where the command's class
     * suppresses it ([UNMARKED_AGGREGATE_ID]), or one of its [handlers] does: a library's `@Suppress` is kept only in
     * its source, so its command function is where a command of a library suppresses it. A `@Suppress` without a value
     * is one of [problems].
     *
     * The caller makes it an error for a command that [creates] its aggregate and a warning for any other, and the
     * two severities are the two outcomes of the same send. A creation sent without an aggregate id, as a saga's
     * `body.toCommandRequest()` or a client's route without an id sends it, takes a generated id: a new aggregate
     * where 9.x addressed the one its `id` named, silently, so the build refuses it until the `id` is marked or the
     * command says it is not the aggregate's. Any other command sent without an id is refused where it is sent, by
     * admission or for want of a route, so the warning only says so earlier.
     */
    fun unmarkedId(
        command: KSClassDeclaration,
        handlers: List<KSFunctionDeclaration>,
        creates: Boolean,
        problems: MutableList<String>,
    ): String? {
        val suppressed = command.suppresses(UNMARKED_AGGREGATE_ID, problems) ||
            handlers.any { it.suppresses(UNMARKED_AGGREGATE_ID, problems) }
        val properties = propertiesOf(command)
        if (suppressed || properties.any { it.annotated() } || markedComponents(command).isNotEmpty()) {
            return null
        }
        val id = properties.firstOrNull {
            it.simpleName.asString() == ID && it.type.resolve().declaration.qualifiedName?.asString() in STRINGS
        }
        val (clause, mark) = id?.let { unmarked(command, it, problems) } ?: recordUnmarked(command) ?: return null
        val name = command.simpleName.asString()
        val has = "has $clause and no @AggregateId property"
        val suppress = "suppress this with @Suppress(\"$UNMARKED_AGGREGATE_ID\") on the command class, or on its " +
            "command function where the class is compiled in another module."
        if (creates) {
            return "command $name creates its aggregate and $has: sent without an aggregate id, as a saga's " +
                "body.toCommandRequest() or a client's route without an id sends it, it creates a new aggregate " +
                "under a generated id, whatever its $ID holds. If $ID names $name's aggregate, $mark; if it names " +
                "something else, $suppress"
        }
        return "command $name $has: Wow 10 does not take a command's aggregate from its body unless a property is " +
            "marked @AggregateId. If $ID names $name's aggregate, $mark, as a 9.x command whose body named its " +
            "aggregate needs. If 9.x bound it from the path, it only repeated the route: remove it and read " +
            "command.ref.id. Otherwise $suppress"
    }

    /**
     * What [command]'s unmarked [id] is, and how it is marked: as it is, or once the generated model can read it and
     * Wow's JSON writes it, as [of] refuses it otherwise.
     */
    private fun unmarked(
        command: KSClassDeclaration,
        id: KSPropertyDeclaration,
        problems: MutableList<String>,
    ): Pair<String, String> {
        val ignored = command.jsonIgnoredProperties(problems)
        val hindrance = when {
            !id.readable(command) -> "not visible to the generated model"
            id.jsonIgnored(problems) || ID in ignored ||
                id.wireName(problems, command.jsonNaming(problems)) in ignored -> "not written by Wow's JSON"
            else -> null
        }
        val mark = if (hindrance == null) {
            "mark it @AggregateId (an inherited $ID is marked where the command overrides it)"
        } else {
            "make it a public property Wow's JSON writes, then mark it @AggregateId: the mark is refused on a " +
                "property the generated model cannot read or a body does not carry"
        }
        return "a String property $ID ${originOf(command, id, hindrance)}" to mark
    }

    /**
     * What [command]'s `id` record component is, when it is a Java record with one ([JavaRecords.component]), and how
     * it is marked: on the component; null for any other command.
     */
    private fun recordUnmarked(command: KSClassDeclaration): Pair<String, String>? {
        if (JavaRecords.component(command, ID, STRINGS) == null) return null
        val elsewhere = if (command.origin in SOURCE) "" else ", compiled in another module"
        val clause = "a String record component $ID (read by ${command.qualifiedName!!.asString()}'s accessor " +
            "$ID()$elsewhere)"
        return clause to "mark the component @AggregateId (record ${command.simpleName.asString()}(@AggregateId " +
            "String $ID, …))"
    }

    /**
     * Where [command]'s [property] comes from: declared by the command's class or a supertype, an interface's default
     * getter among them, or a Java field's getter; a library's class is said to be compiled elsewhere, and the
     * [hindrance] that keeps the mark off it follows.
     */
    private fun originOf(command: KSClassDeclaration, property: KSPropertyDeclaration, hindrance: String?): String {
        val owner = property.parentDeclaration as KSClassDeclaration
        val reader = property.javaReader(command)
        val declared = when {
            // A Java bean's private field, which Wow's JSON and the model read through its getter.
            reader != null && property.isPrivate() -> "read by ${owner.qualifiedName!!.asString()}'s $reader"
            owner == command -> "declared by ${owner.qualifiedName!!.asString()}"
            owner.classKind != ClassKind.INTERFACE -> "inherited from the class ${owner.qualifiedName!!.asString()}"
            // A class that does not override an interface's property inherits its default getter.
            else -> "inherited from the interface ${owner.qualifiedName!!.asString()}'s default getter"
        }
        val elsewhere = "compiled in another module".takeIf { command.origin !in SOURCE }
        return listOfNotNull(declared, elsewhere, hindrance).joinToString(", ", "(", ")")
    }

    /**
     * Whether the generated model, and Wow's JSON, read this property of [command]: a public or internal one (a
     * Kotlin property's getter is as visible as the property), or a Java field, whatever its visibility, that a
     * public method of [command] reads ([javaReader]), which a Kotlin caller reads as the property.
     */
    private fun KSPropertyDeclaration.readable(command: KSClassDeclaration): Boolean =
        !isPrivate() && !isProtected() || javaReader(command) != null

    /**
     * The public getter of [command] that reads this Java field (`getId()`), as Kotlin reads it as a property and
     * Wow's JSON writes it; null for a Kotlin property, or a field no public getter reads.
     */
    private fun KSPropertyDeclaration.javaReader(command: KSClassDeclaration): String? =
        JavaBeans.getterOf(this, command)?.let { "getter ${it.simpleName.asString()}()" }

    /** The properties of [command] and its supertypes, the class's own first. */
    internal fun propertiesOf(command: KSClassDeclaration): Sequence<KSPropertyDeclaration> =
        command.getAllProperties() + command.getAllSuperTypes()
            .mapNotNull { it.declaration as? KSClassDeclaration }
            .flatMap { it.getAllProperties() }

    /** Whether this property or a declaration it overrides ([declarations]), or its getter, is `@AggregateId`. */
    private fun KSPropertyDeclaration.annotated(): Boolean =
        declarations().any { property ->
            property.hasAnnotation(AGGREGATE_ID) || property.getter?.hasAnnotation(AGGREGATE_ID) == true
        }

    private fun com.google.devtools.ksp.symbol.KSAnnotation.qualifiedName(): String? =
        annotationType.resolve().declaration.qualifiedName?.asString()

    internal fun KSAnnotated.hasAnnotation(qualifiedName: String): Boolean =
        annotations.any { it.qualifiedName() == qualifiedName }

    /** The name of the property 9.x took a command's aggregate id from when no property was marked. */
    private const val ID = "id"

    /** The origins of a declaration whose source this compilation reads. */
    private val SOURCE: Set<Origin> = setOf(Origin.KOTLIN, Origin.JAVA)

    /** The simple name of [AGGREGATE_ID], as [AnnotationSet] reports it. */
    private const val AGGREGATE_ID_NAME = "AggregateId"
}
