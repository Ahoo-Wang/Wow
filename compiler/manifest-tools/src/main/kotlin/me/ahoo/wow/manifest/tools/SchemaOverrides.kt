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
import com.fasterxml.jackson.annotation.JsonRawValue
import com.github.victools.jsonschema.generator.CustomDefinition
import com.github.victools.jsonschema.generator.CustomDefinitionProviderV2
import com.github.victools.jsonschema.generator.CustomPropertyDefinition
import com.github.victools.jsonschema.generator.CustomPropertyDefinitionProvider
import com.github.victools.jsonschema.generator.FieldScope
import com.github.victools.jsonschema.generator.MemberScope
import com.github.victools.jsonschema.generator.SchemaGenerationContext
import me.ahoo.wow.api.wire.WireNames
import me.ahoo.wow.spi.json.WowEnums
import me.ahoo.wow.spi.json.WowJson
import tools.jackson.databind.BeanDescription
import tools.jackson.databind.JavaType
import tools.jackson.databind.ValueSerializer
import tools.jackson.databind.annotation.JsonSerialize
import tools.jackson.databind.introspect.AnnotatedMember
import tools.jackson.databind.introspect.BeanPropertyDefinition
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.jsonFormatVisitors.JsonFormatVisitorWrapper
import tools.jackson.databind.jsonFormatVisitors.JsonObjectFormatVisitor
import tools.jackson.databind.node.ObjectNode
import tools.jackson.databind.util.Converter
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

/**
 * The schemas a module declares in `wow { schemaOverrides }`, and the generation's stops that ask for one: a schema is
 * declared, not guessed, wherever Jackson writes a value with a serializer of its own (#6372, #6377).
 *
 * - A key names a class by its binary name (`a.b.Money`), or a property: the class that declares the accessor Jackson
 *   writes it by, `#`, and the property's name as that class declares it, whatever its JSON name (`a.b.Order#id`). An
 *   inherited property is so named by the class that declares it, once for every subclass, and one a class overrides
 *   by that class, an interface's annotated getter by each class that implements it.
 * - A declared schema stands for the whole class, or the whole property as written on the wire, whenever its key is
 *   present: a property's list items and map values included. The generator adds to a declared property only what it
 *   adds to any property: `null` to the types of one that can be null, and the keywords of its query annotations
 *   ([QueryKeywords]). A property's key wins over its type's.
 * - A key that names nothing here is ignored: a convention plugin's shared block may name classes a module does not
 *   hold.
 * - Without a declaration, the generation stops for a class of the application that Jackson does not write as an
 *   object (a custom serializer, a `@JsonValue`), for any class, an interface, an abstract class and an enum among
 *   them, that Jackson writes with a serializer or a converter of its own (`@JsonSerialize`, its own or a
 *   supertype's), and for a property written by a serializer of its own (`@JsonSerialize` with `using`,
 *   `converter`, `contentUsing` or `contentConverter`, or `@JsonRawValue`). Each stop prints the line that declares
 *   it. A property's key serializer (`keyUsing`) is no stop: a JSON object's keys are strings, which the schema
 *   does not constrain.
 * - It also stops for a property holding a platform class, itself or as its container's element, that Wow's JSON
 *   writes as no object and that is no scalar Wow describes ([WireTypes.SCALARS]): a `Charset`, an `AtomicLong`, a
 *   `kotlin.time.Duration` in a list. The generator would describe it by its fields, which are not what is written.
 */
internal class SchemaOverrides(declared: Map<String, ObjectNode>, private val mapper: JsonMapper) {
    private val classes: Map<String, ObjectNode> = declared.filterKeys { PROPERTY !in it }
    private val properties: Map<String, ObjectNode> = declared.filterKeys { PROPERTY in it }
    private val descriptions = ConcurrentHashMap<Class<*>, BeanDescription>()

