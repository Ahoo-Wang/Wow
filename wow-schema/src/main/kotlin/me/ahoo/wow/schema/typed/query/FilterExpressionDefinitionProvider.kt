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

package me.ahoo.wow.schema.typed.query

import com.fasterxml.classmate.ResolvedType
import com.github.victools.jsonschema.generator.CustomDefinition
import com.github.victools.jsonschema.generator.CustomDefinitionProviderV2
import com.github.victools.jsonschema.generator.SchemaGenerationContext
import io.swagger.v3.oas.annotations.media.Schema
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import me.ahoo.wow.query.AggregationField
import me.ahoo.wow.query.AggregationFieldCatalog
import me.ahoo.wow.query.AggregationFieldKind
import me.ahoo.wow.schema.WowSchemaLoader
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode

object FilterExpressionDefinitionProvider : CustomDefinitionProviderV2 {
    override fun provideCustomSchemaDefinition(
        javaType: ResolvedType,
        context: SchemaGenerationContext,
    ): CustomDefinition? {
        return when (javaType.erasedType) {
            AggregatedAggregationElement::class.java -> provideAggregationElementSchema(javaType, context)
            AggregationElementFilterExpressionSchema::class.java -> CustomDefinition(
                loadAggregationElementFilterSchema()
            )
            in SUPPORTED_TYPES -> CustomDefinition(WowSchemaLoader.load(FilterExpression::class.java))
            else -> null
        }
    }

    private fun provideAggregationElementSchema(
        javaType: ResolvedType,
        context: SchemaGenerationContext,
    ): CustomDefinition? {
        val aggregateType = javaType.typeBindings.getBoundType(0)?.erasedType ?: return null
        if (aggregateType == Any::class.java) return null
        val stateType = aggregateType.aggregateMetadata<Any, Any>().state.aggregateType
        val catalog = AggregationFieldCatalog.scan(stateType)
        val root = context.generatorConfig.createObjectNode()
        val alternatives = root.putArray("oneOf")
        val definitions = root.putObject("definitions")
        catalog.elementPaths.forEachIndexed { index, elementPath ->
            val element = catalog.paths.getValue(elementPath)
            val fields = catalog.paths.values.filter { field ->
                field.path.startsWith("$elementPath.") && field.collectionPaths == element.collectionPaths
            }
            val prefix = "element${index}_"
            val filterSchema = loadAggregationElementFilterSchema(fields)
            filterSchema.prefixDefinitionReferences(prefix)
            (filterSchema.path("definitions") as ObjectNode).properties().forEach { (name, definition) ->
                definitions.set("$prefix$name", definition)
            }
            alternatives.add(
                root.objectNode().apply {
                    put("type", "object")
                    putObject("properties").apply {
                        putObject("path").put("const", elementPath)
                        putObject("filter").put("\$ref", "#/definitions/${prefix}filterExpression")
                    }
                    putArray("required").add("path")
                    put("additionalProperties", false)
                }
            )
        }
        return CustomDefinition(root)
    }

    private fun loadAggregationElementFilterSchema(fields: Collection<AggregationField>? = null) = WowSchemaLoader.load(
        FilterExpression::class.java
    ).also { schema ->
        schema.remove("\$id")
        val supportedDefinitions = fields?.let { schema.constrainElementFields(it) } ?: SUPPORTED_DEFINITIONS
        val oneOf = schema.path("definitions").path("filterExpression").path("oneOf")
        val supported = oneOf.filter { reference ->
            reference.path("\$ref").stringValue().substringAfterLast('/') in supportedDefinitions
        }
        (oneOf as ArrayNode).removeAll()
        supported.forEach(oneOf::add)

        val definitions = schema.path("definitions") as tools.jackson.databind.node.ObjectNode
        val retainedDefinitions = mutableSetOf("filterExpression")
        while (true) {
            val referencedDefinitions = retainedDefinitions.flatMap { definition ->
                definitions.path(definition).findValuesAsString("\$ref")
                    .map { it.substringAfterLast('/') }
                    .filter(definitions::has)
            }
            if (!retainedDefinitions.addAll(referencedDefinitions)) break
        }
        val unusedDefinitions = definitions.propertyNames()
            .filterNot(retainedDefinitions::contains)
            .toList()
        definitions.remove(unusedDefinitions)
    }

