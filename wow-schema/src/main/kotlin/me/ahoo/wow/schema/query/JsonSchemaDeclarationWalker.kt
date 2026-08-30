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

import com.github.victools.jsonschema.generator.SchemaKeyword
import com.github.victools.jsonschema.generator.SchemaVersion
import me.ahoo.wow.api.query.LogicalField
import me.ahoo.wow.api.query.schema.QueryCardinality
import me.ahoo.wow.api.query.schema.QuerySemanticType
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.api.query.schema.Temporal
import me.ahoo.wow.query.schema.DeclarationValue
import me.ahoo.wow.query.schema.MaskRule
import me.ahoo.wow.query.schema.QueryFieldDeclaration
import me.ahoo.wow.query.schema.QuerySchemaConflictException
import me.ahoo.wow.query.schema.QuerySchemaDeclaration
import me.ahoo.wow.query.schema.QuerySchemaDeclarationProperties.DESCRIPTION
import me.ahoo.wow.query.schema.QuerySchemaDeclarationProperties.ENUM_VALUES
import me.ahoo.wow.query.schema.QuerySchemaDeclarationProperties.SEMANTIC_TYPE
import me.ahoo.wow.query.schema.QuerySchemaDeclarationProperties.TITLE
import me.ahoo.wow.serialization.state.StateAggregateRecords
import tools.jackson.databind.JsonNode
import java.util.concurrent.TimeUnit

private const val ROOT_REFERENCE = "#"
private const val LOCAL_REFERENCE_PREFIX = "#/"
private const val DATE_FORMAT = "date"
private const val DATE_TIME_FORMAT = "date-time"
private val JSON_SCHEMA_VERSION = SchemaVersion.DRAFT_2020_12

private object JsonSchemaProperty {
    val REF = SchemaKeyword.TAG_REF.forVersion(JSON_SCHEMA_VERSION)
    val TYPE = SchemaKeyword.TAG_TYPE.forVersion(JSON_SCHEMA_VERSION)
    val PROPERTIES = SchemaKeyword.TAG_PROPERTIES.forVersion(JSON_SCHEMA_VERSION)
    val ITEMS = SchemaKeyword.TAG_ITEMS.forVersion(JSON_SCHEMA_VERSION)
    val REQUIRED = SchemaKeyword.TAG_REQUIRED.forVersion(JSON_SCHEMA_VERSION)
    val ADDITIONAL_PROPERTIES = SchemaKeyword.TAG_ADDITIONAL_PROPERTIES.forVersion(JSON_SCHEMA_VERSION)
    val ALL_OF = SchemaKeyword.TAG_ALLOF.forVersion(JSON_SCHEMA_VERSION)
    val ANY_OF = SchemaKeyword.TAG_ANYOF.forVersion(JSON_SCHEMA_VERSION)
    val ONE_OF = SchemaKeyword.TAG_ONEOF.forVersion(JSON_SCHEMA_VERSION)
    val TITLE = SchemaKeyword.TAG_TITLE.forVersion(JSON_SCHEMA_VERSION)
    val DESCRIPTION = SchemaKeyword.TAG_DESCRIPTION.forVersion(JSON_SCHEMA_VERSION)
    val ENUM = SchemaKeyword.TAG_ENUM.forVersion(JSON_SCHEMA_VERSION)
    val WRITE_ONLY = SchemaKeyword.TAG_WRITE_ONLY.forVersion(JSON_SCHEMA_VERSION)
    val FORMAT = SchemaKeyword.TAG_FORMAT.forVersion(JSON_SCHEMA_VERSION)
}

private object JsonSchemaType {
    val NULL = SchemaKeyword.TAG_TYPE_NULL.forVersion(JSON_SCHEMA_VERSION)
    val ARRAY = SchemaKeyword.TAG_TYPE_ARRAY.forVersion(JSON_SCHEMA_VERSION)
    val OBJECT = SchemaKeyword.TAG_TYPE_OBJECT.forVersion(JSON_SCHEMA_VERSION)
    val BOOLEAN = SchemaKeyword.TAG_TYPE_BOOLEAN.forVersion(JSON_SCHEMA_VERSION)
    val STRING = SchemaKeyword.TAG_TYPE_STRING.forVersion(JSON_SCHEMA_VERSION)
    val INTEGER = SchemaKeyword.TAG_TYPE_INTEGER.forVersion(JSON_SCHEMA_VERSION)
    val NUMBER = SchemaKeyword.TAG_TYPE_NUMBER.forVersion(JSON_SCHEMA_VERSION)
}

