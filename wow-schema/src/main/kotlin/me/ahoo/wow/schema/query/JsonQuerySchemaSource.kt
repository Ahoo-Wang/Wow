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

import com.fasterxml.jackson.annotation.JsonFormat
import com.fasterxml.jackson.annotation.JsonGetter
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeId
import com.github.victools.jsonschema.generator.CustomDefinition
import com.github.victools.jsonschema.generator.CustomPropertyDefinition
import com.github.victools.jsonschema.generator.FieldScope
import com.github.victools.jsonschema.generator.InstanceAttributeOverrideV2
import com.github.victools.jsonschema.generator.MemberScope
import com.github.victools.jsonschema.generator.MethodScope
import com.github.victools.jsonschema.generator.Option
import com.github.victools.jsonschema.generator.SchemaGenerationContext
import com.github.victools.jsonschema.generator.SchemaGenerator
import io.swagger.v3.oas.annotations.media.Schema
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.mask.MaskStrategy
import me.ahoo.wow.api.query.mask.Masking
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.api.query.schema.QueryTemporal
import me.ahoo.wow.configuration.requiredAggregateType
import me.ahoo.wow.infra.TypeNameMapper.toType
import me.ahoo.wow.infra.reflection.MergedAnnotation.Companion.inheritedAnnotations
import me.ahoo.wow.infra.reflection.MergedAnnotation.Companion.toMergedAnnotation
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import me.ahoo.wow.query.schema.DeclarationValue
import me.ahoo.wow.query.schema.MaskRule
import me.ahoo.wow.query.schema.QueryFieldDeclaration
import me.ahoo.wow.query.schema.QuerySchemaConflictException
import me.ahoo.wow.query.schema.QuerySchemaContext
import me.ahoo.wow.query.schema.QuerySchemaDeclaration
import me.ahoo.wow.query.schema.QuerySchemaException
import me.ahoo.wow.query.schema.QuerySchemaSource
import me.ahoo.wow.query.schema.QuerySchemaSourcePriority
import me.ahoo.wow.query.schema.QuerySchemaUnavailableException
import me.ahoo.wow.schema.SchemaGeneratorBuilder
import me.ahoo.wow.schema.Types.isStdType
import me.ahoo.wow.schema.typed.AggregatedDomainEventStream
import me.ahoo.wow.serialization.JsonSerializer
import me.ahoo.wow.serialization.MessageRecords
import reactor.core.publisher.Flux
import reactor.core.scheduler.Schedulers
import tools.jackson.databind.BeanProperty
import tools.jackson.databind.JavaType
import tools.jackson.databind.ValueDeserializer
import tools.jackson.databind.ValueSerializer
import tools.jackson.databind.annotation.JsonDeserialize
import tools.jackson.databind.annotation.JsonSerialize
import tools.jackson.databind.cfg.EnumFeature
import tools.jackson.databind.cfg.MapperConfig
import tools.jackson.databind.introspect.AnnotatedClass
import tools.jackson.databind.introspect.AnnotatedMember
import tools.jackson.databind.introspect.BeanPropertyDefinition
import tools.jackson.databind.jsonFormatVisitors.JsonFormatVisitorWrapper
import tools.jackson.databind.jsonFormatVisitors.JsonIntegerFormatVisitor
import tools.jackson.databind.node.ObjectNode
import tools.jackson.databind.ser.bean.BeanAsArraySerializer
import tools.jackson.databind.ser.bean.BeanSerializerBase
import tools.jackson.databind.ser.bean.UnrolledBeanAsArraySerializer
import tools.jackson.databind.ser.impl.UnknownSerializer
import tools.jackson.databind.ser.jdk.EnumSerializer
import tools.jackson.databind.ser.std.ReferenceTypeSerializer
import tools.jackson.databind.ser.std.StdContainerSerializer
import tools.jackson.databind.util.Converter
import tools.jackson.databind.util.EnumDefinition
import java.lang.reflect.AnnotatedElement
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.full.declaredMemberProperties
import kotlin.reflect.jvm.javaGetter
import kotlin.reflect.jvm.kotlinFunction
import kotlin.reflect.jvm.kotlinProperty

internal const val TEMPORAL_UNIT = "x-wow-query-temporal-unit"
internal const val MASK_RULE_ATTRIBUTE = "x-wow-query-mask-rule"

