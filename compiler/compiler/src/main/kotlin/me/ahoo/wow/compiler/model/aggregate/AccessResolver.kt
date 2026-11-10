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

import com.google.devtools.ksp.getDeclaredProperties
import com.google.devtools.ksp.isPrivate
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeAlias
import com.google.devtools.ksp.symbol.Modifier
import me.ahoo.wow.api.annotation.Access
import me.ahoo.wow.api.annotation.Blocking
import me.ahoo.wow.compiler.model.symbol.argumentsOf
import me.ahoo.wow.compiler.model.symbol.isModuleInternal
import me.ahoo.wow.compiler.model.symbol.isOf
import me.ahoo.wow.manifest.AccessAttributeName
import me.ahoo.wow.manifest.QueryFieldNames

/**
 * An access attribute of an aggregate, a state member marked `@Access`: the generated model reads it in
 * `access(<State>Access.<constant>) { state -> … }`. [member] is the member's name and [property] whether it is a
 * property, read without a call; [name] the attribute's published name; [valueType] the Kotlin class of each value
 * (`kotlin.String`, an enum's qualified name); [type] its Manifest type (`STRING`, `INTEGER`, `BOOLEAN`); [multiple]
 * whether it holds several values and [nullable] whether a single one may be absent; [internalValue] whether the class
 * of its values is internal, so its reference is. A member whose values are a value class wrapping one of those
 * (`@JvmInline value class DepartmentId(val value: String)`) publishes the wrapped value, which [unwrap], the value
 * class's property, reads.
 */
internal data class AccessBinding(
    val member: String,
    val property: Boolean,
    val name: String,
    val valueType: String,
    val type: String,
    val multiple: Boolean,
    val internalValue: Boolean = false,
    val nullable: Boolean = false,
    val unwrap: String? = null,
) {
    /** The constant of `<State>Access` that holds its reference: [name] in upper snake case, as a query field's. */
    val constant: String get() = QueryFieldNames.upperSnake(name)

    /** How the generated model reads the attribute's value from `state`: the member, its value classes unwrapped. */
    val read: String
        get() {
            val member = if (property) "state.$member" else "state.$member()"
            return when {
                unwrap == null -> member
                multiple -> "$member.map { it.$unwrap }"
                nullable -> "$member?.$unwrap"
                else -> "$member.$unwrap"
            }
        }
}

/**
 * Finds and checks the `@Access` members of an aggregate's state, its own and inherited ones: properties and functions
 * without parameters, neither private, extension, suspending nor `@Blocking`, of a value type an access attribute
 * takes ([valueOf]), each named as `@Access` says, by the rule of [AccessAttributeName], the names unique ignoring
 * case. That the member is pure and cheap (no clock, no randomness) the compiler cannot see: `@Access` says so.
 */
internal object AccessResolver {
    private val BLOCKING = Blocking::class.java.name
    private const val WHERE = "@Access"

    /** The value types by qualified name, Kotlin's and Java's, to the Kotlin class and the Manifest type. */
    private val SCALARS: Map<String, Pair<String, String>> = mapOf(
        "kotlin.String" to ("kotlin.String" to "STRING"),
        "java.lang.String" to ("kotlin.String" to "STRING"),
        "kotlin.Int" to ("kotlin.Int" to "INTEGER"),
        "java.lang.Integer" to ("kotlin.Int" to "INTEGER"),
        "kotlin.Long" to ("kotlin.Long" to "INTEGER"),
        "java.lang.Long" to ("kotlin.Long" to "INTEGER"),
        "kotlin.Boolean" to ("kotlin.Boolean" to "BOOLEAN"),
        "java.lang.Boolean" to ("kotlin.Boolean" to "BOOLEAN"),
    )
    private val COLLECTIONS = setOf(
        "kotlin.collections.List",
        "kotlin.collections.MutableList",
        "kotlin.collections.Set",
        "kotlin.collections.MutableSet",
        "kotlin.collections.Collection",
        "kotlin.collections.MutableCollection",
        "java.util.List",
        "java.util.Set",
        "java.util.Collection",
    )
    private const val RETURNS = "a String, Int, Long, Boolean or enum, a value class wrapping one, or a List, Set or " +
        "Collection of them"

    /** The access attributes of [state], properties before functions, each in declaration order; problems to [problems]. */
    fun of(state: KSClassDeclaration, problems: MutableList<String>): List<AccessBinding> {
        val properties = state.getAllProperties().filter { it.isAccess() }.mapNotNull { property(it, problems) }
        val functions = state.getAllFunctions().filter { it.isAccess() }.mapNotNull { function(it, problems) }
        val bindings = (properties + functions).toList()
        bindings.groupBy { it.name.lowercase() }.values.filter { it.size > 1 }.forEach { same ->
            problems += "the state ${state.simpleName.asString()} declares the access attributes " +
                "${same.joinToString { it.name }}, whose names differ only in case: a name is unique ignoring case"
        }
        return bindings
    }

    private fun property(property: KSPropertyDeclaration, problems: MutableList<String>): AccessBinding? {
        val member = property.simpleName.asString()
        val where = "$WHERE property $member"
        val found = listOfNotNull(
            "$where is an extension: it is a member of the state".takeIf { property.extensionReceiver != null },
            "$where is private: the generated model reads it".takeIf { property.isPrivate() },
        )
        problems += found
        return binding(property, member, true, where, property.type.resolve(), problems).takeIf { found.isEmpty() }
    }