private val ALTERNATIVE_COMPOSITIONS by lazy {
    listOf(JsonSchemaProperty.ANY_OF, JsonSchemaProperty.ONE_OF)
}
private val COMPOSITIONS by lazy {
    listOf(JsonSchemaProperty.ALL_OF) + ALTERNATIVE_COMPOSITIONS
}

internal class JsonSchemaWalker(
    private val schema: JsonNode,
    private val rootSchema: JsonNode = schema,
    private val maskRuleResolver: (String) -> MaskRule,
) {
    private val fields = linkedMapOf<LogicalField, QueryFieldDeclaration>()

    fun declaration(
        rootField: LogicalField = LogicalField(StateAggregateRecords.STATE),
        includeRoot: Boolean = true,
    ): QuerySchemaDeclaration {
        if (includeRoot) {
            val rootNodes = schema.effectiveNodes()
            fields[rootField] = QueryFieldDeclaration(
                title = DeclarationValue.Set(
                    rootNodes.consistentValue(rootField, TITLE) {
                        it.textValueOrNull(JsonSchemaProperty.TITLE)
                    },
                ),
                description = DeclarationValue.Set(
                    rootNodes.consistentValue(rootField, DESCRIPTION) {
                        it.textValueOrNull(JsonSchemaProperty.DESCRIPTION)
                    },
                ),
                enumValues = DeclarationValue.Set(
                    rootNodes.consistentValue(rootField, ENUM_VALUES, JsonNode::enumValuesOrNull),
                ),
                dynamicChildren = DeclarationValue.Set(rootNodes.any(JsonNode::hasAdditionalProperties)),
            )
        }
        fields.putAll(schema.collectProperties(rootField.value, setOf(ROOT_REFERENCE)))
        return QuerySchemaDeclaration(fields)
    }

    private fun JsonNode.collectProperties(
        parentName: String,
        resolvingReferences: Set<String>,
    ): Map<LogicalField, QueryFieldDeclaration> {
        val collected = linkedMapOf<LogicalField, QueryFieldDeclaration>()
        val parentRequired = get(JsonSchemaProperty.REQUIRED)?.asSequence()
            ?.filter(JsonNode::isString)
            ?.map(JsonNode::stringValue)
            ?.toSet()
            .orEmpty()
        get(JsonSchemaProperty.PROPERTIES)?.properties()?.forEach { (propertyName, propertySchema) ->
            if (propertySchema.isWriteOnly()) {
                return@forEach
            }
            val fullName = "$parentName.$propertyName"
            if (!propertyName.isLogicalFieldSegment()) {
                propertySchema.requireNoMaskRule(
                    "Masked query schema field has an invalid logical name: [$parentName[\"$propertyName\"]].",
                )
                return@forEach
            }
            val field = LogicalField(fullName)
            collected.mergeConjunctive(
                mapOf(field to propertySchema.toDeclaration(field, propertyName in parentRequired)),
            )
            collected.mergeConjunctive(propertySchema.collectProperties(fullName, resolvingReferences))
        }
        validateMaskedUnrepresentableDescendants(parentName, resolvingReferences)
        reference()?.takeIf { it !in resolvingReferences }?.let { reference ->
            rootSchema.at(reference.removePrefix(ROOT_REFERENCE))
                .takeUnless(JsonNode::isMissingNode)
                ?.collectProperties(parentName, resolvingReferences + reference)
                ?.let(collected::mergeConjunctive)
        }
        get(JsonSchemaProperty.ALL_OF)?.forEach { branch ->
            collected.mergeConjunctive(branch.collectProperties(parentName, resolvingReferences))
        }
        ALTERNATIVE_COMPOSITIONS.forEach { composition ->
            get(composition)?.asSequence()?.map { alternative ->
                alternative.collectProperties(parentName, resolvingReferences)
            }?.toList()?.mergeAlternatives()?.let(collected::mergeConjunctive)
        }
        get(JsonSchemaProperty.ITEMS)?.collectProperties(parentName, resolvingReferences)
            ?.let(collected::mergeConjunctive)
        return collected
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
        val maskRuleId = shapeNodes.consistentValue(field, "maskRule") {
            it.textValueOrNull(MASK_RULE_ATTRIBUTE)
        }
        if (maskRuleId != null && valueTypes != setOf(QueryValueType.STRING)) {
            throw QuerySchemaConflictException("Masked query schema field must have STRING value type.")
        }
        val temporalUnit = (nodes + itemNodes).consistentValue(field, SEMANTIC_TYPE) {
            it.textValueOrNull(TEMPORAL_UNIT)
        }
        val inferredTemporal = inferredTemporal()
        val semanticType = temporalUnit?.let { unit ->
            if (valueTypes != setOf(QueryValueType.INTEGER)) {
                throw QuerySchemaConflictException(
                    "@QueryTemporal requires an integer JSON wire shape.",
                )
            }
            Temporal.Epoch(TimeUnit.valueOf(unit))
        } ?: inferredTemporal
        return QueryFieldDeclaration(
            title = DeclarationValue.Set(
                nodes.consistentValue(field, TITLE) { it.textValueOrNull(JsonSchemaProperty.TITLE) },
            ),
            description = DeclarationValue.Set(
                nodes.consistentValue(field, DESCRIPTION) {
                    it.textValueOrNull(JsonSchemaProperty.DESCRIPTION)
                },
            ),
            enumValues = DeclarationValue.Set(
                nodes.consistentValue(field, ENUM_VALUES, JsonNode::enumValuesOrNull),
            ),
            valueTypes = DeclarationValue.Set(valueTypes),
            nullable = DeclarationValue.Set(nodes.any(JsonNode::allowsNull)),
            required = DeclarationValue.Set(required),
            cardinality = DeclarationValue.Set(
                if (arrayShape) QueryCardinality.MANY else QueryCardinality.SINGLE,
            ),
            semanticType = DeclarationValue.Set(semanticType),
            dynamicChildren = DeclarationValue.Set(shapeNodes.any(JsonNode::hasAdditionalProperties)),
            maskRule = maskRuleId?.let { DeclarationValue.Set(maskRuleResolver(it)) } ?: DeclarationValue.Unset,
        )
    }

    private fun JsonNode.effectiveNodes(
        resolvingReferences: Set<String> = emptySet(),
    ): List<JsonNode> = buildList {
        add(this@effectiveNodes)
        reference()?.takeIf { it !in resolvingReferences }?.let { reference ->
            rootSchema.at(reference.removePrefix(ROOT_REFERENCE))
                .takeUnless(JsonNode::isMissingNode)
                ?.let { addAll(it.effectiveNodes(resolvingReferences + reference)) }
        }
        COMPOSITIONS.forEach { composition ->
            get(composition)?.forEach { alternative ->
                addAll(alternative.effectiveNodes(resolvingReferences))
            }
        }
    }

    private fun JsonNode.inferredTemporal(
        resolvingReferences: Set<String> = emptySet(),
    ): QuerySemanticType? {
        if (get(JsonSchemaProperty.FORMAT)?.takeIf(JsonNode::isString)?.stringValue() in
            setOf(DATE_FORMAT, DATE_TIME_FORMAT)
        ) {
            return Temporal.Date
        }
        reference()?.takeIf { it !in resolvingReferences }?.let { reference ->
            rootSchema.at(reference.removePrefix(ROOT_REFERENCE))
                .takeUnless(JsonNode::isMissingNode)
                ?.inferredTemporal(resolvingReferences + reference)
                ?.let { return it }
        }
        ALTERNATIVE_COMPOSITIONS.forEach { composition ->
            val alternatives = get(composition)?.toList().orEmpty()
            val valueBearing = alternatives.filter { alternative ->
                alternative.effectiveNodes(resolvingReferences)
                    .any { it.nonNullValueTypes().isNotEmpty() }
            }
            if (valueBearing.isNotEmpty()) {
                return valueBearing.map { it.inferredTemporal(resolvingReferences) }
                    .distinct().singleOrNull()
            }
        }
        get(JsonSchemaProperty.ALL_OF)?.mapNotNull {
            it.inferredTemporal(resolvingReferences)
        }?.distinct()?.singleOrNull()?.let { return it }
        return if (isArrayShape()) {
            get(JsonSchemaProperty.ITEMS)?.inferredTemporal(resolvingReferences)
        } else {
            null
        }
    }

    private fun JsonNode.reference(): String? =
        get(JsonSchemaProperty.REF)?.takeIf(JsonNode::isString)?.stringValue()
            ?.takeIf { it == ROOT_REFERENCE || it.startsWith(LOCAL_REFERENCE_PREFIX) }

    private fun JsonNode.isWriteOnly(): Boolean = effectiveNodes().any {
        it.get(JsonSchemaProperty.WRITE_ONLY)?.takeIf(JsonNode::isBoolean)?.booleanValue() == true
    }

    private fun JsonNode.hasMaskRule(visitedReferences: Set<String> = emptySet()): Boolean {
        if (isWriteOnly()) return false
        if (textValueOrNull(MASK_RULE_ATTRIBUTE) != null) return true
        if (get(JsonSchemaProperty.PROPERTIES)?.properties()?.any { (_, propertySchema) ->
                propertySchema.hasMaskRule(visitedReferences)
            } == true ||
            get(JsonSchemaProperty.ITEMS)?.hasMaskRule(visitedReferences) == true ||
            get(JsonSchemaProperty.ADDITIONAL_PROPERTIES)?.takeIf(JsonNode::isObject)
                ?.hasMaskRule(visitedReferences) == true
        ) {
            return true
        }
        reference()?.takeIf { it !in visitedReferences }?.let { reference ->
            if (rootSchema.at(reference.removePrefix(ROOT_REFERENCE))
                    .takeUnless(JsonNode::isMissingNode)
                    ?.hasMaskRule(visitedReferences + reference) == true
            ) {
                return true
            }
        }
        return COMPOSITIONS.any { composition ->
            get(composition)?.any { branch -> branch.hasMaskRule(visitedReferences) } == true
        }
    }

    private fun JsonNode.requireNoMaskRule(message: String) {
        if (hasMaskRule()) {
            throw QuerySchemaConflictException(message)
        }
    }

    private fun JsonNode.validateMaskedUnrepresentableDescendants(
        parentName: String,
        resolvingReferences: Set<String>,
    ) {
        get(JsonSchemaProperty.ADDITIONAL_PROPERTIES)?.takeIf(JsonNode::isObject)
            ?.requireNoMaskRule(
                "Dynamic query schema field cannot contain masked descendants: [$parentName].",
            )
        val reference = reference()?.takeIf { it in resolvingReferences } ?: return
        rootSchema.at(reference.removePrefix(ROOT_REFERENCE))
            .takeUnless(JsonNode::isMissingNode)
            ?.requireNoMaskRule(
                "Recursive query schema field cannot contain masked descendants: [$parentName].",
            )
    }
}

