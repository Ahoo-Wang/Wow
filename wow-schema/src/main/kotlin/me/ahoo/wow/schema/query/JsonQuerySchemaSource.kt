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
import me.ahoo.wow.api.query.schema.QueryCardinality
import me.ahoo.wow.api.query.schema.QuerySemanticType
import me.ahoo.wow.api.query.schema.QueryTemporal
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.api.query.schema.Temporal
import me.ahoo.wow.configuration.requiredAggregateType
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import me.ahoo.wow.query.schema.DeclarationValue
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
import me.ahoo.wow.serialization.JsonSerializer
import me.ahoo.wow.serialization.state.StateAggregateRecords
import reactor.core.publisher.Flux
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ValueSerializer
import tools.jackson.databind.annotation.JsonSerialize
import tools.jackson.databind.node.ObjectNode
import tools.jackson.databind.ser.bean.BeanSerializerBase
import tools.jackson.databind.ser.impl.UnknownSerializer
import tools.jackson.databind.ser.std.ReferenceTypeSerializer
import tools.jackson.databind.ser.std.StdContainerSerializer
import tools.jackson.databind.util.Converter
import java.util.concurrent.TimeUnit

private const val TEMPORAL_UNIT = "x-wow-query-temporal-unit"
private val COMPOSITIONS = listOf("allOf", "anyOf", "oneOf")