class JsonQuerySchemaSource(
    private val typeResolver: (QuerySchemaContext) -> Class<*> = { context ->
        val aggregateType = context.namedAggregate.requiredAggregateType<Any>()
        if (context.model == QueryModel.EVENT_STREAM) {
            aggregateType
        } else {
            aggregateType.aggregateMetadata<Any, Any>().state.aggregateType
        }
    },
    private val declarationResolver: (QueryModel, Class<*>) -> QuerySchemaDeclaration = ::inferDeclaration,
) : QuerySchemaSource {
    private val declarations = ConcurrentHashMap<Pair<QueryModel, Class<*>>, QuerySchemaDeclaration>()

    override val priority: Int = QuerySchemaSourcePriority.JSON_SCHEMA

    override fun load(context: QuerySchemaContext): Flux<QuerySchemaDeclaration> = Flux.defer {
        if (context.model != QueryModel.SNAPSHOT && context.model != QueryModel.EVENT_STREAM) {
            return@defer Flux.empty()
        }
        val type = typeResolver(context)
        val key = context.model to type
        Flux.just(declarations.computeIfAbsent(key) { declarationResolver(context.model, type) })
    }.subscribeOn(Schedulers.boundedElastic()).onErrorMap { error ->
        when (error) {
            is QuerySchemaException -> error
            is Exception -> QuerySchemaUnavailableException(
                "Unable to infer JSON query schema [${context.namedAggregate}/${context.model.value}].",
                error,
            )
            else -> error
        }
    }

    private companion object {
        val eventPayloadField = QueryField("${MessageRecords.BODY}.${MessageRecords.BODY}")
        val eventBodyTypeField = QueryField("${MessageRecords.BODY}.${MessageRecords.BODY_TYPE}")

        fun inferDeclaration(model: QueryModel, type: Class<*>): QuerySchemaDeclaration {
            val maskRuleCatalog = MaskRuleCatalog()
            val maskValidator = MaskMaterializationValidator()
            val schemaGenerator = schemaGenerator(maskRuleCatalog, maskValidator)
            return if (model == QueryModel.EVENT_STREAM) {
                inferEventStreamDeclaration(type, schemaGenerator, maskRuleCatalog, maskValidator)
            } else {
                maskValidator.validate(JsonSerializer.typeFactory.constructType(type))
                JsonSchemaWalker(
                    schemaGenerator.generateSchema(type),
                    maskRuleResolver = maskRuleCatalog::get,
                ).declaration()
            }
        }

        fun inferEventStreamDeclaration(
            aggregateType: Class<*>,
            schemaGenerator: SchemaGenerator,
            maskRuleCatalog: MaskRuleCatalog,
            maskValidator: MaskMaterializationValidator,
        ): QuerySchemaDeclaration {
            val rootSchema = schemaGenerator.generateSchema(AggregatedDomainEventStream::class.java, aggregateType)
            val eventSchemas = rootSchema.path("properties").path(MessageRecords.BODY).path("items").path("anyOf")
            val payloadSchema = JsonSerializer.createObjectNode()
            val payloadAlternatives = payloadSchema.putArray("anyOf")
            val bodyTypes = mutableSetOf<String>()
            eventSchemas.forEach { eventSchema ->
                eventSchema.path("properties").path(MessageRecords.BODY_TYPE).path("const")
                    .takeIf { it.isString }
                    ?.stringValue()
                    ?.let { bodyType ->
                        maskValidator.validate(JsonSerializer.typeFactory.constructType(bodyType.toType<Any>()))
                        bodyTypes.add(bodyType)
                    }
                eventSchema.path("properties").path(MessageRecords.BODY)
                    .takeUnless { it.isMissingNode }
                    ?.let(payloadAlternatives::add)
            }
            if (payloadAlternatives.isEmpty) {
                return QuerySchemaDeclaration(emptyMap())
            }
            val declaration = JsonSchemaWalker(
                payloadSchema,
                rootSchema,
                maskRuleCatalog::get,
            ).declaration(eventPayloadField, includeRoot = false)
            return declaration.copy(
                fields = declaration.fields + Pair(
                    eventBodyTypeField,
                    QueryFieldDeclaration(
                        enumValues = DeclarationValue.Set(
                            bodyTypes.sorted().map { JsonSerializer.valueToTree(it) },
                        ),
                    ),
                ),
            )
        }

        fun schemaGenerator(maskRuleCatalog: MaskRuleCatalog, maskValidator: MaskMaterializationValidator): SchemaGenerator {
            return SchemaGeneratorBuilder().objectMapper(JsonSerializer).customizer { config ->
                config.with(Option.DEFINITIONS_FOR_ALL_OBJECTS)
                config.with(Option.NONSTATIC_NONVOID_NONGETTER_METHODS)
                config.with(Option.FIELDS_DERIVED_FROM_ARGUMENTFREE_METHODS)
                config.forFields()
                    .withCustomDefinitionProvider { scope, context -> scope.customSerializerDefinition(context) }
                    .withInstanceAttributeOverride(TemporalAttributeOverride<FieldScope>())
                    .withInstanceAttributeOverride(MaskAttributeOverride(maskRuleCatalog))
                config.forMethods()
                    .withPropertyNameOverrideResolver { scope ->
                        scope.rawMember.explicitJacksonPropertyName()
                    }
                    .withIgnoreCheck { scope ->
                        scope.findGetterField() == null &&
                            !scope.rawMember.isComputedGetter() &&
                            !scope.rawMember.isExplicitJacksonProperty()
                    }
                    .withCustomDefinitionProvider { scope, context -> scope.customSerializerDefinition(context) }
                    .withInstanceAttributeOverride(TemporalAttributeOverride<MethodScope>())
                    .withInstanceAttributeOverride(MaskAttributeOverride(maskRuleCatalog))
                config.forTypesInGeneral().withCustomDefinitionProvider { javaType, context ->
                    maskValidator.validateGeneratedType(JsonSerializer.typeFactory.constructType(javaType.erasedType))
                    javaType.erasedType.registeredSerializerDefinition(context)
                }
            }.build()
        }
    }
}