    /** The definition of a declared class, of a Wow enumeration, or none: the generator's own. */
    val types: CustomDefinitionProviderV2 = CustomDefinitionProviderV2 { type, _ -> definitionOf(type) }

    /** The definition of a declared property, or none: its type's. */
    fun <M : MemberScope<*, *>> members(): CustomPropertyDefinitionProvider<M> =
        CustomPropertyDefinitionProvider { member, _: SchemaGenerationContext -> definitionOf(member) }

    private fun definitionOf(type: ResolvedType): CustomDefinition? {
        val rawClass = type.erasedType
        classes[rawClass.name]?.let { return CustomDefinition(it.deepCopy()) }
        // A Wow enumeration is written by its wire names; a domain's, by its constants' names.
        if (rawClass in WowEnums.TYPES) {
            val values = rawClass.enumConstants.map { it as Enum<*> }.filter { it.name != UNKNOWN }
            return CustomDefinition(
                mapper.createObjectNode().put("type", "string").apply {
                    putArray("enum").apply { values.forEach { add(WireNames.of(it)) } }
                },
            )
        }
        check(!isApplicationClass(rawClass) || isWrittenAsObject(rawClass)) {
            "Jackson does not write ${rawClass.name} as an object (a custom serializer or @JsonValue), so its " +
                "schema cannot be introspected: ${declaration(rawClass.name)}."
        }
        check(!isOwnClass(rawClass) || !hasOwnSerializer(rawClass)) {
            "Jackson writes ${rawClass.name} with a serializer of its own (@JsonSerialize using or converter), " +
                "so its schema cannot be introspected: ${declaration(rawClass.name)}."
        }
        return null
    }

    private fun definitionOf(member: MemberScope<*, *>): CustomPropertyDefinition? {
        val declaring = member.declaringType.erasedType
        if (!isOwnClass(declaring)) return null
        val property = propertyOf(declaring, member) ?: return null
        val accessor = property.accessor ?: return null
        val key = "${accessor.declaringClass.name}$PROPERTY${property.internalName}"
        properties[key]?.let { return CustomPropertyDefinition(it.deepCopy()) }
        check(!hasOwnSerializer(accessor)) {
            "Jackson writes $key with a serializer of its own (@JsonSerialize using, converter, contentUsing or " +
                "contentConverter, or @JsonRawValue), so its schema cannot be introspected: ${declaration(key)}."
        }
        unwritten(member.type)?.let { type ->
            error(
                "Wow's JSON does not write ${type.name}, which $key holds, as an object, and it is no scalar Wow " +
                    "describes, so its schema would not be what is written: ${declaration(key)}, or hold it as a " +
                    "String.",
            )
        }
        return null
    }

    /**
     * The platform class in [type], itself or an element of its container (a collection's, an `Optional`'s, a map's
     * value, an array's), that Wow's JSON does not write as an object while the generator describes it as one: no
     * scalar of [WireTypes.SCALARS], no enumeration, no class of a declared schema. A `char[]` is one: Jackson writes
     * it as a string, though its format visitor calls it an array.
     */
    private fun unwritten(type: ResolvedType): Class<*>? {
        val rawClass = type.erasedType
        return when {
            rawClass == CharArray::class.java -> rawClass
            rawClass.isArray -> type.arrayElementType?.let(::unwritten)
            Map::class.java.isAssignableFrom(rawClass) -> type.typeParameters.getOrNull(1)?.let(::unwritten)
            rawClass in CONTAINERS || Collection::class.java.isAssignableFrom(rawClass) ->
                type.typeParameters.firstOrNull()?.let(::unwritten)
            rawClass.isPrimitive || rawClass.isEnum || isOwnClass(rawClass) -> null
            rawClass in WireTypes.SCALARS || rawClass in GENERATOR_TYPES || rawClass.name in classes -> null
            isWrittenAsObject(rawClass, WowJson.mapper) -> null
            else -> rawClass
        }
    }