private fun JsonNode.textValueOrNull(name: String): String? =
    get(name)?.takeIf(JsonNode::isString)?.stringValue()

private fun JsonNode.enumValuesOrNull(): List<JsonNode>? =
    get(JsonSchemaProperty.ENUM)?.takeIf(JsonNode::isArray)?.toList()

private fun JsonNode.nonNullValueTypes(): Set<QueryValueType> = buildSet {
    val typeNames = get(JsonSchemaProperty.TYPE)?.schemaTypeNames().orEmpty()
    typeNames.mapNotNullTo(this) { type ->
        when (type) {
            JsonSchemaType.STRING -> QueryValueType.STRING
            JsonSchemaType.INTEGER -> QueryValueType.INTEGER
            JsonSchemaType.NUMBER -> QueryValueType.DECIMAL
            JsonSchemaType.BOOLEAN -> QueryValueType.BOOLEAN
            JsonSchemaType.OBJECT -> QueryValueType.OBJECT
            else -> null
        }
    }
    if (JsonSchemaType.ARRAY in typeNames) {
        get(JsonSchemaProperty.ITEMS)?.nonNullValueTypes()?.let(::addAll)
    }
    COMPOSITIONS.forEach { composition ->
        get(composition)?.forEach { alternative -> addAll(alternative.nonNullValueTypes()) }
    }
}

private fun JsonNode.allowsNull(): Boolean {
    if (get(JsonSchemaProperty.TYPE)?.schemaTypeNames()?.contains(JsonSchemaType.NULL) == true) {
        return true
    }
    return COMPOSITIONS.any { composition ->
        get(composition)?.any(JsonNode::allowsNull) == true
    }
}

private fun JsonNode.isArrayShape(): Boolean {
    if (get(JsonSchemaProperty.TYPE)?.schemaTypeNames()?.contains(JsonSchemaType.ARRAY) == true) {
        return true
    }
    return COMPOSITIONS.any { composition ->
        get(composition)?.any(JsonNode::isArrayShape) == true
    }
}

internal fun JsonNode.hasAdditionalProperties(): Boolean {
    get(JsonSchemaProperty.ADDITIONAL_PROPERTIES)?.let { additionalProperties ->
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
    if (get(JsonSchemaProperty.TYPE)?.schemaTypeNames()?.contains(JsonSchemaType.ARRAY) == true) {
        get(JsonSchemaProperty.ITEMS)?.let(::add)
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
