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
import me.ahoo.wow.api.query.QueryField
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
import org.slf4j.LoggerFactory
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
private val jsonSchemaWalkerLogger = LoggerFactory.getLogger(JsonSchemaWalker::class.java)

private const val MEMBER_METADATA = 0
private const val INLINE_METADATA = 1
private const val REFERENCED_TYPE_METADATA = 2
private const val DEEPER_COMPOSITION_METADATA = 3

private data class DescriptiveMetadataSource(
    val precedence: Int = MEMBER_METADATA,
    val label: String = "member",
) {
    fun referenced(): DescriptiveMetadataSource = if (precedence <= INLINE_METADATA) {
        DescriptiveMetadataSource(
            precedence = REFERENCED_TYPE_METADATA,
            label = "referenced-type",
        )
    } else {
        deeper()
    }

    fun composed(composition: String): DescriptiveMetadataSource = if (precedence == MEMBER_METADATA) {
        DescriptiveMetadataSource(
            precedence = INLINE_METADATA,
            label = "inline-$composition",
        )
    } else {
        deeper()
    }

    private fun deeper(): DescriptiveMetadataSource = DescriptiveMetadataSource(
        precedence = maxOf(DEEPER_COMPOSITION_METADATA, precedence + 1),
        label = "deeper-composition",
    )
}

private val MEMBER_METADATA_SOURCE = DescriptiveMetadataSource()

private data class DescriptiveMetadataCandidate(
    val value: String,
    val localSource: DescriptiveMetadataSource,
    val containerSource: DescriptiveMetadataSource,
)

private data class RankedDescriptiveMetadataCandidate(
    val value: String,
    val precedence: Int,
    val label: String,
)

private data class SourcedJsonNode(val node: JsonNode, val source: DescriptiveMetadataSource)

