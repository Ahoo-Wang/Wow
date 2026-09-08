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

package me.ahoo.wow.query.mask

import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.api.query.schema.QueryValueKind
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.query.schema.MaskRule
import me.ahoo.wow.query.schema.QueryMaskValue
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QueryPathSegment
import me.ahoo.wow.query.schema.QuerySchemaConflictException
import me.ahoo.wow.query.schema.QuerySchemaValidationException
import me.ahoo.wow.query.schema.QueryValueSchema
import me.ahoo.wow.query.schema.withMask
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode

/** Generation-owned data. Runtime masking neither resolves schema paths nor builds plans. */
internal class QueryMaskDefinition private constructor(
    val root: MaskNode,
    val eventBodyTypes: Set<String>?,
) {
    internal class MaskNode(
        val values: List<QueryMaskValue>,
        val shapes: List<QueryValueSchema>,
        val properties: Map<String, MaskNode>,
        val keys: List<Pair<Set<String>, MaskNode>>,
        val item: MaskNode?,
    ) {
        val plainObject: Boolean = values.isEmpty() && keys.isEmpty() &&
            shapes.isNotEmpty() && shapes.all { it.kind == QueryValueKind.OBJECT }

        val stringMask: MaskRule? = values.singleOrNull()
            ?.takeIf { properties.isEmpty() && keys.isEmpty() && item == null }
            ?.takeIf { it.masked.isStringShape() && it.allowed.isStringShape() && shapes.all { shape -> shape.isStringShape() } }
            ?.masked?.maskRule
    }

    companion object {
        fun create(schema: QueryModelSchema): QueryMaskDefinition? {
            if (!schema.hasMaskedFields) return null
            val paths = schema.protectedSources.responseMasks
            paths.forEach { (path, value) ->
                val names = path.segments.filterIsInstance<QueryPathSegment.Property>().map { it.name }
                val prefix = when (schema.model) {
                    QueryModel.SNAPSHOT -> listOf("state")
                    QueryModel.EVENT_STREAM -> listOf("body", "body")
                    else -> throw QuerySchemaConflictException("Unsupported masked model: [${schema.model}].")
                }
                if (names.take(prefix.size) != prefix || !value.masked.maskable() || value.allowed.hasUnknownShape()) {
                    throw QuerySchemaConflictException(
                        "Mask requires a declared string domain under the model payload."
                    )
                }
            }
            val bodyTypes = schema.eventBodyTypes()
            val shapes = schema.definition.values.mapKeys { it.key.segments }.toMutableMap()
            schema.bindings.forEach { (logical, native) ->
                native.responsePath?.let { response ->
                    schema.definition.value(logical)?.let { shapes.putIfAbsent(response.segments, it) }
                }
            }
            return QueryMaskDefinition(
                build(paths.map { it.first.segments to it.second }, emptyList(), shapes),
                bodyTypes
            )
        }

        private fun QueryModelSchema.eventBodyTypes(): Set<String>? {
            return if (model == QueryModel.EVENT_STREAM) {
                val values = field(QueryField("body.bodyType"))?.value?.enumValues
                    ?: throw QuerySchemaConflictException("Masked event schema requires body.bodyType enum values.")
                val types = values.mapNotNull { it.takeIf(JsonNode::isString)?.stringValue() }.toSet()
                if (types.isEmpty() || types.size != values.size) {
                    throw QuerySchemaConflictException("Masked event schema requires string body.bodyType enum values.")
                }
                types
            } else {
                null
            }
        }

        private fun build(
            paths: List<Pair<List<QueryPathSegment>, QueryMaskValue>>,
            prefix: List<QueryPathSegment>,
            shapes: Map<List<QueryPathSegment>, QueryValueSchema>,
        ): MaskNode {
            val values = paths.filter { it.first.isEmpty() }.map { it.second }
            if (values.mapNotNull { it.masked.maskRule }.distinct().size > 1) {
                throw QuerySchemaConflictException("Conflicting mask rules at one response value.")
            }
            val children = paths.filter { it.first.isNotEmpty() }.groupBy {
                val segment = it.first.first()
                segment to if (segment is QueryPathSegment.Key) it.second.excludedKeys[segment.slot].orEmpty() else emptySet()
            }.mapValues { (part, entries) ->
                build(
                    entries.map { it.first.drop(1) to it.second },
                    prefix + part.first,
                    shapes
                )
            }
            return MaskNode(
                values,
                listOfNotNull(shapes[prefix]),
                children.filterKeys { it.first is QueryPathSegment.Property }
                    .mapKeys { (part, _) -> (part.first as QueryPathSegment.Property).name },
                children.filterKeys { it.first is QueryPathSegment.Key }.map { it.key.second to it.value },
                children[QueryPathSegment.Item to emptySet()],
            )
        }
    }
}

