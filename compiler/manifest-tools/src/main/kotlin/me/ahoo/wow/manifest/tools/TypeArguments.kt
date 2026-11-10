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

package me.ahoo.wow.manifest.tools

import com.fasterxml.classmate.ResolvedType
import com.github.victools.jsonschema.generator.CustomDefinition
import com.github.victools.jsonschema.generator.CustomDefinitionProviderV2
import com.github.victools.jsonschema.generator.CustomPropertyDefinition
import com.github.victools.jsonschema.generator.CustomPropertyDefinitionProvider
import com.github.victools.jsonschema.generator.MemberScope
import com.github.victools.jsonschema.generator.SchemaGenerationContext
import com.github.victools.jsonschema.generator.TypeContext
import tools.jackson.databind.node.ObjectNode
import kotlin.reflect.KType

/**
 * The nullability of a type's arguments where the type is used, which the JVM erases and Kotlin's type keeps: a
 * `Box<String?>` and a `Box<String>` are two schemas, and a `List<Item?>` holds nulls where a `List<Item>` does not.
 *
 * - Where a member is declared, each type argument its Kotlin type marks nullable, at any depth, is resolved as a
 *   [Nullable] of itself ([declared]): `Box<String?>` is resolved as `Box<Nullable<String>>`. A parameterized class is
 *   so a definition of its own, keyed `a.Box(java.lang.String?)`, whose members typed by the parameter accept null,
 *   while `Box<String>` keeps `a.Box(java.lang.String)` and its non-null members; a [Nullable] value is written as a
 *   nullable value is ([nullable]).
 * - A property's list, set, object array or map is written where it is used ([Containers]), never as a definition of
 *   its erased type, each element or value nullable as its type argument is.
 */
internal object TypeArguments : CustomDefinitionProviderV2 {
    private const val TYPE = "type"
    private const val ITEMS = "items"
    private const val ADDITIONAL_PROPERTIES = "additionalProperties"
    private const val ANY_OF = "anyOf"
    private const val ARRAY = "array"
    private const val OBJECT = "object"
    private const val NULL = "null"
    private const val REF = "\$ref"
    private const val NULLABLE_MARK = '?'
    private const val GENERATOR_MARK = '*'
    private val PLATFORM_PACKAGES = listOf("java.", "javax.", "kotlin.")

    /** A type argument that accepts null where its type is used: a marker of the resolved types, never built. */
    @Suppress("unused")
    class Nullable<T> private constructor()

    /** The type a [Nullable] of it holds, null for any other type. */
    fun nullableValue(type: ResolvedType): ResolvedType? =
        type.typeParameters.singleOrNull()?.takeIf { type.erasedType == Nullable::class.java }

    /**
     * The key the generator is given for the definition [key]: the generator keeps no `?` in a key, so a nullable
     * argument is spelled with [GENERATOR_MARK], which no binary name holds, until [restored] spells it `?` again.
     */
    fun generatorKey(key: String): String = key.replace(NULLABLE_MARK, GENERATOR_MARK)

    /** The [generated] definitions with their keys, and the references to them, spelled as [generatorKey] was given. */
    fun restored(generated: Map<String, Map<String, Any?>>): Map<String, Map<String, Any?>> =
        generated.entries.associate { (key, schema) ->
            @Suppress("UNCHECKED_CAST")
            key.replace(GENERATOR_MARK, NULLABLE_MARK) to restoredRefs(schema) as Map<String, Any?>
        }

    private fun restoredRefs(node: Any?): Any? = when (node) {
        is Map<*, *> -> node.entries.associate { (key, value) ->
            key.toString() to if (key == REF && value is String) value.replace(GENERATOR_MARK, NULLABLE_MARK) else restoredRefs(value)
        }
        is List<*> -> node.map(::restoredRefs)
        else -> node
    }

    /** [member]'s type with each argument its Kotlin type marks nullable a [Nullable] of it; null when it has none. */
    fun declared(member: MemberScope<*, *>): List<ResolvedType>? {
        val kotlinType = KotlinMembers.typeOf(member) ?: return null
        return marked(member.type, kotlinType, member.context)?.let(::listOf)
    }

    /** [type] with the arguments [kotlinType] marks nullable as [Nullable]s, at any depth; null when none is. */
    private fun marked(type: ResolvedType, kotlinType: KType, context: TypeContext): ResolvedType? {
        val parameters = type.typeParameters
        if (parameters.isEmpty() || parameters.size != kotlinType.arguments.size) return null
        val arguments = parameters.mapIndexed { index, parameter ->
            val argument = kotlinType.arguments[index].type ?: return@mapIndexed parameter
            val inner = marked(parameter, argument, context) ?: parameter
            // `Any?` is any value already, null included: nothing to mark.
            val nullable = argument.isMarkedNullable && inner.erasedType != Any::class.java
            if (nullable) context.resolve(Nullable::class.java, inner) else inner
        }
        return if (arguments == parameters) null else context.resolve(type.erasedType, *arguments.toTypedArray())
    }