private fun Method.isComputedGetter(): Boolean = parameterCount == 0 &&
    when {
        name == "getClass" -> false
        name.startsWith("get") -> name.length > 3
        name.startsWith("is") ->
            name.length > 2 &&
                (returnType == Boolean::class.java || returnType == Boolean::class.javaObjectType)
        else -> false
    }

private fun Method.explicitJacksonProperty(): Annotation? = inheritedAnnotations().firstOrNull {
    it is JsonProperty || it is JsonGetter
}

private fun Method.isExplicitJacksonProperty(): Boolean = explicitJacksonProperty() != null

private fun Method.explicitJacksonPropertyName(): String? {
    val annotation = explicitJacksonProperty() ?: return null
    val explicitName = when (annotation) {
        is JsonProperty -> annotation.value
        is JsonGetter -> annotation.value
        else -> error("Unsupported Jackson property annotation: [$annotation].")
    }
    return explicitName.takeIf(String::isNotEmpty) ?: name.takeIf { !isComputedGetter() }
}

private class MaskRuleCatalog {
    private val rules = mutableListOf<MaskRule>()

    fun add(rule: MaskRule): String = rules.indexOf(rule).takeIf { it >= 0 }?.toString()
        ?: rules.size.also { rules += rule }.toString()

    fun get(id: String): MaskRule = rules[id.toInt()]
}

private fun Annotation.effectiveMaskAnnotations(): List<Pair<Annotation, Masking>> =
    (listOf(this) + annotationClass.toMergedAnnotation().mergedAnnotations).mapNotNull { candidate ->
        candidate.annotationClass.java.getAnnotation(Masking::class.java)?.let { candidate to it }
    }

