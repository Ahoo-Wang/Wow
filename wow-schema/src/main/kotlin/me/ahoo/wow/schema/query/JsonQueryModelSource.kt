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

package me.ahoo.wow.schema.query

import com.fasterxml.jackson.annotation.JsonValue
import com.github.victools.jsonschema.generator.CustomDefinition
import com.github.victools.jsonschema.generator.CustomPropertyDefinition
import com.github.victools.jsonschema.generator.FieldScope
import com.github.victools.jsonschema.generator.InstanceAttributeOverrideV2
import com.github.victools.jsonschema.generator.MemberScope
import com.github.victools.jsonschema.generator.MethodScope
import com.github.victools.jsonschema.generator.Option
import com.github.victools.jsonschema.generator.SchemaGenerationContext
import com.github.victools.jsonschema.generator.SchemaGenerator
import me.ahoo.wow.infra.reflection.MergedAnnotation.Companion.inheritedAnnotations
import me.ahoo.wow.infra.reflection.MergedAnnotation.Companion.toMergedAnnotation
import me.ahoo.wow.query.schema.QueryMemberFact
import me.ahoo.wow.query.schema.QueryModelSource
import me.ahoo.wow.query.schema.QueryTypeFact
import me.ahoo.wow.schema.SchemaGeneratorBuilder
import me.ahoo.wow.schema.Types.isStdType
import me.ahoo.wow.serialization.JsonSerializer
import tools.jackson.databind.ValueSerializer
import tools.jackson.databind.annotation.JsonSerialize
import tools.jackson.databind.introspect.AnnotatedMethod
import tools.jackson.databind.node.ObjectNode
import tools.jackson.databind.ser.bean.BeanSerializerBase
import tools.jackson.databind.ser.impl.UnknownSerializer
import tools.jackson.databind.ser.std.ReferenceTypeSerializer
import tools.jackson.databind.ser.std.StdContainerSerializer
import tools.jackson.databind.util.Converter
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.kotlinFunction
import kotlin.reflect.jvm.kotlinProperty

/** Generator attribute that links a member's schema node to the [QueryMemberFact] the walker reports for it. */
internal const val MEMBER_ATTRIBUTE = "x-wow-query-member"

/**
 * The default [QueryModelSource]: infers a type's serialized shape from the JSON Schema that Wow's generator writes
 * with [JsonSerializer], so field names follow Jackson (renames, ignored and write-only members, custom serializers).
 * Every serialized member is reported with its JVM type and effective annotations.
 */
class JsonQueryModelSource : QueryModelSource {
    override fun describe(type: Class<*>): QueryTypeFact {
        val members = MemberCatalog()
        return JsonSchemaWalker(
            schema = schemaGenerator(members).generateSchema(type),
            memberResolver = members::get,
            rootPath = type.simpleName,
        ).fact()
    }

    private companion object {
        private fun schemaGenerator(members: MemberCatalog): SchemaGenerator =
            SchemaGeneratorBuilder().objectMapper(JsonSerializer).customizer { config ->
                config.with(Option.DEFINITIONS_FOR_ALL_OBJECTS)
                config.with(Option.NONSTATIC_NONVOID_NONGETTER_METHODS)
                config.with(Option.FIELDS_DERIVED_FROM_ARGUMENTFREE_METHODS)
                config.forFields()
                    .withCustomDefinitionProvider { scope, context -> scope.customSerializerDefinition(context) }
                    .withInstanceAttributeOverride(MemberAttributeOverride(members))
                val serializedGetters = SerializedGetters()
                config.forMethods()
                    .withPropertyNameOverrideResolver(serializedGetters::propertyName)
                    .withIgnoreCheck { scope -> serializedGetters.propertyName(scope) == null }
                    .withCustomDefinitionProvider { scope, context -> scope.customSerializerDefinition(context) }
                    .withInstanceAttributeOverride(MemberAttributeOverride(members))
                config.forTypesInGeneral().withCustomDefinitionProvider { javaType, context ->
                    javaType.erasedType.registeredSerializerDefinition(context)
                }
            }.build()
    }
}

/**
 * Maps argument-free methods to the Jackson property they serialize.
 *
 * Snapshot documents hold what [JsonSerializer] writes, so a method is a query field only when it is the
 * serialization accessor of a Jackson property, and it takes Jackson's external name rather than a
 * JavaBeans-derived one: a Kotlin `isRetryable` stays `isRetryable`, while `@JsonIgnore` getters and
 * methods such as `isEmpty()` that Jackson does not serialize are left out.
 */
private class SerializedGetters {
    private val propertyNames = ConcurrentHashMap<Class<*>, Map<String, String>>()

    fun propertyName(scope: MethodScope): String? {
        if (scope.argumentCount > 0) {
            return null
        }
        val targetType = scope.declaringTypeMembers.allTypesAndOverrides().first().type.erasedType
        return propertyNames.computeIfAbsent(targetType, ::serializedGetterNames)[scope.rawMember.name]
    }

    private fun serializedGetterNames(type: Class<*>): Map<String, String> {
        val serializationConfig = JsonSerializer.serializationConfig()
        val classIntrospector = serializationConfig.classIntrospectorInstance()
        val javaType = JsonSerializer.typeFactory.constructType(type)
        return classIntrospector
            .introspectForSerialization(javaType, classIntrospector.introspectClassAnnotations(javaType))
            .findProperties()
            .mapNotNull { property ->
                (property.accessor as? AnnotatedMethod)?.let { it.name to property.name }
            }.toMap()
    }
}