private fun QueryValueSchema.maskable(): Boolean = when (kind) {
    QueryValueKind.NULL -> true
    QueryValueKind.SCALAR -> valueTypes == setOf(QueryValueType.STRING)
    QueryValueKind.ARRAY -> checkNotNull(items).maskable()
    QueryValueKind.UNION -> alternatives.all { it.maskable() }
    else -> false
}

internal class SchemaMasker private constructor(private val definition: QueryMaskDefinition) {
    fun mask(node: ObjectNode): ObjectNode {
        definition.eventBodyTypes?.let { known ->
            val events = node.get("body")?.takeUnless(JsonNode::isNull)
            if (events != null) {
                if (!events.isArray || events.any { !it.isObject }) fail("Event body must contain objects.")
                events.forEach { event ->
                    if (event.get("body")?.isNull == false && event.get("bodyType")?.stringValue() !in known) {
                        fail("Unknown event bodyType.")
                    }
                }
            }
        }
        visitSingle(node, definition.root)
        return node
    }

    private fun visit(node: JsonNode, nodes: List<QueryMaskDefinition.MaskNode>, inherited: List<QueryMaskValue>): JsonNode =
        if (nodes.size == 1 && inherited.isEmpty()) {
            visitSingle(
                node,
                nodes.single()
            )
        } else {
            visitMany(node, nodes, inherited)
        }

    private fun visitSingle(node: JsonNode, mask: QueryMaskDefinition.MaskNode): JsonNode {
        if (node.isNull) return node
        if (mask.plainObject) {
            if (!node.isObject) fail("Masked response has an undeclared container shape.")
            maskNamedProperties(node as ObjectNode, mask.properties)
            return node
        }
        mask.stringMask?.let { rule ->
            if (!node.isString) fail("Masked value must contain strings.")
            return JsonNodeFactory.instance.stringNode(rule.apply(node.stringValue()))
        }
        if (node.isArray || mask.keys.isNotEmpty()) return visitMany(node, listOf(mask), emptyList())
        val rule = selectMask(node, mask.shapes, mask.values, true)
        if (node.isObject) {
            if (rule != null) fail("Masked value must contain strings.")
            maskNamedProperties(node as ObjectNode, mask.properties)
        } else if (rule != null) {
            if (!node.isString) fail("Masked value must contain strings.")
            return JsonNodeFactory.instance.stringNode(rule.apply(node.stringValue()))
        }
        return node
    }

    private fun visitMany(node: JsonNode, nodes: List<QueryMaskDefinition.MaskNode>, inherited: List<QueryMaskValue>): JsonNode {
        if (node.isNull) return node
        val domains = nodes.flatMap { it.values } + inherited
        val rule = selectMask(node, nodes.flatMap { it.shapes }, domains, nodes.isNotEmpty())
        when {
            node.isString -> return if (rule == null) {
                node
            } else {
                JsonNodeFactory.instance.stringNode(
                    rule.apply(node.stringValue())
                )
            }
            node.isArray -> {
                val children = nodes.mapNotNull { it.item }
                val members = domains.flatMap { it.arrayMembers() }
                node.forEachIndexed { index, child ->
                    (node as ArrayNode).set(index, visit(child, children, members))
                }
            }
            node.isObject -> {
                if (rule != null) fail("Masked value must contain strings.")
                maskObject(node as ObjectNode, nodes)
            }
            rule != null -> fail("Masked value must contain strings.")
        }
        return node
    }

    private fun selectMask(
        node: JsonNode,
        shapes: List<QueryValueSchema>,
        domains: List<QueryMaskValue>,
        requiresContainer: Boolean,
    ): MaskRule? {
        validateShape(node, shapes, domains, requiresContainer)
        var rule: MaskRule? = null
        domains.forEach { domain ->
            if (domain.masked.matchesShape(node)) {
                val candidate = domain.masked.maskRule
                if (rule != null && candidate != null && rule != candidate) {
                    fail(
                        "Conflicting masks for a response value."
                    )
                }
                if (candidate != null) rule = candidate
            }
        }
        return rule
    }

    private fun validateShape(
        node: JsonNode,
        shapes: List<QueryValueSchema>,
        domains: List<QueryMaskValue>,
        requiresContainer: Boolean,
    ) {
        if (shapes.isNotEmpty() && shapes.none { it.matchesShape(node) }) {
            fail("Masked response has an undeclared container shape.")
        }
        val hasKnownShape = shapes.isNotEmpty() || domains.isNotEmpty()
        if (!hasKnownShape && requiresContainer && node.isScalarValue()) {
            fail("Masked response requires an object or array.")
        }
        if (domains.isNotEmpty() && domains.none { it.allowed.matchesShape(node) }) {
            fail("Masked response has an undeclared JSON value shape.")
        }
    }