private class MaskAttributeOverride<M : MemberScope<*, *>>(
    private val catalog: MaskRuleCatalog,
) : InstanceAttributeOverrideV2<M> {
    override fun overrideInstanceAttributes(
        attributes: ObjectNode,
        scope: M,
        context: SchemaGenerationContext,
    ) {
        val effectiveAnnotations = scope.annotationsConsideringFieldAndGetter()
            .flatMap(Annotation::effectiveMaskAnnotations).distinct()
        if (effectiveAnnotations.size > 1) {
            throw QuerySchemaConflictException("Multiple effective mask annotations are not allowed.")
        }
        effectiveAnnotations.singleOrNull()?.let { annotation ->
            if (scope.type.erasedType != String::class.java) {
                throw QuerySchemaConflictException(
                    "Masked query schema member [${scope.rawMember}] must have String JVM type.",
                )
            }
            val rule = annotation.toMaskRule()
            attributes.put(MASK_RULE_ATTRIBUTE, catalog.add(rule))
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun Pair<Annotation, Masking>.toMaskRule(): MaskRule {
        val strategyType = second.strategy
        val strategy = runMaskStrategyOperation(
            "Unable to instantiate MaskStrategy [${strategyType.qualifiedName}].",
        ) {
            strategyType.objectInstance ?: strategyType.java.getConstructor().newInstance()
        }

        val compiled = runMaskStrategyOperation(
            "Unable to compile mask annotation [${first.annotationClass.qualifiedName}] " +
                "with MaskStrategy [${strategyType.qualifiedName}].",
        ) {
            (strategy as MaskStrategy<Annotation>).compile(first)
        }
        return MaskRule(strategyType, first, compiled)
    }

    @Suppress("TooGenericExceptionCaught")
    private inline fun <T> runMaskStrategyOperation(message: String, operation: () -> T): T = try {
        operation()
    } catch (error: Throwable) {
        when (val failure = (error as? InvocationTargetException)?.targetException ?: error) {
            is QuerySchemaException -> throw failure
            is Error -> throw failure
            is Exception -> throw QuerySchemaConflictException(message, failure)
            else -> throw failure
        }
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

private class TemporalAttributeOverride<M : MemberScope<*, *>> : InstanceAttributeOverrideV2<M> {
    override fun overrideInstanceAttributes(
        attributes: ObjectNode,
        scope: M,
        context: SchemaGenerationContext,
    ) {
        scope.getAnnotationConsideringFieldAndGetterIfSupported(QueryTemporal::class.java)
            ?.let { attributes.put(TEMPORAL_UNIT, it.timeUnit.name) }
    }
}

private fun MemberScope<*, *>.customSerializerDefinition(
    context: SchemaGenerationContext,
): CustomPropertyDefinition? {
    val annotation = getAnnotationConsideringFieldAndGetterIfSupported(JsonSerialize::class.java)
    return annotation?.takeIf { it.definesWireShape() }?.let {
        CustomPropertyDefinition(context.generatorConfig.createObjectNode())
    }
}

private fun JsonSerialize.definesWireShape(): Boolean =
    listOf(using, contentUsing, keyUsing, nullsUsing).any { it != ValueSerializer.None::class } ||
        listOf(converter, contentConverter).any { it != Converter.None::class } ||
        listOf(`as`, keyAs, contentAs).any { it != Void::class } ||
        typing != JsonSerialize.Typing.DEFAULT_TYPING

private fun Class<*>.registeredSerializerDefinition(context: SchemaGenerationContext): CustomDefinition? =
    takeIf { hasOpaqueSerializer() }?.let {
        CustomDefinition(context.generatorConfig.createObjectNode())
    }

private fun Class<*>.hasOpaqueSerializer(): Boolean {
    if (isStdType() && !isEnum) {
        return false
    }
    val serializer = JsonSerializer._serializationContext().findValueSerializer(this)
    return serializer.let {
        it is BeanSerializerBase ||
            it is EnumSerializer ||
            it is UnknownSerializer ||
            it is StdContainerSerializer<*> ||
            it is ReferenceTypeSerializer<*>
    }.not()
}

private fun JsonDeserialize.definesWireShape(): Boolean =
    listOf(using, contentUsing, converter, contentConverter).any {
        it != ValueDeserializer.None::class && it != Converter.None::class
    }

/** Validates mask declarations before schema omissions or custom Jackson handlers hide them. */
private class MaskMaterializationValidator {
    private val serialization = JsonSerializer._serializationContext()
    private val deserialization = JsonSerializer.deserializationConfig().let {
        it.classIntrospectorInstance().forOperation(it)
    }
    private val visited = mutableSetOf<Visit>()
    private val properties = mutableMapOf<JavaType, Pair<List<BeanPropertyDefinition>, Set<String>>>()
    private val writable = mutableMapOf<JavaType, Map<String, BeanPropertyDefinition>>()

    fun validateGeneratedType(type: JavaType) {
        // Types already traversed from model properties must retain their contextual wire shape.
        if (visited.any { it.type == type }) {
            return
        }
        validate(type)
    }

    fun validate(
        type: JavaType,
        unsupportedParent: Boolean = false,
        opaqueParent: Boolean = false,
        property: BeanProperty? = null,
    ) {
        val serializer = serialization.findPrimaryPropertySerializer(type, property)
        val opaque = opaqueParent || hasOpaqueSerializer(type, serializer)
        val unsupportedShape = opaque || serializer.hasUnsupportedMemberShape(type)
        val visit = Visit(
            type,
            unsupportedParent,
            opaque,
            unsupportedShape,
            property?.findFormatOverrides(serialization.config),
        )
        if (!visited.add(visit)) {
            return
        }
        val classInfo = classInfo(type, unsupportedShape)
        val unsupportedType = unsupportedParent || unsupportedShape ||
            classInfo?.getAnnotation(JsonDeserialize::class.java)?.definesWireShape() == true
        if (classInfo != null) {
            validateAlternatives(classInfo.annotations().toList(), unsupportedType, opaque)
            if (opaque) {
                validateOpaqueMembers(type, classInfo)
            }
        }
        if (type.isContainerType || type.isReferenceType) {
            // JSON object names cannot carry field mask rules, regardless of the key serializer.
            type.keyType?.let { validate(it, unsupportedParent = true, opaqueParent = true) }
            type.contentType?.let { validate(it, unsupportedType, opaque, property) }
            return
        }
        classInfo ?: return
        validateProperties(type, unsupportedType, opaque)
    }

    private data class Visit(
        val type: JavaType,
        val unsupportedParent: Boolean,
        val opaque: Boolean,
        val unsupportedShape: Boolean,
        val formatOverrides: JsonFormat.Value?,
    )

    // Member mask paths cannot address positional arrays or object-shaped enum schemas.
    private fun ValueSerializer<*>.hasUnsupportedMemberShape(type: JavaType): Boolean =
        this is BeanAsArraySerializer || this is UnrolledBeanAsArraySerializer ||
            (type.isEnumType && this !is EnumSerializer)

    private fun hasOpaqueSerializer(type: JavaType, serializer: ValueSerializer<*>?): Boolean =
        type.rawClass.hasOpaqueSerializer() || usesCustomEnumToString(type, serializer) ||
            serialization.introspectClassAnnotations(type).getAnnotation(JsonSerialize::class.java)
                ?.definesWireShape() == true

    private fun usesCustomEnumToString(type: JavaType, serializer: ValueSerializer<*>?): Boolean {
        if (serializer !is EnumSerializer || !serialization.isEnabled(EnumFeature.WRITE_ENUMS_USING_TO_STRING)) {
            return false
        }
        var numeric = false
        serializer.acceptJsonFormatVisitor(
            object : JsonFormatVisitorWrapper.Base(serialization) {
                override fun expectIntegerFormat(type: JavaType): JsonIntegerFormatVisitor? {
                    numeric = true
                    return null
                }
            },
            type
        )
        if (numeric) {
            return false
        }
        val definition = EnumDefinition.construct(serialization.config, serialization.introspectClassAnnotations(type))
        val explicitNames = definition.explicitNames()
        return definition.enumConstants().any {
            explicitNames[it.ordinal] == null && it.javaClass.getMethod("toString").declaringClass != Enum::class.java
        }
    }

    private fun classInfo(type: JavaType, inspectEnum: Boolean): AnnotatedClass? =
        type.rawClass.takeUnless { it.isStdType() && !(inspectEnum && it.isEnum) }?.let {
            deserialization.introspectClassAnnotations(type)
        }

    private fun validateOpaqueMembers(type: JavaType, classInfo: AnnotatedClass) {
        // Opaque handlers can expose private/ignored members; Jackson retains their resolved generic types here.
        (classInfo.fields() + classInfo.memberMethods().filter { it.parameterCount == 0 }).forEach { member ->
            val annotations = member.maskAnnotations().toMutableList()
            (member.member as? Method)?.let { method ->
                method.declaringClass.kotlin.declaredMemberProperties.firstOrNull { it.javaGetter == method }
                    ?.toMergedAnnotation()?.mergedAnnotations?.let(annotations::addAll)
            }
            rejectUnsupportedMask(type, member.name, annotations, true)
            validateAlternatives(annotations, true, true)
            validate(member.type, unsupportedParent = true, opaqueParent = true)
        }
    }

    private fun validateProperties(type: JavaType, unsupportedType: Boolean, opaque: Boolean) {
        val (serialProperties, schemaIgnored) = serializationProperties(type)
        val writableProperties = writableProperties(type)
        serialProperties.forEach { property ->
            val annotations = property.maskAnnotations() + writableProperties[property.name]?.maskAnnotations().orEmpty()
            // Jackson merges setters/creator parameters into readable members; the schema generator does not.
            val schemaMasks = listOfNotNull(property.field, property.getter)
                .flatMap { it.maskAnnotations(includeJacksonAnnotations = false) }
                .flatMap(Annotation::effectiveMaskAnnotations).toSet()
            val unrepresentedMask = annotations.flatMap(Annotation::effectiveMaskAnnotations).any { it !in schemaMasks }
            val opaqueProperty = opaque || annotations.any { it is JsonTypeId } ||
                annotations.filterIsInstance<JsonSerialize>().any { it.definesWireShape() }
            val unsupported = unsupportedType || opaqueProperty || unrepresentedMask || property.name !in writableProperties ||
                property.internalName in schemaIgnored || property.name in schemaIgnored ||
                annotations.filterIsInstance<Schema>().any { it.hidden || it.accessMode == Schema.AccessMode.WRITE_ONLY } ||
                annotations.filterIsInstance<JsonDeserialize>().any { it.definesWireShape() }
            rejectUnsupportedMask(type, property.name, annotations, unsupported)
            validateAlternatives(annotations, unsupported, opaqueProperty)
            validate(
                property.primaryType,
                unsupported,
                opaqueProperty,
                property.jacksonProperty(),
            )
        }
    }

    private fun rejectUnsupportedMask(type: JavaType, name: String, annotations: List<Annotation>, unsupported: Boolean) {
        if (unsupported && annotations.any { it.effectiveMaskAnnotations().isNotEmpty() }) {
            throw QuerySchemaConflictException(
                "Masked query property [${type.rawClass.name}.$name] requires a visible schema " +
                    "and a writable Jackson property without opaque serialization or deserialization.",
            )
        }
    }

    private fun validateAlternatives(annotations: List<Annotation>, unsupported: Boolean, opaque: Boolean) {
        val alternatives = annotations.filterIsInstance<Schema>().flatMap {
            it.allOf.toList() + it.oneOf.toList() + it.anyOf.toList()
        } +
            annotations.filterIsInstance<JsonSubTypes>().flatMap { subtypes -> subtypes.value.map { it.value } }
        alternatives.forEach { validate(JsonSerializer.typeFactory.constructType(it.java), unsupported, opaque) }
    }

    private fun writableProperties(type: JavaType): Map<String, BeanPropertyDefinition> = writable.getOrPut(type) {
        val config = JsonSerializer.deserializationConfig()
        val target = deserialization.introspectForDeserialization(
            type,
            deserialization.introspectClassAnnotations(type)
        )
        val builder = config.annotationIntrospector.findPOJOBuilder(config, target.classInfo)
        val description = if (builder == null) {
            target
        } else {
            deserialization.introspectForDeserializationWithBuilder(
                JsonSerializer.typeFactory.constructType(builder),
                target,
            )
        }
        val ignored = config.annotationIntrospector
            .findPropertyIgnoralByName(config, description.classInfo).findIgnoredForDeserialization()
        description.findProperties()
            .filter { it.couldDeserialize() && it.name !in ignored }.associateBy { it.name }
    }

    private fun serializationProperties(type: JavaType): Pair<List<BeanPropertyDefinition>, Set<String>> = properties.getOrPut(
        type
    ) {
        val description = serialization.introspectBeanDescription(
            type,
            deserialization.introspectClassAnnotations(type),
        )
        val ignorals = serialization.annotationIntrospector
            .findPropertyIgnoralByName(serialization.config, description.classInfo)
        val ignored = ignorals.findIgnoredForSerialization()
        // WowJacksonModule excludes the original ignored names even when Jackson allows getters/setters.
        description.findProperties().filter {
            it.couldSerialize() && it.name !in ignored && it.findReferenceType()?.isBackReference != true
        } to ignorals.ignored
    }
}

private fun BeanPropertyDefinition.jacksonProperty(): BeanProperty = object : BeanProperty.Std(
    fullName,
    primaryType,
    wrapperName,
    primaryMember,
    metadata,
) {
    override fun findFormatOverrides(config: MapperConfig<*>): JsonFormat.Value? =
        member?.let { config.annotationIntrospector.findFormat(config, it) }
}

private fun BeanPropertyDefinition.maskAnnotations(): List<Annotation> =
    listOfNotNull(field, getter, setter, constructorParameter).flatMap { it.maskAnnotations() }

private fun AnnotatedMember.maskAnnotations(includeJacksonAnnotations: Boolean = true): List<Annotation> = buildSet {
    if (includeJacksonAnnotations) {
        annotations().forEach { add(it) }
    } else {
        (member as? AnnotatedElement)?.annotations?.let(::addAll)
    }
    when (val reflected = member) {
        is Field -> reflected.kotlinProperty?.toMergedAnnotation()?.mergedAnnotations?.let(::addAll)
        is Method -> addAll(
            reflected.kotlinFunction?.toMergedAnnotation()?.mergedAnnotations ?: reflected.inheritedAnnotations()
        )
    }
}.toList()