class JsonQuerySchemaSource internal constructor(
    private val stateTypeResolver: (QuerySchemaContext) -> Class<*>,
) : QuerySchemaSource {
    constructor() : this({ context ->
        context.namedAggregate.requiredAggregateType<Any>()
            .aggregateMetadata<Any, Any>().state.aggregateType
    })

    override val priority: Int = QuerySchemaSourcePriority.JSON_SCHEMA

    override fun load(context: QuerySchemaContext): Flux<QuerySchemaDeclaration> = Flux.defer {
        val rootSchema = schemaGenerator.generateSchema(stateTypeResolver(context))
        Flux.just(JsonSchemaWalker(rootSchema).declaration())
    }.onErrorMap { error ->
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

private class JsonSchemaWalker(
    private val rootSchema: JsonNode,
) {
    private val fields = linkedMapOf<LogicalField, QueryFieldDeclaration>()

    fun declaration(): QuerySchemaDeclaration {
        val stateField = LogicalField(StateAggregateRecords.STATE)
        val rootNodes = rootSchema.effectiveNodes()
        fields[stateField] = QueryFieldDeclaration(
            title = DeclarationValue.Set(rootNodes.consistentValue(stateField, "title") { it.textValueOrNull("title") }),
            description = DeclarationValue.Set(
                rootNodes.consistentValue(stateField, "description") { it.textValueOrNull("description") },
            ),
            enumValues = DeclarationValue.Set(
                rootNodes.consistentValue(stateField, "enumValues", JsonNode::enumValuesOrNull),
            ),
            dynamicChildren = DeclarationValue.Set(rootNodes.any(JsonNode::hasAdditionalProperties)),
        )
        rootSchema.collectProperties(StateAggregateRecords.STATE, setOf("#"))
        return QuerySchemaDeclaration(fields)
    }

    private fun JsonNode.collectProperties(
        parentName: String,
        resolvingReferences: Set<String>,
    ) {
        val parentRequired = get("required")?.asSequence()
            ?.filter(JsonNode::isString)
            ?.map(JsonNode::stringValue)
            ?.toSet()
            .orEmpty()
        get("properties")?.properties()?.forEach { (propertyName, propertySchema) ->
            if (propertyName.isLogicalFieldSegment() && !propertySchema.isWriteOnly()) {
                val fullName = "$parentName.$propertyName"
                val field = LogicalField(fullName)
                fields.merge(
                    field,
                    propertySchema.toDeclaration(field, propertyName in parentRequired),
                ) { current, next ->
                    current.mergeStructural(next, field)
                }
                propertySchema.collectProperties(fullName, resolvingReferences)
            }
        }
        reference()?.takeIf { it !in resolvingReferences }?.let { reference ->
            rootSchema.at(reference.removePrefix("#"))
                .takeUnless(JsonNode::isMissingNode)
                ?.collectProperties(parentName, resolvingReferences + reference)
        }
        COMPOSITIONS.forEach { composition ->
            get(composition)?.forEach { alternative ->
                alternative.collectProperties(parentName, resolvingReferences)
            }
        }
        get("items")?.collectProperties(parentName, resolvingReferences)
    }

    private fun JsonNode.toDeclaration(
        field: LogicalField,
        required: Boolean,
    ): QueryFieldDeclaration {
        val nodes = effectiveNodes()
        val arrayShape = nodes.any(JsonNode::isArrayShape)
        val itemNodes = if (arrayShape) {
            nodes.flatMap { it.arrayItems() }.flatMap { it.effectiveNodes() }
        } else {
            emptyList()
        }
        val shapeNodes = if (arrayShape) nodes + itemNodes else nodes
        val valueTypes = shapeNodes.flatMap { it.nonNullValueTypes() }.toSet()
        val temporalUnit = (nodes + itemNodes).consistentValue(field, "semanticType") {
            it.textValueOrNull(TEMPORAL_UNIT)
        }
        val inferredTemporal = shapeNodes.consistentValue(field, "semanticType", JsonNode::inferredTemporal)
        val semanticType = temporalUnit?.let { unit ->
            if (valueTypes != setOf(QueryValueType.INTEGER)) {
                throw QuerySchemaConflictException(
                    "@QueryTemporal requires an integer JSON wire shape.",
                )
            }
            Temporal.Epoch(TimeUnit.valueOf(unit))
        } ?: inferredTemporal
        return QueryFieldDeclaration(
            title = DeclarationValue.Set(nodes.consistentValue(field, "title") { it.textValueOrNull("title") }),
            description = DeclarationValue.Set(
                nodes.consistentValue(field, "description") { it.textValueOrNull("description") },
            ),
            enumValues = DeclarationValue.Set(
                nodes.consistentValue(field, "enumValues", JsonNode::enumValuesOrNull),
            ),
            valueTypes = DeclarationValue.Set(valueTypes),
            nullable = DeclarationValue.Set(nodes.any(JsonNode::allowsNull)),
            required = DeclarationValue.Set(required),
            cardinality = DeclarationValue.Set(
                if (arrayShape) QueryCardinality.MANY else QueryCardinality.SINGLE,
            ),
            semanticType = DeclarationValue.Set(semanticType),
            dynamicChildren = DeclarationValue.Set(shapeNodes.any(JsonNode::hasAdditionalProperties)),
        )
    }

    private fun JsonNode.effectiveNodes(
        resolvingReferences: Set<String> = emptySet(),
    ): List<JsonNode> = buildList {
        add(this@effectiveNodes)
        reference()?.takeIf { it !in resolvingReferences }?.let { reference ->
            rootSchema.at(reference.removePrefix("#"))
                .takeUnless(JsonNode::isMissingNode)
                ?.let { addAll(it.effectiveNodes(resolvingReferences + reference)) }
        }
        COMPOSITIONS.forEach { composition ->
            get(composition)?.forEach { alternative ->
                addAll(alternative.effectiveNodes(resolvingReferences))
            }
        }
    }

    private fun JsonNode.reference(): String? =
        get("\$ref")?.takeIf(JsonNode::isString)?.stringValue()
            ?.takeIf { it == "#" || it.startsWith("#/") }

    private fun JsonNode.isWriteOnly(): Boolean = effectiveNodes().any {
        it.get("writeOnly")?.takeIf(JsonNode::isBoolean)?.booleanValue() == true
    }
}

private fun QueryFieldDeclaration.mergeStructural(
    other: QueryFieldDeclaration,
    field: LogicalField,
): QueryFieldDeclaration = QueryFieldDeclaration(
    title = title.requireSame(other.title, field, "title"),
    description = description.requireSame(other.description, field, "description"),
    enumValues = enumValues.requireSame(other.enumValues, field, "enumValues"),
    valueTypes = valueTypes.union(other.valueTypes),
    nullable = nullable.requireSame(other.nullable, field, "nullable"),
    required = required.requireSame(other.required, field, "required"),
    cardinality = cardinality.requireSame(other.cardinality, field, "cardinality"),
    semanticType = semanticType.requireSame(other.semanticType, field, "semanticType"),
    dynamicChildren = dynamicChildren.requireSame(other.dynamicChildren, field, "dynamicChildren"),
)

private fun DeclarationValue<Set<QueryValueType>>.union(
    other: DeclarationValue<Set<QueryValueType>>,
): DeclarationValue<Set<QueryValueType>> = when {
    this is DeclarationValue.Set && other is DeclarationValue.Set -> DeclarationValue.Set(value + other.value)
    this is DeclarationValue.Set -> this
    else -> other
}

private fun <T> DeclarationValue<T>.requireSame(
    other: DeclarationValue<T>,
    field: LogicalField,
    leaf: String,
): DeclarationValue<T> {
    if (this === DeclarationValue.Unset) return other
    if (other === DeclarationValue.Unset || this == other) return this
    throw QuerySchemaConflictException("Conflicting query schema declaration: [$field.$leaf].")
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

private fun JsonNode.textValueOrNull(name: String): String? =
    get(name)?.takeIf(JsonNode::isString)?.stringValue()

private fun JsonNode.enumValuesOrNull(): List<JsonNode>? =
    get("enum")?.takeIf(JsonNode::isArray)?.toList()

private fun JsonNode.nonNullValueTypes(): Set<QueryValueType> = buildSet {
    val typeNames = get("type")?.schemaTypeNames().orEmpty()
    typeNames.mapNotNullTo(this) { type ->
        when (type) {
            "string" -> QueryValueType.STRING
            "integer" -> QueryValueType.INTEGER
            "number" -> QueryValueType.DECIMAL
            "boolean" -> QueryValueType.BOOLEAN
            "object" -> QueryValueType.OBJECT
            else -> null
        }
    }
    if ("array" in typeNames) {
        get("items")?.nonNullValueTypes()?.let(::addAll)
    }
    COMPOSITIONS.forEach { composition ->
        get(composition)?.forEach { alternative -> addAll(alternative.nonNullValueTypes()) }
    }
}

private fun JsonNode.allowsNull(): Boolean {
    if (get("type")?.schemaTypeNames()?.contains("null") == true) {
        return true
    }
    return COMPOSITIONS.any { composition ->
        get(composition)?.any(JsonNode::allowsNull) == true
    }
}

private fun JsonNode.isArrayShape(): Boolean {
    if (get("type")?.schemaTypeNames()?.contains("array") == true) {
        return true
    }
    return COMPOSITIONS.any { composition ->
        get(composition)?.any(JsonNode::isArrayShape) == true
    }
}

private fun JsonNode.inferredTemporal(): QuerySemanticType? {
    if (get("format")?.takeIf(JsonNode::isString)?.stringValue() in setOf("date", "date-time")) {
        return Temporal.Date
    }
    COMPOSITIONS.forEach { composition ->
        get(composition)?.firstNotNullOfOrNull(JsonNode::inferredTemporal)?.let { return it }
    }
    return if (isArrayShape()) get("items")?.inferredTemporal() else null
}

internal fun JsonNode.hasAdditionalProperties(): Boolean {
    get("additionalProperties")?.let { additionalProperties ->
        if (additionalProperties.isObject ||
            additionalProperties.takeIf(JsonNode::isBoolean)?.booleanValue() == true
        ) {
            return true
        }
    }
    return COMPOSITIONS.any { composition ->
        get(composition)?.any(JsonNode::hasAdditionalProperties) == true
    }
}

private fun JsonNode.schemaTypeNames(): Set<String> = when {
    isString -> setOf(stringValue())
    isArray -> asSequence().filter(JsonNode::isString).map(JsonNode::stringValue).toSet()
    else -> emptySet()
}

private fun JsonNode.arrayItems(): List<JsonNode> = buildList {
    if (get("type")?.schemaTypeNames()?.contains("array") == true) {
        get("items")?.let(::add)
    }
    COMPOSITIONS.forEach { composition ->
        get(composition)?.forEach { alternative -> addAll(alternative.arrayItems()) }
    }
}

private inline fun <T : Any> List<JsonNode>.consistentValue(
    field: LogicalField,
    leaf: String,
    value: (JsonNode) -> T?,
): T? {
    val values = mapNotNull(value).distinct()
    if (values.size > 1) {
        throw QuerySchemaConflictException("Conflicting query schema declaration: [$field.$leaf].")
    }
    return values.singleOrNull()
}

private fun String.isLogicalFieldSegment(): Boolean =
    '.' !in this && runCatching { LogicalField(this) }.isSuccess
