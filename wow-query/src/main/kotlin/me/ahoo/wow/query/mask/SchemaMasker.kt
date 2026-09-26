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

import me.ahoo.wow.api.query.schema.QueryValueKind
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.query.QueryExecutionException
import me.ahoo.wow.query.schema.MaskRule
import me.ahoo.wow.query.schema.QueryMaskDefinition
import me.ahoo.wow.query.schema.QueryMaskValue
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QueryValueSchema
import me.ahoo.wow.query.schema.profile
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode

internal class SchemaMasker private constructor(private val definition: QueryMaskDefinition) {
    fun mask(node: ObjectNode): ObjectNode {
        definition.payloadTypes?.let { definition.profile.requireDeclaredPayloadTypes(node, it) }
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
                val members = domains.flatMap { it.arrayMembers }
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
        throw QueryExecutionException("Mask strategy execution failed.", error)
    }

    private fun fail(message: String): Nothing = throw QueryExecutionException(message)

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

private fun JsonNode.isScalarValue(): Boolean = !isObject && !isArray
