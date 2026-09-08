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
import me.ahoo.wow.api.query.schema.QueryValueKind
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.api.query.schema.Temporal
import me.ahoo.wow.query.schema.DeclarationValue
import me.ahoo.wow.query.schema.MaskRule
import me.ahoo.wow.query.schema.QueryFieldDeclaration
import me.ahoo.wow.query.schema.QuerySchemaConflictException
import me.ahoo.wow.query.schema.QuerySchemaDeclaration
import me.ahoo.wow.query.schema.QuerySchemaDeclarationProperties.DESCRIPTION
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
    private val descriptiveMetadataCandidates =
        mutableMapOf<Pair<QueryField, String>, MutableList<DescriptiveMetadataCandidate>>()

    fun declaration(
        rootField: QueryField = QueryField(StateAggregateRecords.STATE),
        includeRoot: Boolean = true,
    ): QuerySchemaDeclaration {
        val tree = schema.toTree(rootField, setOf(ROOT_REFERENCE), MEMBER_METADATA_SOURCE)
        val value = tree.withDescriptiveMetadata(rootField, mutableMapOf())
        return QuerySchemaDeclaration(
            mapOf(
                rootField to value.copy(
                    title = if (includeRoot) value.title else DeclarationValue.Unset,
                    description = if (includeRoot) value.description else DeclarationValue.Unset,
                    nullable = DeclarationValue.Unset,
                    required = DeclarationValue.Unset,
                )
            )
        )
    }

    private fun JsonNode.toTree(
        field: QueryField,
        resolvingReferences: Set<String>,
        source: DescriptiveMetadataSource,
        collectMetadata: Boolean = true,
    ): QueryFieldDeclaration {
        if (collectMetadata) collectDescriptiveMetadataCandidates(field, source)
        val types = get(JsonSchemaProperty.TYPE)?.schemaTypeNames().orEmpty()
        val shapes = (types - JsonSchemaType.NULL).ifEmpty {
            if (JsonSchemaType.NULL in types) setOf(JsonSchemaType.NULL) else emptySet()
        }.map { valueShape(it, types, field, resolvingReferences, source) }
        var result = if (shapes.isEmpty()) {
            valueShape(null, types, field, resolvingReferences, source)
        } else {
            shapes.unionDeclaration(field)
        }
        reference()?.let { reference ->
            val target = rootSchema.at(reference.removePrefix(ROOT_REFERENCE))
            val referenced = if (reference in resolvingReferences || target.isMissingNode) {
                target.requireNoMaskRule("Recursive query schema field cannot contain masked descendants: [$field].")
                QueryFieldDeclaration(kind = DeclarationValue.Set(QueryValueKind.UNKNOWN))
            } else {
                target.toTree(field, resolvingReferences + reference, source.referenced(), collectMetadata = false)
            }
            result = result.intersectDeclaration(referenced, field)
        }
        get(JsonSchemaProperty.ALL_OF)?.forEach { branch ->
            result = result.intersectDeclaration(branch.toTree(field, resolvingReferences, source.composed(JsonSchemaProperty.ALL_OF), collectMetadata = false), field)
        }
        ALTERNATIVE_COMPOSITIONS.forEach { composition ->
            get(
                composition
            )?.toList()?.map { branch ->
                branch.toTree(
                    field,
                    resolvingReferences,
                    source.composed(composition),
                    collectMetadata = false
                )
            }
                ?.takeIf { it.isNotEmpty() }?.let { alternatives ->
                    result = result.intersectDeclaration(alternatives.unionDeclaration(field), field)
                }
        }
        return valueAttributes(result, field)
    }

    private fun JsonNode.valueAttributes(definition: QueryFieldDeclaration, field: QueryField): QueryFieldDeclaration {
        var result = get(JsonSchemaProperty.ENUM)?.let { values ->
            definition.intersectDeclaration(
                QueryFieldDeclaration(enumValues = DeclarationValue.Set(values.toList())),
                field,
            )
        } ?: definition
        val rule = textValueOrNull(MASK_RULE_ATTRIBUTE)?.let(maskRuleResolver)
        if (rule != null) {
            if (!result.isStringDomain()) {
                throw QuerySchemaConflictException(
                    "Masked query schema field must have STRING value type."
                )
            }
            val existing = (result.maskRule as? DeclarationValue.Set)?.value
            if (existing != null && existing != rule) {
                throw QuerySchemaConflictException(
                    "Conflicting query schema declaration: [$field.maskRule]."
                )
            }
            result = result.copy(maskRule = DeclarationValue.Set(rule))
        }
        textValueOrNull(TEMPORAL_UNIT)?.let { unit -> result = result.withEpoch(TimeUnit.valueOf(unit)) }
        val temporal = get(JsonSchemaProperty.FORMAT)?.takeIf(JsonNode::isString)?.stringValue()
        if (temporal in setOf(DATE_FORMAT, DATE_TIME_FORMAT)) result = result.copy(semanticType = DeclarationValue.Set(Temporal.Date))
        return result
    }

    private fun JsonNode.valueShape(
        type: String?,
        types: Set<String>,
        field: QueryField,
        resolvingReferences: Set<String>,
        source: DescriptiveMetadataSource,
    ): QueryFieldDeclaration {
        val kind = valueKind(type)
        val basic = QueryFieldDeclaration(
            kind = DeclarationValue.Set(kind),
            valueTypes = DeclarationValue.Set(valueTypes(type, kind)),
            nullable = if (types.isEmpty()) {
                DeclarationValue.Unset
            } else {
                DeclarationValue.Set(
                    JsonSchemaType.NULL in types
                )
            },
        )
        return when (kind) {
            QueryValueKind.OBJECT -> basic.copy(
                properties = DeclarationValue.Set(objectProperties(field, resolvingReferences, source)),
                additionalProperties = if (has(JsonSchemaProperty.ADDITIONAL_PROPERTIES)) {
                    DeclarationValue.Set(dynamicValue(field, resolvingReferences, source))
                } else {
                    DeclarationValue.Unset
                },
            )
            QueryValueKind.ARRAY -> basic.copy(
                items = DeclarationValue.Set(
                    get(JsonSchemaProperty.ITEMS)?.toTree(QueryField("${field.path}.__items"), resolvingReferences, source)
                        ?: QueryFieldDeclaration(kind = DeclarationValue.Set(QueryValueKind.UNKNOWN)),
                )
            )
            else -> basic
        }
    }

    private fun JsonNode.valueKind(type: String?): QueryValueKind = when (type) {
        JsonSchemaType.ARRAY -> QueryValueKind.ARRAY
        JsonSchemaType.OBJECT -> QueryValueKind.OBJECT
        JsonSchemaType.NULL -> QueryValueKind.NULL
        null -> when {
            has(JsonSchemaProperty.ITEMS) -> QueryValueKind.ARRAY
            has(JsonSchemaProperty.PROPERTIES) || has(JsonSchemaProperty.ADDITIONAL_PROPERTIES) -> QueryValueKind.OBJECT
            else -> QueryValueKind.UNKNOWN
        }
        else -> QueryValueKind.SCALAR
    }

    private fun valueTypes(type: String?, kind: QueryValueKind): Set<QueryValueType> = setOfNotNull(
        when (type) {
            JsonSchemaType.STRING -> QueryValueType.STRING
            JsonSchemaType.INTEGER -> QueryValueType.INTEGER
            JsonSchemaType.NUMBER -> QueryValueType.DECIMAL
            JsonSchemaType.BOOLEAN -> QueryValueType.BOOLEAN
            else -> if (kind == QueryValueKind.OBJECT) QueryValueType.OBJECT else null
        }
    )

    private fun JsonNode.objectProperties(
        field: QueryField,
        resolvingReferences: Set<String>,
        source: DescriptiveMetadataSource,
    ): Map<String, QueryFieldDeclaration> = buildMap {
        val requiredNames = this@objectProperties.get(JsonSchemaProperty.REQUIRED)?.mapNotNull {
            it.takeIf(JsonNode::isString)?.stringValue()
        }.orEmpty()
        this@objectProperties.get(JsonSchemaProperty.PROPERTIES)?.properties()?.forEach { (name, child) ->
            if (child.isWriteOnly()) return@forEach
            if (!name.isQueryFieldSegment()) {
                child.requireNoMaskRule("Masked query schema property is not a valid QueryField: [$field[\"$name\"]].")
                return@forEach
            }
            put(
                name,
                child.toTree(QueryField("${field.path}.$name"), resolvingReferences, source)
                    .copy(required = DeclarationValue.Set(name in requiredNames))
            )
        }
    }

    private fun JsonNode.dynamicValue(
        field: QueryField,
        resolvingReferences: Set<String>,
        source: DescriptiveMetadataSource,
    ): QueryFieldDeclaration? = get(JsonSchemaProperty.ADDITIONAL_PROPERTIES)?.let { node ->
        when {
            node.isObject -> node.toTree(QueryField("${field.path}.__key"), resolvingReferences, source)
            node.isBoolean && node.booleanValue() -> QueryFieldDeclaration(
                kind = DeclarationValue.Set(QueryValueKind.UNKNOWN)
            )
            else -> null
        }
    }

    private fun QueryFieldDeclaration.withDescriptiveMetadata(
        field: QueryField,
        resolved: MutableMap<Pair<QueryField, String>, String?>,
    ): QueryFieldDeclaration {
        fun metadata(property: String): String? {
            val key = field to property
            if (!resolved.containsKey(key)) resolved[key] = resolveDescriptiveMetadata(field, property)
            return resolved[key]
        }
        return copy(
            title = DeclarationValue.Set(metadata(TITLE)),
            description = DeclarationValue.Set(metadata(DESCRIPTION)),
            enumValues = if (enumValues === DeclarationValue.Unset) DeclarationValue.Set(null) else enumValues,
            semanticType = if (semanticType === DeclarationValue.Unset) DeclarationValue.Set(null) else semanticType,
            properties = if (properties is DeclarationValue.Set) {
                DeclarationValue.Set(
                    properties.or(emptyMap()).mapValues { (name, child) ->
                        child.withDescriptiveMetadata(QueryField("${field.path}.$name"), resolved)
                    }
                )
            } else {
                properties
            },
            items = if (items is DeclarationValue.Set) {
                DeclarationValue.Set(
                    items.or(null)?.withDescriptiveMetadata(QueryField("${field.path}.__items"), resolved)
                )
            } else {
                items
            },
            additionalProperties = if (additionalProperties is DeclarationValue.Set) {
                DeclarationValue.Set(
                    additionalProperties.or(null)?.withDescriptiveMetadata(QueryField("${field.path}.__key"), resolved)
                )
            } else {
                additionalProperties
            },
            alternatives = if (alternatives is DeclarationValue.Set) {
                DeclarationValue.Set(
                    alternatives.or(emptyList()).map {
                        it.withDescriptiveMetadata(field, resolved)
                    }
                )
            } else {
                alternatives
            },
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
}

private fun JsonNode.textValueOrNull(name: String): String? =
    get(name)?.takeIf(JsonNode::isString)?.stringValue()

private fun JsonNode.schemaTypeNames(): Set<String> = when {
    isString -> setOf(stringValue())
    isArray -> asSequence().filter(JsonNode::isString).map(JsonNode::stringValue).toSet()
    else -> emptySet()
}

private fun String.isQueryFieldSegment(): Boolean =
    '.' !in this && runCatching { QueryField(this) }.isSuccess