    /** A [Nullable] value, wherever the generator meets one: a member typed by a nullable argument. */
    override fun provideCustomSchemaDefinition(
        type: ResolvedType,
        context: SchemaGenerationContext
    ): CustomDefinition? {
        val value = nullableValue(type) ?: return null
        return CustomDefinition(
            nullable(value, null, context),
            CustomDefinition.DefinitionType.INLINE,
            CustomDefinition.AttributeInclusion.NO,
        )
    }

    /**
     * The schema of a value of [type], of the Kotlin type [kotlinType] when known, or `null`, as a nullable property is
     * written: a container or a scalar by a type union, any other value by a `null` alternative beside its reference.
     */
    private fun nullable(type: ResolvedType, kotlinType: KType?, context: SchemaGenerationContext): ObjectNode {
        Containers.container(type, kotlinType, context)?.let { return context.makeNullable(it) }
        if (isScalar(type.erasedType)) return context.makeNullable(context.createDefinition(type))
        // The reference is filled in, or replaced by a definition's reference, once the generation ends.
        return context.generatorConfig.createObjectNode().apply {
            putArray(ANY_OF)
                .add(context.generatorConfig.createObjectNode().put(TYPE, NULL))
                .add(context.createDefinitionReference(type))
        }
    }

    /**
     * A value of the platform written as a JSON scalar: a number, a string, a boolean, an enumeration, a time. A
     * nullable value is never a primitive: a type argument, or an object array's element, is a boxed class.
     */
    private fun isScalar(type: Class<*>): Boolean = type.isEnum || PLATFORM_PACKAGES.any { type.name.startsWith(it) }

    /**
     * A property's list, set, object array or map, written where it is used, never as a definition of its erased type
     * (two `Map<String, Long>` and one `Map<String, Long?>` are three schemas, not one shared definition): each element
     * or value accepts null when its type argument is nullable, at any depth (`List<Map<String, Item?>>`), the way a
     * nullable property does. A collection's or a map's arguments are [Nullable]s where they accept null ([declared]);
     * an object array's element is nullable as its Kotlin type argument is, which an array type cannot carry. A Java
     * or platform type argument is not nullable; a primitive array is left to the generator.
     */
    object Containers {
        /** The provider of the properties [M] are: fields, or the getters of computed properties. */
        fun <M : MemberScope<*, *>> of(): CustomPropertyDefinitionProvider<M> =
            CustomPropertyDefinitionProvider { member, context ->
                if (member.isFakeContainerItemScope) return@CustomPropertyDefinitionProvider null
                container(member.type, KotlinMembers.typeOf(member), context)
                    ?.let { CustomPropertyDefinition(it, CustomDefinition.AttributeInclusion.YES) }
            }

        /** The schema of [type] when it is a container, an array's element typed by [kotlinType]'s, else null. */
        fun container(type: ResolvedType, kotlinType: KType?, context: SchemaGenerationContext): ObjectNode? {
            val erased = type.erasedType
            val (keyword, element) = when {
                erased.isArray && !erased.componentType.isPrimitive -> ITEMS to type.arrayElementType
                Collection::class.java.isAssignableFrom(erased) ->
                    ITEMS to context.typeContext.getContainerItemType(type)
                // A map has its two parameters, resolved (to `Object` when raw).
                Map::class.java.isAssignableFrom(erased) ->
                    ADDITIONAL_PROPERTIES to type.typeParametersFor(Map::class.java)[1]
                else -> return null
            }
            val elementKotlinType = kotlinType?.arguments?.getOrNull(if (keyword == ITEMS) 0 else 1)?.type
            return context.generatorConfig.createObjectNode().apply {
                put(TYPE, if (keyword == ITEMS) ARRAY else OBJECT)
                // Any value at all, null included, is left unsaid, as the generator leaves it out of a free map.
                element.takeIf { it.erasedType != Any::class.java }?.let {
                    set(keyword, element(it, elementKotlinType, context))
                }
                if (Set::class.java.isAssignableFrom(erased)) put(WireTypes.UNIQUE_ITEMS, true)
            }
        }

        private fun element(type: ResolvedType, kotlinType: KType?, context: SchemaGenerationContext): ObjectNode {
            nullableValue(type)?.let { return nullable(it, kotlinType, context) }
            if (kotlinType?.isMarkedNullable == true) return nullable(type, kotlinType, context)
            return container(type, kotlinType, context) ?: context.createDefinitionReference(type)
        }
    }
}