private class MemberCatalog {
    private val members = mutableListOf<QueryMemberFact>()

    fun add(member: QueryMemberFact): String = synchronized(members) {
        members.size.also { members += member }.toString()
    }

    fun get(id: String): QueryMemberFact = synchronized(members) { members[id.toInt()] }
}

/** Records each serialized member's JVM type and effective annotations for the walker. */
private class MemberAttributeOverride<M : MemberScope<*, *>>(
    private val catalog: MemberCatalog,
) : InstanceAttributeOverrideV2<M> {
    override fun overrideInstanceAttributes(
        attributes: ObjectNode,
        scope: M,
        context: SchemaGenerationContext,
    ) {
        val member = QueryMemberFact(
            name = scope.rawMember.toString(),
            type = scope.type.erasedType,
            annotations = scope.annotationsConsideringFieldAndGetter(),
            valueType = scope.valueType(),
        )
        attributes.put(MEMBER_ATTRIBUTE, catalog.add(member))
    }
}

/**
 * The class the member's values are declared with: the Kotlin classifier (a value class erases to its underlying type
 * on the JVM), or the element class of a collection or array.
 */
private fun MemberScope<*, *>.valueType(): Class<*> {
    val kotlinType = runCatching {
        when (this) {
            is FieldScope -> rawMember.kotlinProperty?.returnType
            is MethodScope ->
                rawMember.kotlinFunction?.returnType
                    ?: findGetterField()?.rawMember?.kotlinProperty?.returnType
            else -> null
        }
    }.getOrNull()
    if (kotlinType != null) {
        val declared = kotlinType.classifier as? KClass<*>
        val element = kotlinType.arguments.singleOrNull()?.type?.classifier as? KClass<*>
        val container = declared?.let { Iterable::class.java.isAssignableFrom(it.java) || it.java.isArray } == true
        (if (container) element else declared)?.let { return it.java }
    }
    val resolved = type
    return when {
        resolved.isArray -> resolved.arrayElementType.erasedType
        Iterable::class.java.isAssignableFrom(resolved.erasedType) ->
            resolved.typeParameters.singleOrNull()?.erasedType ?: resolved.erasedType
        else -> resolved.erasedType
    }
}

private fun MemberScope<*, *>.annotationsConsideringFieldAndGetter(): List<Annotation> = buildSet {
    when (this@annotationsConsideringFieldAndGetter) {
        is FieldScope -> {
            rawMember.kotlinProperty?.toMergedAnnotation()?.mergedAnnotations?.let(::addAll)
            addAll(rawMember.annotations)
            findGetter()?.rawMember?.annotations?.let(::addAll)
        }

        is MethodScope -> {
            addAll(
                rawMember.kotlinFunction?.toMergedAnnotation()?.mergedAnnotations
                    ?: rawMember.inheritedAnnotations(),
            )
            findGetterField()?.rawMember?.kotlinProperty
                ?.toMergedAnnotation()?.mergedAnnotations?.let(::addAll)
            addAll(rawMember.annotations)
            findGetterField()?.rawMember?.annotations?.let(::addAll)
        }
    }
}.toList()

private fun MemberScope<*, *>.customSerializerDefinition(
    context: SchemaGenerationContext,
): CustomPropertyDefinition? {
    val annotation = getAnnotationConsideringFieldAndGetterIfSupported(JsonSerialize::class.java)
    return annotation?.takeIf { it.definesWireShape() }?.let {
        CustomPropertyDefinition(context.generatorConfig.createObjectNode())
    }
}

private fun JsonSerialize.definesWireShape(): Boolean =
    listOf(contentUsing, keyUsing, converter, contentConverter, using).any {
        it != ValueSerializer.None::class.java && it != Converter.None::class.java
    }

private fun Class<*>.registeredSerializerDefinition(context: SchemaGenerationContext): CustomDefinition? {
    if (isStdType()) {
        return null
    }
    if (serializesAsString()) {
        return CustomDefinition(context.generatorConfig.createObjectNode().put("type", "string"))
    }
    val serializer = runCatching { JsonSerializer._serializationContext().findValueSerializer(this) }.getOrNull()
    return serializer?.takeUnless {
        it is BeanSerializerBase ||
            it is UnknownSerializer ||
            it is StdContainerSerializer<*> ||
            it is ReferenceTypeSerializer<*>
    }?.let {
        CustomDefinition(context.generatorConfig.createObjectNode())
    }
}

/**
 * Whether this type writes one JSON string: a Kotlin value class over a `String`, or a type whose `@JsonValue`
 * accessor returns a `String`. Its wire shape is then a known string rather than an opaque custom serializer.
 */
private fun Class<*>.serializesAsString(): Boolean {
    val kotlinClass = runCatching { kotlin }.getOrNull()
    if (kotlinClass != null && runCatching { kotlinClass.isValue }.getOrDefault(false)) {
        return kotlinClass.primaryConstructor?.parameters?.singleOrNull()?.type?.classifier == String::class
    }
    return generateSequence(this) { it.superclass }.any { type ->
        type.declaredMethods.any {
            it.isAnnotationPresent(JsonValue::class.java) && it.parameterCount == 0 && it.returnType == String::class.java
        } || type.declaredFields.any {
            it.isAnnotationPresent(JsonValue::class.java) && it.type == String::class.java
        }
    }
}
