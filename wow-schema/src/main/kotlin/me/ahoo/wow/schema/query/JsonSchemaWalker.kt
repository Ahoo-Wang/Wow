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
import me.ahoo.wow.api.query.schema.QueryValueKind
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.query.schema.QueryMemberFact
import me.ahoo.wow.query.schema.QueryTypeFact
import org.slf4j.LoggerFactory
import tools.jackson.databind.JsonNode

private const val ROOT_REFERENCE = "#"
private const val LOCAL_REFERENCE_PREFIX = "#/"
private const val METADATA_TITLE = "title"
private const val METADATA_DESCRIPTION = "description"
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

/**
 * Reads one generated JSON Schema into [QueryTypeFact]s: resolves local `$ref`s and compositions, keeps every
 * property as it serializes, and attaches the member facts the generator recorded under [MEMBER_ATTRIBUTE]. It reports
 * facts only; what they mean for queries is decided by the query subsystem. `rootPath` names the root value in
 * conflict messages and metadata warnings.
 */
internal class JsonSchemaWalker(
    private val schema: JsonNode,
    private val rootSchema: JsonNode = schema,
    private val memberResolver: (String) -> QueryMemberFact,
    private val rootPath: String = "",
) {
    private val descriptiveMetadataCandidates =
        mutableMapOf<Pair<String, String>, MutableList<DescriptiveMetadataCandidate>>()

    fun fact(): QueryTypeFact {
        val tree = schema.toTree(rootPath, setOf(ROOT_REFERENCE), MEMBER_METADATA_SOURCE)
        return tree.withDescriptiveMetadata(rootPath, mutableMapOf()).toFact()
    }

    private fun JsonNode.toTree(
        field: String,
        resolvingReferences: Set<String>,
        source: DescriptiveMetadataSource,
        collectMetadata: Boolean = true,
    ): JsonTypeNode {
        if (collectMetadata) collectDescriptiveMetadataCandidates(field, source)
        val types = get(JsonSchemaProperty.TYPE)?.schemaTypeNames().orEmpty()
        val shapes = (types - JsonSchemaType.NULL).ifEmpty {
            if (JsonSchemaType.NULL in types) setOf(JsonSchemaType.NULL) else emptySet()
        }.map { valueShape(it, types, field, resolvingReferences, source) }
        var result = if (shapes.isEmpty()) {
            valueShape(null, types, field, resolvingReferences, source)
        } else {
            shapes.union(field)
        }
        reference()?.let { reference ->
            val target = rootSchema.at(reference.removePrefix(ROOT_REFERENCE))
            val referenced = if (reference in resolvingReferences || target.isMissingNode) {
                // A recursive reference is not expanded again; the members below it are still reported.
                JsonTypeNode(kind = QueryValueKind.UNKNOWN, omitted = target.members())
            } else {
                target.toTree(field, resolvingReferences + reference, source.referenced(), collectMetadata = false)
            }
            result = result.intersect(referenced, field)
        }
        get(JsonSchemaProperty.ALL_OF)?.forEach { branch ->
            val composed = branch.toTree(
                field,
                resolvingReferences,
                source.composed(JsonSchemaProperty.ALL_OF),
                collectMetadata = false
            )
            result = result.intersect(composed, field)
        }
        ALTERNATIVE_COMPOSITIONS.forEach { composition ->
            get(composition)?.toList()?.map { branch ->
                branch.toTree(field, resolvingReferences, source.composed(composition), collectMetadata = false)
            }?.takeIf { it.isNotEmpty() }?.let { alternatives ->
                result = result.intersect(alternatives.union(field), field)
            }
        }
        return valueAttributes(result, field)
    }

    private fun JsonNode.valueAttributes(definition: JsonTypeNode, field: String): JsonTypeNode {
        var result = get(JsonSchemaProperty.ENUM)?.let { values ->
            definition.intersect(JsonTypeNode(enumValues = values.toList()), field)
        } ?: definition
        textValueOrNull(MEMBER_ATTRIBUTE)?.let(memberResolver)?.let { member ->
            result = result.copy(members = (result.members + member).distinct())
        }
        get(JsonSchemaProperty.FORMAT)?.takeIf(JsonNode::isString)?.stringValue()?.let { format ->
            result = result.copy(formats = result.formats + format)
        }
        return result
    }

    private fun JsonNode.valueShape(
        type: String?,
        types: Set<String>,
        field: String,
        resolvingReferences: Set<String>,
        source: DescriptiveMetadataSource,
    ): JsonTypeNode {
        val kind = valueKind(type)
        val basic = JsonTypeNode(
            kind = kind,
            valueTypes = valueTypes(type, kind),
            nullable = if (types.isEmpty()) null else JsonSchemaType.NULL in types,
        )
        return when (kind) {
            QueryValueKind.OBJECT -> basic.copy(
                properties = objectProperties(field, resolvingReferences, source),
                additionalProperties = if (has(JsonSchemaProperty.ADDITIONAL_PROPERTIES)) {
                    JsonTypeNode.Slot(dynamicValue(field, resolvingReferences, source))
                } else {
                    null
                },
            )
            QueryValueKind.ARRAY -> basic.copy(
                items = get(JsonSchemaProperty.ITEMS)?.toTree("$field.__items", resolvingReferences, source)
                    ?: JsonTypeNode(kind = QueryValueKind.UNKNOWN),
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
        field: String,
        resolvingReferences: Set<String>,
        source: DescriptiveMetadataSource,
    ): Map<String, JsonTypeNode> = buildMap {
        val requiredNames = this@objectProperties.get(JsonSchemaProperty.REQUIRED)?.mapNotNull {
            it.takeIf(JsonNode::isString)?.stringValue()
        }.orEmpty()
        this@objectProperties.get(JsonSchemaProperty.PROPERTIES)?.properties()?.forEach { (name, child) ->
            if (child.isWriteOnly()) return@forEach
            put(
                name,
                child.toTree(path(field, name), resolvingReferences, source).copy(required = name in requiredNames)
            )
        }
    }

    private fun JsonNode.dynamicValue(
        field: String,
        resolvingReferences: Set<String>,
        source: DescriptiveMetadataSource,
    ): JsonTypeNode? = get(JsonSchemaProperty.ADDITIONAL_PROPERTIES)?.let { node ->
        when {
            node.isObject -> node.toTree("$field.__key", resolvingReferences, source)
            node.isBoolean && node.booleanValue() -> JsonTypeNode(kind = QueryValueKind.UNKNOWN)
            else -> null
        }
    }

    private fun JsonTypeNode.withDescriptiveMetadata(
        field: String,
        resolved: MutableMap<Pair<String, String>, String?>,
    ): JsonTypeNode {
        fun metadata(property: String): String? {
            val key = field to property
            if (!resolved.containsKey(key)) resolved[key] = resolveDescriptiveMetadata(field, property)
            return resolved[key]
        }
        return copy(
            title = metadata(METADATA_TITLE),
            description = metadata(METADATA_DESCRIPTION),
            properties = properties?.mapValues { (name, child) ->
                child.withDescriptiveMetadata(path(field, name), resolved)
            },
            items = items?.withDescriptiveMetadata("$field.__items", resolved),
            additionalProperties = additionalProperties?.let { slot ->
                JsonTypeNode.Slot(slot.value?.withDescriptiveMetadata("$field.__key", resolved))
            },
            alternatives = alternatives?.map { it.withDescriptiveMetadata(field, resolved) },
        )
    }

    private fun JsonNode.collectDescriptiveMetadataCandidates(
        field: String,
        containerSource: DescriptiveMetadataSource,
    ) {
        sourcedMetadataNodes().forEach { (node, localSource) ->
            mapOf(
                METADATA_TITLE to node.textValueOrNull(JsonSchemaProperty.TITLE),
                METADATA_DESCRIPTION to node.textValueOrNull(JsonSchemaProperty.DESCRIPTION),
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

    private fun resolveDescriptiveMetadata(field: String, property: String): String? {
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

    /** Every member fact recorded at or below this schema node, following local references once. */
    private fun JsonNode.members(visitedReferences: Set<String> = emptySet()): List<QueryMemberFact> {
        if (isWriteOnly()) return emptyList()
        return buildList {
            textValueOrNull(MEMBER_ATTRIBUTE)?.let { add(memberResolver(it)) }
            get(JsonSchemaProperty.PROPERTIES)?.properties()?.forEach { (_, child) ->
                addAll(child.members(visitedReferences))
            }
            get(JsonSchemaProperty.ITEMS)?.let { addAll(it.members(visitedReferences)) }
            get(JsonSchemaProperty.ADDITIONAL_PROPERTIES)?.takeIf(JsonNode::isObject)
                ?.let { addAll(it.members(visitedReferences)) }
            reference()?.takeIf { it !in visitedReferences }?.let { reference ->
                rootSchema.at(reference.removePrefix(ROOT_REFERENCE)).takeUnless(JsonNode::isMissingNode)
                    ?.let { addAll(it.members(visitedReferences + reference)) }
            }
            COMPOSITIONS.forEach { composition ->
                get(composition)?.forEach { branch -> addAll(branch.members(visitedReferences)) }
            }
        }.distinct()
    }
}

private fun path(parent: String, name: String): String = if (parent.isEmpty()) name else "$parent.$name"

private fun JsonNode.textValueOrNull(name: String): String? =
    get(name)?.takeIf(JsonNode::isString)?.stringValue()

private fun JsonNode.schemaTypeNames(): Set<String> = when {
    isString -> setOf(stringValue())
    isArray -> asSequence().filter(JsonNode::isString).map(JsonNode::stringValue).toSet()
    else -> emptySet()
}