    private fun ObjectNode.constrainElementFields(fields: Collection<AggregationField>): Set<String> {
        val definitions = path("definitions") as ObjectNode
        val scalarFields = fields.filter { it.kind == AggregationFieldKind.SCALAR }
        val scalarPaths = scalarFields.map(AggregationField::path)
        val nullablePaths = fields.filter {
            it.kind == AggregationFieldKind.SCALAR || it.kind == AggregationFieldKind.OBJECT
        }.map(AggregationField::path)
        val textualPaths = scalarFields.filter { it.isTextual }.map(AggregationField::path)
        val temporalPaths = scalarFields.filter(AggregationField::isTemporal).map(AggregationField::path)
        val numericPaths = scalarFields.filter(AggregationField::isNumeric).map(AggregationField::path)
        val stringRangePaths = scalarFields.filter { it.isTemporal || it.isTextual }.map(AggregationField::path)

        definitions.setFieldEnums(listOf("eqShape", "neShape", "in", "notIn"), scalarPaths)
        definitions.setFieldEnums(listOf("contains", "startsWith", "endsWith"), textualPaths)
        definitions.setFieldEnums(listOf("isNull", "isNotNull", "exists", "notExists"), nullablePaths)
        definitions.setFieldEnums(RELATIVE_TIME_DEFINITIONS, temporalPaths)
        RANGE_DEFINITIONS.forEach { name ->
            definitions.set(
                name,
                definitions.path(name).rangeVariants(numericPaths, stringRangePaths),
            )
        }

        return buildSet {
            addAll(BASE_DEFINITIONS)
            if (scalarPaths.isNotEmpty()) addAll(SCALAR_DEFINITIONS)
            if (textualPaths.isNotEmpty()) addAll(TEXTUAL_DEFINITIONS)
            if (nullablePaths.isNotEmpty()) addAll(NULLABLE_DEFINITIONS)
            if (temporalPaths.isNotEmpty()) addAll(RELATIVE_TIME_OPERATORS)
            if (numericPaths.isNotEmpty() || stringRangePaths.isNotEmpty()) addAll(RANGE_OPERATORS)
        }
    }

    private fun ObjectNode.setFieldEnums(definitionNames: Collection<String>, fields: Collection<String>) {
        definitionNames.forEach { definitionName ->
            val definition = path(definitionName) as ObjectNode
            val properties = definition.path("properties") as ObjectNode
            properties.set("field", fieldEnum(fields))
        }
    }

    private fun ObjectNode.fieldEnum(fields: Collection<String>): ObjectNode = objectNode().apply {
        put("type", "string")
        putArray("enum").also { values -> fields.forEach(values::add) }
    }

    private fun JsonNode.rangeVariants(
        numericFields: Collection<String>,
        stringFields: Collection<String>,
    ): ObjectNode {
        val template = this as ObjectNode
        return template.objectNode().apply {
            val alternatives = putArray("oneOf")
            if (numericFields.isNotEmpty()) alternatives.add(template.rangeVariant(numericFields, "number"))
            if (stringFields.isNotEmpty()) alternatives.add(template.rangeVariant(stringFields, "string"))
        }
    }

    private fun ObjectNode.rangeVariant(fields: Collection<String>, valueType: String): ObjectNode =
        deepCopy().apply {
            val properties = path("properties") as ObjectNode
            properties.set("field", fieldEnum(fields))
            listOf("value", "lowerBound", "upperBound")
                .filter(properties::has)
                .forEach { property -> properties.set(property, objectNode().put("type", valueType)) }
        }

    private fun JsonNode.prefixDefinitionReferences(prefix: String) {
        when (this) {
            is ObjectNode -> {
                path("\$ref").takeIf(JsonNode::isString)?.stringValue()?.let { reference ->
                    put("\$ref", reference.replace("#/definitions/", "#/definitions/$prefix"))
                }
                properties().map(Map.Entry<String, JsonNode>::value).forEach { it.prefixDefinitionReferences(prefix) }
            }
            is ArrayNode -> forEach { it.prefixDefinitionReferences(prefix) }
        }
    }

    private val SUPPORTED_TYPES = setOf(FilterExpression::class.java, FilterExpressionSchema::class.java)
    private val BASE_DEFINITIONS = setOf("matchAll", "matchNone", "and", "or", "nor")
    private val SCALAR_DEFINITIONS = setOf("eq", "ne", "in", "notIn")
    private val RANGE_OPERATORS = setOf("gt", "gte", "lt", "lte", "between")
    private val RANGE_DEFINITIONS = setOf("gtShape", "gteShape", "ltShape", "lteShape", "between")
    private val TEXTUAL_DEFINITIONS = setOf("contains", "startsWith", "endsWith")
    private val NULLABLE_DEFINITIONS = setOf("isNull", "isNotNull", "exists", "notExists")
    private val RELATIVE_TIME_OPERATORS = setOf(
        "today", "beforeToday", "tomorrow", "thisWeek", "nextWeek", "lastWeek",
        "thisMonth", "lastMonth", "recentDays", "earlierDays",
    )
    private val RELATIVE_TIME_DEFINITIONS = setOf(
        "todayShape", "beforeToday", "tomorrowShape", "thisWeekShape", "nextWeekShape", "lastWeekShape",
        "thisMonthShape", "lastMonthShape", "recentDays", "earlierDays",
    )
    private val SUPPORTED_DEFINITIONS = BASE_DEFINITIONS + SCALAR_DEFINITIONS + RANGE_OPERATORS +
        TEXTUAL_DEFINITIONS + NULLABLE_DEFINITIONS + RELATIVE_TIME_OPERATORS
}

@Schema(name = "api.query.FilterExpression")
sealed interface FilterExpressionSchema {
    data object MatchAll : FilterExpressionSchema
}

@Schema(name = "api.query.AggregationElementFilterExpression")
sealed interface AggregationElementFilterExpressionSchema {
    data object MatchAll : AggregationElementFilterExpressionSchema
}
