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

import com.github.victools.jsonschema.generator.CustomDefinition
import com.github.victools.jsonschema.generator.CustomPropertyDefinition
import com.github.victools.jsonschema.generator.FieldScope
import com.github.victools.jsonschema.generator.InstanceAttributeOverrideV2
import com.github.victools.jsonschema.generator.MemberScope
import com.github.victools.jsonschema.generator.MethodScope
import com.github.victools.jsonschema.generator.Option
import com.github.victools.jsonschema.generator.SchemaGenerationContext
import me.ahoo.wow.api.query.LogicalField
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.api.query.schema.QueryTemporal
import me.ahoo.wow.configuration.requiredAggregateType
import me.ahoo.wow.modeling.annotation.aggregateMetadata
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
import java.util.concurrent.ConcurrentHashMap

internal const val TEMPORAL_UNIT = "x-wow-query-temporal-unit"

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

        fun inferDeclaration(model: QueryModel, type: Class<*>): QuerySchemaDeclaration =
            if (model == QueryModel.EVENT_STREAM) {
                inferEventStreamDeclaration(type)
            } else {
                JsonSchemaWalker(schemaGenerator.generateSchema(type)).declaration()
            }

        fun inferEventStreamDeclaration(aggregateType: Class<*>): QuerySchemaDeclaration {
            val rootSchema = schemaGenerator.generateSchema(AggregatedDomainEventStream::class.java, aggregateType)
            val eventSchemas = rootSchema.path("properties").path(MessageRecords.BODY).path("items").path("anyOf")
            val payloadSchema = JsonSerializer.createObjectNode()
            val payloadAlternatives = payloadSchema.putArray("anyOf")
            eventSchemas.forEach { eventSchema ->
                eventSchema.path("properties").path(MessageRecords.BODY)
                    .takeUnless { it.isMissingNode }
                    ?.let(payloadAlternatives::add)
            }
            if (payloadAlternatives.isEmpty) {
                return QuerySchemaDeclaration(emptyMap())
            }
            return JsonSchemaWalker(payloadSchema, rootSchema).declaration(eventPayloadField, includeRoot = false)
        }

        val schemaGenerator by lazy {
            SchemaGeneratorBuilder().objectMapper(JsonSerializer).customizer { config ->
                config.with(Option.DEFINITIONS_FOR_ALL_OBJECTS)
                config.forFields()
                    .withCustomDefinitionProvider { scope, context -> scope.customSerializerDefinition(context) }
                    .withInstanceAttributeOverride(TemporalAttributeOverride<FieldScope>())
                config.forMethods()
                    .withCustomDefinitionProvider { scope, context -> scope.customSerializerDefinition(context) }
                    .withInstanceAttributeOverride(TemporalAttributeOverride<MethodScope>())
                config.forTypesInGeneral().withCustomDefinitionProvider { javaType, context ->
                    javaType.erasedType.registeredSerializerDefinition(context)
                }
            }.build()
        }
    }
}

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