    private fun maskObject(objectNode: ObjectNode, nodes: List<QueryMaskDefinition.MaskNode>) {
        val single = nodes.singleOrNull()
        if (single != null && single.keys.isEmpty()) {
            maskNamedProperties(objectNode, single.properties)
            return
        }
        val dynamic = nodes.flatMap { it.keys }
        val names = nodes.flatMapTo(linkedSetOf()) { it.properties.keys }
        if (dynamic.isNotEmpty() || names.size > objectNode.size()) {
            objectNode.forEachEntry { name, child ->
                val children = nodes.mapNotNull { it.properties[name] } + dynamic.filter { name !in it.first }.map { it.second }
                if (children.isNotEmpty()) objectNode.set(name, visit(child, children, emptyList()))
            }
        } else {
            names.forEach { name ->
                objectNode.get(name)?.let { child ->
                    objectNode.set(name, visit(child, nodes.mapNotNull { it.properties[name] }, emptyList()))
                }
            }
        }
    }

    private fun maskNamedProperties(objectNode: ObjectNode, properties: Map<String, QueryMaskDefinition.MaskNode>) {
        if (properties.size <= objectNode.size()) {
            properties.forEach { (name, mask) ->
                objectNode.get(name)?.let { objectNode.set(name, visitSingle(it, mask)) }
            }
        } else {
            objectNode.forEachEntry { name, child ->
                properties[name]?.let { objectNode.set(name, visitSingle(child, it)) }
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun MaskRule.apply(value: String): String = try {
        val masked: String? = compiled.mask(value)
        masked ?: fail("Mask strategy returned null.")
    } catch (error: Exception) {
        throw QuerySchemaValidationException("Mask strategy execution failed.", error)
    }

    private fun fail(message: String): Nothing = throw QuerySchemaValidationException(message)

    companion object {
        fun create(schema: QueryModelSchema): SchemaMasker? = schema.maskDefinition?.let(::SchemaMasker)
    }
}

private fun QueryValueSchema.matchesShape(node: JsonNode): Boolean {
    if (node.isNull) return nullable
    return when (kind) {
        QueryValueKind.UNKNOWN -> true
        QueryValueKind.NULL -> false
        QueryValueKind.OBJECT -> node.isObject
        QueryValueKind.ARRAY -> node.isArray && node.all { checkNotNull(items).matchesShape(it) }
        QueryValueKind.UNION -> alternatives.any { it.matchesShape(node) }
        QueryValueKind.SCALAR -> matchesScalarShape(node)
    }
}

private fun QueryValueSchema.matchesScalarShape(node: JsonNode): Boolean = when {
    node.isString -> QueryValueType.STRING in valueTypes
    node.isIntegralNumber -> QueryValueType.INTEGER in valueTypes || QueryValueType.DECIMAL in valueTypes
    node.isNumber -> QueryValueType.DECIMAL in valueTypes
    node.isBoolean -> QueryValueType.BOOLEAN in valueTypes
    else -> false
}

private fun QueryValueSchema.isStringShape(): Boolean =
    kind == QueryValueKind.SCALAR && valueTypes.size == 1 && QueryValueType.STRING in valueTypes

private fun QueryValueSchema.hasUnknownShape(): Boolean = when (kind) {
    QueryValueKind.UNKNOWN -> true
    QueryValueKind.ARRAY -> checkNotNull(items).hasUnknownShape()
    QueryValueKind.UNION -> alternatives.any { it.hasUnknownShape() }
    else -> false
}

private fun QueryMaskValue.arrayMembers(): List<QueryMaskValue> {
    val rule = checkNotNull(masked.maskRule)
    val allowedItems = allowed.arrayItems()
    if (allowedItems.isEmpty()) return emptyList()
    val allowedValue = if (allowedItems.size == 1) {
        allowedItems.single()
    } else {
        QueryValueSchema(QueryValueKind.UNION, alternatives = allowedItems)
    }
    return masked.arrayItems().map { QueryMaskValue(it.withMask(rule), allowedValue) }
}

private fun QueryValueSchema.arrayItems(): List<QueryValueSchema> = when (kind) {
    QueryValueKind.ARRAY -> listOf(checkNotNull(items))
    QueryValueKind.UNION -> alternatives.flatMap { it.arrayItems() }
    else -> emptyList()
}

private fun JsonNode.isScalarValue(): Boolean = !isObject && !isArray
