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

import com.fasterxml.jackson.annotation.JsonGetter
import com.fasterxml.jackson.annotation.JsonProperty
import com.github.victools.jsonschema.generator.CustomDefinition
import com.github.victools.jsonschema.generator.CustomPropertyDefinition
import com.github.victools.jsonschema.generator.FieldScope
import com.github.victools.jsonschema.generator.InstanceAttributeOverrideV2
import com.github.victools.jsonschema.generator.MemberScope
import com.github.victools.jsonschema.generator.MethodScope
import com.github.victools.jsonschema.generator.Option
import com.github.victools.jsonschema.generator.SchemaGenerationContext
import com.github.victools.jsonschema.generator.SchemaGenerator
import me.ahoo.wow.api.query.LogicalField
import me.ahoo.wow.api.query.mask.MaskStrategy
import me.ahoo.wow.api.query.mask.Masking
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.api.query.schema.QueryTemporal
import me.ahoo.wow.configuration.requiredAggregateType
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
import tools.jackson.databind.ValueSerializer
import tools.jackson.databind.annotation.JsonSerialize
import tools.jackson.databind.node.ObjectNode
import tools.jackson.databind.ser.bean.BeanSerializerBase
import tools.jackson.databind.ser.impl.UnknownSerializer
import tools.jackson.databind.ser.std.ReferenceTypeSerializer
import tools.jackson.databind.ser.std.StdContainerSerializer
import tools.jackson.databind.util.Converter
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap
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
        val eventPayloadField = LogicalField("${MessageRecords.BODY}.${MessageRecords.BODY}")
        val eventBodyTypeField = LogicalField("${MessageRecords.BODY}.${MessageRecords.BODY_TYPE}")

        fun inferDeclaration(model: QueryModel, type: Class<*>): QuerySchemaDeclaration {
            val maskRuleCatalog = MaskRuleCatalog()
            val schemaGenerator = schemaGenerator(maskRuleCatalog)
            return if (model == QueryModel.EVENT_STREAM) {
                inferEventStreamDeclaration(type, schemaGenerator, maskRuleCatalog)
            } else {
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
                    ?.let(bodyTypes::add)
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

        fun schemaGenerator(maskRuleCatalog: MaskRuleCatalog): SchemaGenerator =
            SchemaGeneratorBuilder().objectMapper(JsonSerializer).customizer { config ->
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
                    javaType.erasedType.registeredSerializerDefinition(context)
                }
            }.build()
    }
}

private fun Method.isComputedGetter(): Boolean = Modifier.isPublic(modifiers) && parameterCount == 0 &&
    when {
        name == "getClass" -> false
        name.startsWith("get") -> name.length > 3
        name.startsWith("is") ->
            name.length > 2 &&
                (returnType == Boolean::class.java || returnType == Boolean::class.javaObjectType)
        else -> false
    }

private fun Method.isExplicitJacksonProperty(): Boolean =
    isAnnotationPresent(JsonProperty::class.java) || isAnnotationPresent(JsonGetter::class.java)

private fun Method.explicitJacksonPropertyName(): String? =
    getAnnotation(JsonProperty::class.java)?.value?.takeIf(String::isNotEmpty)
        ?: getAnnotation(JsonGetter::class.java)?.value?.takeIf(String::isNotEmpty)
        ?: name.takeIf { isExplicitJacksonProperty() && !isComputedGetter() }

private class MaskRuleCatalog {
    private val rules = mutableListOf<MaskRule>()

    fun add(rule: MaskRule): String = rules.indexOf(rule).takeIf { it >= 0 }?.toString()
        ?: rules.size.also { rules += rule }.toString()

    fun get(id: String): MaskRule = rules[id.toInt()]
}

private class MaskAttributeOverride<M : MemberScope<*, *>>(
    private val catalog: MaskRuleCatalog,
) : InstanceAttributeOverrideV2<M> {
    override fun overrideInstanceAttributes(
        attributes: ObjectNode,
        scope: M,
        context: SchemaGenerationContext,
    ) {
        val effectiveAnnotations = scope.annotationsConsideringFieldAndGetter().flatMap { annotation ->
            (listOf(annotation) + annotation.annotationClass.toMergedAnnotation().mergedAnnotations)
                .mapNotNull { candidate ->
                    candidate.annotationClass.java.getAnnotation(Masking::class.java)?.let { candidate to it }
                }
        }.distinct()
        if (effectiveAnnotations.size > 1) {
            throw QuerySchemaConflictException("Multiple effective mask annotations are not allowed.")
        }
        effectiveAnnotations.singleOrNull()?.toMaskRule()?.let { rule ->
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
    listOf(contentUsing, keyUsing, converter, contentConverter, using).any {
        it != ValueSerializer.None::class.java && it != Converter.None::class.java
    }

private fun Class<*>.registeredSerializerDefinition(context: SchemaGenerationContext): CustomDefinition? {
    if (isStdType()) {
        return null
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