    /** The property Jackson writes by [member]: a field of [declaring], or the getter of a computed property. */
    private fun propertyOf(declaring: Class<*>, member: MemberScope<*, *>): BeanPropertyDefinition? {
        val properties = descriptions.computeIfAbsent(declaring) {
            val config = mapper.serializationConfig()
            val introspector = config.classIntrospectorInstance()
            val type = mapper.typeFactory.constructType(it)
            introspector.introspectForSerialization(type, introspector.introspectClassAnnotations(type))
        }.findProperties()
        return if (member is FieldScope) {
            properties.firstOrNull { it.internalName == member.declaredName }
        } else {
            properties.firstOrNull { it.getter?.name == member.rawMember.name }
        }
    }

    private fun isApplicationClass(rawClass: Class<*>): Boolean =
        isOwnClass(rawClass) && !rawClass.isEnum && !rawClass.isInterface && !Modifier.isAbstract(rawClass.modifiers)

    /** A class of the application, of any kind: no primitive, array or platform class. */
    private fun isOwnClass(rawClass: Class<*>): Boolean =
        !rawClass.isPrimitive && !rawClass.isArray && PLATFORM_PACKAGES.none { rawClass.name.startsWith(it) }

    /** Whether Jackson's annotations give [rawClass] a serializer or a converter of its own. */
    private fun hasOwnSerializer(rawClass: Class<*>): Boolean {
        val config = mapper.serializationConfig()
        val annotations = config.classIntrospectorInstance()
            .introspectClassAnnotations(mapper.typeFactory.constructType(rawClass))
        val introspector = config.annotationIntrospector
        return introspector.findSerializer(config, annotations) != null ||
            introspector.findSerializationConverter(config, annotations) != null
    }

    /**
     * Whether a property's own annotations, which Jackson merges onto its [accessor] from its field, getter and
     * constructor parameter, give it a serializer of its own. Read from the annotations, not the introspector, which
     * also answers with the Kotlin module's own converters, such as a value class's, which the generator describes as
     * Jackson writes them.
     */
    private fun hasOwnSerializer(accessor: AnnotatedMember): Boolean {
        val serialize = accessor.getAnnotation(JsonSerialize::class.java)
        val serializes = serialize != null && (
            serialize.using.java != ValueSerializer.None::class.java ||
                serialize.contentUsing.java != ValueSerializer.None::class.java ||
                serialize.converter.java != Converter.None::class.java ||
                serialize.contentConverter.java != Converter.None::class.java
            )
        return serializes || accessor.getAnnotation(JsonRawValue::class.java)?.value == true
    }

    private fun isWrittenAsObject(rawClass: Class<*>, writer: JsonMapper = mapper): Boolean {
        var isObject = false
        writer.acceptJsonFormatVisitor(
            rawClass,
            object : JsonFormatVisitorWrapper.Base() {
                override fun expectObjectFormat(type: JavaType): JsonObjectFormatVisitor? {
                    isObject = true
                    return null
                }
            },
        )
        return isObject
    }

    private companion object {
        /** The separator of a property's key: the class's binary name, `#`, the property's declared name. */
        const val PROPERTY = "#"

        /** The value a reader older than its writer reads an unknown value as, which no writer writes. */
        const val UNKNOWN = "UNKNOWN"
        val PLATFORM_PACKAGES = listOf("java.", "javax.", "kotlin.")

        /**
         * The containers, beside a collection, whose elements the generator describes: their first type argument. An
         * `Iterable` only as declared: a `Path` is one, which Jackson writes as a string.
         */
        val CONTAINERS = setOf(Iterable::class.java, Sequence::class.java, java.util.Optional::class.java)

        /** The platform classes the generator describes as Jackson writes them, though no scalar of Wow's: any value. */
        val GENERATOR_TYPES = setOf(Any::class.java, Number::class.java, CharSequence::class.java, Void::class.java)

        fun declaration(key: String): String = "declare it in wow { schemaOverrides.put(\"$key\", \"<JSON Schema>\") }"
    }
}