internal class JsonSchemaWalker(
    private val schema: JsonNode,
    private val rootSchema: JsonNode = schema,
    private val maskRuleResolver: (String) -> MaskRule,
) {
    private val fields = linkedMapOf<QueryField, QueryFieldDeclaration>()
    private val descriptiveMetadataCandidates =
        mutableMapOf<Pair<QueryField, String>, MutableList<DescriptiveMetadataCandidate>>()

    fun declaration(
        rootField: QueryField = QueryField(StateAggregateRecords.STATE),
        includeRoot: Boolean = true,
    ): QuerySchemaDeclaration {
        if (includeRoot) {
            val rootNodes = schema.effectiveNodes()
            schema.collectDescriptiveMetadataCandidates(rootField, MEMBER_METADATA_SOURCE)
            fields[rootField] = QueryFieldDeclaration(
                enumValues = DeclarationValue.Set(
                    rootNodes.consistentValue(rootField, ENUM_VALUES, JsonNode::enumValuesOrNull),
                ),
                dynamicChildren = DeclarationValue.Set(rootNodes.any(JsonNode::hasAdditionalProperties)),
            )
        }
        fields.putAll(
            schema.collectProperties(
                parentName = rootField.path,
                resolvingReferences = setOf(ROOT_REFERENCE),
                source = MEMBER_METADATA_SOURCE,
            ),
        )
        return QuerySchemaDeclaration(
            fields.mapValuesTo(linkedMapOf()) { (field, declaration) ->
                declaration.copy(
                    title = DeclarationValue.Set(resolveDescriptiveMetadata(field, TITLE)),
                    description = DeclarationValue.Set(resolveDescriptiveMetadata(field, DESCRIPTION)),
                )
            },
        )
    }

    private fun JsonNode.collectProperties(
        parentName: String,
        resolvingReferences: Set<String>,
        source: DescriptiveMetadataSource,
    ): Map<QueryField, QueryFieldDeclaration> {
        val collected = collectDirectProperties(parentName, resolvingReferences, source)
        validateMaskedUnrepresentableDescendants(parentName, resolvingReferences)
        reference()?.takeIf { it !in resolvingReferences }?.let { reference ->
            rootSchema.at(reference.removePrefix(ROOT_REFERENCE))
                .takeUnless(JsonNode::isMissingNode)
                ?.collectProperties(parentName, resolvingReferences + reference, source.referenced())
                ?.let(collected::mergeConjunctive)
        }
        get(JsonSchemaProperty.ALL_OF)?.forEach { branch ->
            collected.mergeConjunctive(
                branch.collectProperties(
                    parentName,
                    resolvingReferences,
                    source.composed(JsonSchemaProperty.ALL_OF),
                ),
            )
        }
        ALTERNATIVE_COMPOSITIONS.forEach { composition ->
            get(composition)?.asSequence()?.map { alternative ->
                alternative.collectProperties(
                    parentName,
                    resolvingReferences,
                    source.composed(composition),
                )
            }?.toList()?.mergeAlternatives()?.let(collected::mergeConjunctive)
        }
        get(JsonSchemaProperty.ITEMS)?.collectProperties(parentName, resolvingReferences, source)
            ?.let(collected::mergeConjunctive)
        return collected
    }

    private fun JsonNode.collectDirectProperties(
        parentName: String,
        resolvingReferences: Set<String>,
        source: DescriptiveMetadataSource,
    ): MutableMap<QueryField, QueryFieldDeclaration> {
        val collected = linkedMapOf<QueryField, QueryFieldDeclaration>()
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
            if (!propertyName.isQueryFieldSegment()) {
                propertySchema.requireNoMaskRule(
                    "Masked query schema property is not a valid QueryField: [$parentName[\"$propertyName\"]].",
                )
                return@forEach
            }
            val field = QueryField(fullName)
            propertySchema.collectDescriptiveMetadataCandidates(field, source)
            collected.mergeConjunctive(
                mapOf(field to propertySchema.toDeclaration(field, propertyName in parentRequired)),
            )
            collected.mergeConjunctive(
                propertySchema.collectProperties(
                    fullName,
                    resolvingReferences,
                    source,
                ),
            )
        }
        return collected
    }

    private fun JsonNode.toDeclaration(
        field: QueryField,
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

    private fun JsonNode.collectDescriptiveMetadataCandidates(
        field: QueryField,
        containerSource: DescriptiveMetadataSource,
    ) {
        sourcedMetadataNodes().forEach { (node, localSource) ->
            mapOf(
                TITLE to node.textValueOrNull(JsonSchemaProperty.TITLE),
                DESCRIPTION to node.textValueOrNull(JsonSchemaProperty.DESCRIPTION),
            ).forEach { (property, value) ->
                value?.let {
                    descriptiveMetadataCandidates
                        .getOrPut(field to property, ::mutableListOf)
                        .add(DescriptiveMetadataCandidate(it, localSource, containerSource))
                }
            }
        }
    }

    private fun JsonNode.sourcedMetadataNodes(
        source: DescriptiveMetadataSource = MEMBER_METADATA_SOURCE,
        resolvingReferences: Set<String> = emptySet(),
    ): List<SourcedJsonNode> = buildList {
        add(SourcedJsonNode(this@sourcedMetadataNodes, source))
        reference()?.takeIf { it !in resolvingReferences }?.let { reference ->
            rootSchema.at(reference.removePrefix(ROOT_REFERENCE))
                .takeUnless(JsonNode::isMissingNode)
                ?.let {
                    addAll(it.sourcedMetadataNodes(source.referenced(), resolvingReferences + reference))
                }
        }
        COMPOSITIONS.forEach { composition ->
            get(composition)?.forEach { branch ->
                addAll(branch.sourcedMetadataNodes(source.composed(composition), resolvingReferences))
            }
        }
    }

    private fun resolveDescriptiveMetadata(field: QueryField, property: String): String? {
        val candidates = descriptiveMetadataCandidates[field to property].orEmpty()
        val containerBaseline = candidates.minOfOrNull { it.containerSource.precedence } ?: return null
        val ranked = candidates.map { candidate ->
            val containerPrecedence = candidate.containerSource.precedence
                .takeUnless { it == containerBaseline } ?: MEMBER_METADATA
            val effectiveSource = if (candidate.localSource.precedence >= containerPrecedence) {
                candidate.localSource
            } else {
                candidate.containerSource
            }
            RankedDescriptiveMetadataCandidate(
                value = candidate.value,
                precedence = maxOf(candidate.localSource.precedence, containerPrecedence),
                label = effectiveSource.label,
            )
        }
            .sortedWith(
                compareBy<RankedDescriptiveMetadataCandidate>(
                    RankedDescriptiveMetadataCandidate::precedence,
                    RankedDescriptiveMetadataCandidate::value,
                    RankedDescriptiveMetadataCandidate::label,
                ),
            )
        val selected = ranked.first()
        val ignored = ranked.asSequence()
            .filter { it.value != selected.value }
            .distinctBy(RankedDescriptiveMetadataCandidate::value)
            .toList()
        if (ignored.isNotEmpty()) {
            val hasSamePrecedence = ignored.any { it.precedence == selected.precedence }
            val precedence = buildList {
                add(selected.label + if (hasSamePrecedence) "(stable-value-order)" else "")
                ignored.asSequence()
                    .filter { it.precedence > selected.precedence }
                    .mapTo(this, RankedDescriptiveMetadataCandidate::label)
            }.distinct().joinToString(" > ")
            jsonSchemaWalkerLogger.warn(
                "Query schema descriptive metadata conflict: " +
                    "field=$field, property=$property, selected=${selected.value}, " +
                    "ignored=${ignored.map(RankedDescriptiveMetadataCandidate::value)}, precedence=$precedence",
            )
        }
        return selected.value
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
    field: QueryField,
    leaf: String,
    value: (JsonNode) -> T?,
): T? {
    val values = mapNotNull(value).distinct()
    if (values.size > 1) {
        throw QuerySchemaConflictException("Conflicting query schema declaration: [$field.$leaf].")
    }
    return values.singleOrNull()
}

private fun String.isQueryFieldSegment(): Boolean =
    '.' !in this && runCatching { QueryField(this) }.isSuccess