    private fun function(function: KSFunctionDeclaration, problems: MutableList<String>): AccessBinding? {
        val member = function.simpleName.asString()
        val where = "$WHERE function $member"
        val found = listOfNotNull(
            "$where takes parameters: it reads the state only".takeIf { function.parameters.isNotEmpty() },
            "$where is an extension: it is a member of the state".takeIf { function.extensionReceiver != null },
            "$where suspends: deriving an access attribute does no I/O".takeIf { Modifier.SUSPEND in function.modifiers },
            "$where is annotated @Blocking: deriving an access attribute does no I/O".takeIf {
                function.annotations.any { it.isOf(BLOCKING) }
            },
            "$where is private: the generated model calls it".takeIf { function.isPrivate() },
        )
        problems += found
        val returned = function.returnType?.resolve()
        if (returned == null) {
            problems += "$where returns nothing, not $RETURNS"
            return null
        }
        return binding(function, member, false, where, returned, problems).takeIf { found.isEmpty() }
    }

    /** The binding of [declaration], named as its `@Access` says, else [member]; null, told, when it has a problem. */
    private fun binding(
        declaration: KSDeclaration,
        member: String,
        property: Boolean,
        where: String,
        returned: KSType,
        problems: MutableList<String>,
    ): AccessBinding? {
        val arguments = declaration.argumentsOf(Access::class, problems) ?: return null
        val name = arguments.string("name").ifEmpty { member }
        val nameProblem = AccessAttributeName.problem(name)?.let { "$where: $it; give another with @Access(\"name\")" }
        val value = valueOf(returned)
        val typeProblem = "$where returns ${returned.render()}, not $RETURNS".takeIf { value == null }
        problems += listOfNotNull(nameProblem, typeProblem)
        if (nameProblem != null || value == null) {
            return null
        }
        return AccessBinding(
            member,
            property,
            name,
            value.scalar.kotlinType,
            value.scalar.type,
            value.multiple,
            value.scalar.internal,
            value.nullable,
            value.scalar.unwrap,
        )
    }

    /** What an attribute holds, when its type is a value type it takes: its [scalar], one or several. */
    private class Value(val scalar: Scalar, val multiple: Boolean, val nullable: Boolean)

    /**
     * A scalar an attribute takes: the [kotlinType] of its published values, their Manifest [type], whether the class
     * is [internal], and, for a value class wrapping one, the property that [unwrap]s it.
     */
    private data class Scalar(
        val kotlinType: String,
        val type: String,
        val internal: Boolean = false,
        val unwrap: String? = null,
    )

    /**
     * The value of an attribute of [type]: a scalar ([scalarOf]), nullable for none, or a collection of
     * non-null scalars, itself not nullable; null for any other type.
     */
    private fun valueOf(type: KSType): Value? {
        val resolved = type.expanded()
        if (resolved.qualifiedName !in COLLECTIONS) {
            return scalarOf(resolved)?.let { Value(it, multiple = false, nullable = resolved.isMarkedNullable) }
        }
        if (resolved.isMarkedNullable) {
            return null
        }
        val element = resolved.arguments.singleOrNull()?.type?.resolve()?.expanded() ?: return null
        if (element.isMarkedNullable) {
            return null
        }
        return scalarOf(element)?.let { Value(it, multiple = true, nullable = false) }
    }

    /**
     * The scalar of [type]: a String, Int, Long or Boolean, an enum, or a value class wrapping one of those in a
     * property it does not hide ([wrappedBy]); null for another type.
     */
    private fun scalarOf(type: KSType, wrapped: Boolean = false): Scalar? {
        SCALARS[type.qualifiedName]?.let { (kotlinType, manifestType) -> return Scalar(kotlinType, manifestType) }
        val declaration = type.declaration as? KSClassDeclaration ?: return null
        if (declaration.isPrivate()) {
            return null
        }
        if (declaration.classKind == ClassKind.ENUM_CLASS) {
            return Scalar(declaration.qualifiedName!!.asString(), "STRING", declaration.isModuleInternal())
        }
        return if (wrapped) null else wrappedBy(declaration)
    }

    /**
     * The scalar the value class [declaration] wraps, read by the property of its one constructor parameter: one
     * neither nullable nor a value class itself, in a property that is not private; null for any other class.
     */
    private fun wrappedBy(declaration: KSClassDeclaration): Scalar? {
        if (Modifier.VALUE !in declaration.modifiers && Modifier.INLINE !in declaration.modifiers) {
            return null
        }
        val parameter = declaration.primaryConstructor?.parameters?.singleOrNull() ?: return null
        val property = declaration.getDeclaredProperties()
            .firstOrNull { it.simpleName.asString() == parameter.name?.asString() }
            ?.takeUnless { it.isPrivate() } ?: return null
        val wrapped = property.type.resolve().expanded().takeUnless { it.isMarkedNullable } ?: return null
        return scalarOf(wrapped, wrapped = true)?.let {
            it.copy(internal = it.internal || declaration.isModuleInternal(), unwrap = property.simpleName.asString())
        }
    }

    /** This type with a type alias expanded, its nullability kept. */
    private fun KSType.expanded(): KSType {
        val alias = declaration as? KSTypeAlias ?: return this
        val target = alias.type.resolve().expanded()
        return if (isMarkedNullable) target.makeNullable() else target
    }

    private val KSType.qualifiedName: String? get() = declaration.qualifiedName?.asString()

    private fun KSType.render(): String {
        val name = declaration.simpleName.asString()
        val rendered = if (arguments.isEmpty()) {
            name
        } else {
            name + arguments.joinToString(", ", "<", ">") { it.type?.resolve()?.render() ?: "*" }
        }
        return if (isMarkedNullable) "$rendered?" else rendered
    }

    private fun KSDeclaration.isAccess(): Boolean = annotations.any { it.isOf(Access::class.java.name) }
}
