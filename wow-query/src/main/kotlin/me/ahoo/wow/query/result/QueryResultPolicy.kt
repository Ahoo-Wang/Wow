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

package me.ahoo.wow.query.result

import me.ahoo.wow.api.query.QueryErrorCode
import me.ahoo.wow.api.query.QueryException
import me.ahoo.wow.api.query.QueryStage
import me.ahoo.wow.query.backend.SecuredQuery
import me.ahoo.wow.query.policy.QueryAuthority
import me.ahoo.wow.query.schema.QueryCollectionKind
import me.ahoo.wow.query.schema.QueryFieldSchema
import me.ahoo.wow.query.schema.QuerySchema
import me.ahoo.wow.query.schema.QueryValueKind
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode
import java.time.Instant
import java.util.Base64
import java.util.IdentityHashMap

data class QueryResultContext(
    val query: SecuredQuery,
    val authority: QueryAuthority,
    val subscribedAt: Instant
) {
    override fun toString(): String = "QueryResultContext(query=<redacted>, authority=<redacted>)"
}

fun interface QueryResultPolicy {
    fun transform(context: QueryResultContext, record: ObjectNode): ObjectNode
}

internal class QueryResultPolicyChain(private val policies: List<QueryResultPolicy>) {
    fun transform(context: QueryResultContext, source: ObjectNode): ObjectNode = try {
        var current = canonicalSnapshot(source, context.query.schema)
        validateTarget(current, context.query)
        policies.forEach { policy ->
            val identity = protectedFields(current)
            current = canonicalSnapshot(policy.transform(context, current), context.query.schema)
            validateTarget(current, context.query)
            if (identity != protectedFields(current)) resultInvalid()
        }
        current
    } catch (error: QueryException) {
        throw error
    } catch (@Suppress("TooGenericExceptionCaught") error: RuntimeException) {
        throw QueryException(QueryErrorCode.RESULT_INVALID, QueryStage.RESULT_POLICY)
    }

    private fun validateTarget(record: ObjectNode, query: SecuredQuery) {
        if (record["contextName"].stringValue() != query.target.contextName ||
            record["aggregateName"].stringValue() != query.target.aggregateName
        ) {
            resultInvalid()
        }
    }

    private fun protectedFields(record: ObjectNode): ObjectNode = JsonNodeFactory.instance.objectNode().also { result ->
        record.properties().forEach { (name, value) ->
            if (name != "state") result.set(name, value.deepCopy())
        }
    }

    private fun resultInvalid(): Nothing = throw QueryException(QueryErrorCode.RESULT_INVALID, QueryStage.RESULT_POLICY)
}

internal fun canonicalSnapshot(
    source: ObjectNode,
    schema: QuerySchema,
    maxNodes: Int = 100_000
): ObjectNode {
    val target = JsonNodeFactory.instance.objectNode()
    val active = IdentityHashMap<JsonNode, Boolean>()
    val work = ArrayDeque<CopyWork>()
    active[source] = true
    work += CopyWork.Exit(source)
    source.properties().toList().asReversed().forEach { (name, value) ->
        work += CopyWork.Visit(value, Destination.ObjectField(target, name))
    }
    var nodes = 1
    while (work.isNotEmpty()) {
        when (val current = work.removeLast()) {
            is CopyWork.Exit -> active.remove(current.source)
            is CopyWork.Visit -> {
                nodes++
                if (nodes > maxNodes) resultInvalid()
                when (val node = current.source) {
                    is ObjectNode -> {
                        if (active.put(node, true) != null) resultInvalid()
                        val copied = JsonNodeFactory.instance.objectNode()
                        current.destination.set(copied)
                        work += CopyWork.Exit(node)
                        node.properties().toList().asReversed().forEach { (name, child) ->
                            work += CopyWork.Visit(child, Destination.ObjectField(copied, name))
                        }
                    }

                    is ArrayNode -> {
                        if (active.put(node, true) != null) resultInvalid()
                        val copied = JsonNodeFactory.instance.arrayNode()
                        current.destination.set(copied)
                        work += CopyWork.Exit(node)
                        node.toList().asReversed().forEach { child ->
                            work += CopyWork.Visit(child, Destination.ArrayElement(copied))
                        }
                    }

                    else -> current.destination.set(canonicalValue(node))
                }
            }
        }
    }
    validateCanonicalFields(target, schema)
    return target
}

private sealed interface CopyWork {
    data class Visit(val source: JsonNode, val destination: Destination) : CopyWork

    data class Exit(val source: JsonNode) : CopyWork
}

private sealed interface Destination {
    fun set(value: JsonNode)

    data class ObjectField(val target: ObjectNode, val name: String) : Destination {
        override fun set(value: JsonNode) {
            target.set(name, value)
        }
    }

    data class ArrayElement(val target: ArrayNode) : Destination {
        override fun set(value: JsonNode) {
            target.add(value)
        }
    }
}

private fun canonicalValue(value: JsonNode): JsonNode = when {
    value.isMissingNode || value.isPojo -> resultInvalid()
    value.isBinary -> JsonNodeFactory.instance.stringNode(Base64.getEncoder().encodeToString(value.binaryValue()))
    value.isFloatingPointNumber && !value.doubleValue().isFinite() -> resultInvalid()
    value.isValueNode -> value.deepCopy()
    else -> resultInvalid()
}

private fun validateCanonicalFields(record: ObjectNode, schema: QuerySchema) {
    val allowedRoots = schema.fields.keys.mapTo(hashSetOf()) { it.value.substringBefore('.') }
    if (record.propertyNames().asSequence().any { it !in allowedRoots }) resultInvalid()
    requireText(record, "contextName")
    requireText(record, "aggregateName")
    requireText(record, "tenantId")
    requireText(record, "ownerId", nonBlank = false)
    requireText(record, "spaceId", nonBlank = false)
    requireText(record, "aggregateId")
    requireText(record, "eventId", nonBlank = false)
    requireText(record, "firstOperator", nonBlank = false)
    requireText(record, "operator", nonBlank = false)
    if (record["version"]?.isInt != true) resultInvalid()
    if (record["deleted"]?.isBoolean != true) resultInvalid()
    if (record["tags"] !is ObjectNode || record["state"] !is ObjectNode) resultInvalid()
    listOf("firstEventTime", "eventTime", "snapshotTime").forEach { field ->
        val value = record[field]
        if (value?.isString != true) resultInvalid()
        try {
            Instant.parse(value.stringValue())
        } catch (_: RuntimeException) {
            resultInvalid()
        }
    }
    validateState(record["state"], schema)
}

private fun validateState(state: JsonNode, schema: QuerySchema) {
    val root = SchemaNode()
    schema.fields.values.forEach { field ->
        if (!field.path.value.startsWith("state.")) return@forEach
        var current = root
        field.path.value.removePrefix("state.").split('.').forEach { segment ->
            current = current.children.getOrPut(segment, ::SchemaNode)
        }
        current.field = field
    }
    validateObject(state as? ObjectNode ?: resultInvalid(), root)
}

private fun validateObject(value: ObjectNode, schema: SchemaNode) {
    value.properties().forEach { (name, child) ->
        validateNode(child, schema.children[name] ?: resultInvalid())
    }
}

private fun validateNode(value: JsonNode, schema: SchemaNode) {
    val field = schema.field ?: resultInvalid()
    if (value.isNull) {
        if (!field.nullable) resultInvalid()
        return
    }
    when (field.collectionKind) {
        QueryCollectionKind.NONE -> validateSingle(value, field, schema)
        QueryCollectionKind.SCALAR -> {
            val array = value as? ArrayNode ?: resultInvalid()
            array.forEach { element -> if (!element.isNull) validateScalar(element, field.valueKind) }
        }

        QueryCollectionKind.OBJECT -> {
            val array = value as? ArrayNode ?: resultInvalid()
            array.forEach { element ->
                if (!element.isNull) validateObject(element as? ObjectNode ?: resultInvalid(), schema)
            }
        }
    }
}

private fun validateSingle(value: JsonNode, field: QueryFieldSchema, schema: SchemaNode) {
    when (field.valueKind) {
        QueryValueKind.OBJECT -> validateObject(value as? ObjectNode ?: resultInvalid(), schema)
        QueryValueKind.MAP -> if (value !is ObjectNode) resultInvalid()
        else -> validateScalar(value, field.valueKind)
    }
}

private fun validateScalar(value: JsonNode, kind: QueryValueKind) {
    val valid = when (kind) {
        QueryValueKind.BOOLEAN -> value.isBoolean
        QueryValueKind.INTEGER -> value.isIntegralNumber
        QueryValueKind.DECIMAL -> value.isNumber
        QueryValueKind.STRING,
        QueryValueKind.ENUM,
        QueryValueKind.BINARY -> value.isString

        QueryValueKind.TIME -> value.isString || value.isIntegralNumber
        QueryValueKind.OBJECT,
        QueryValueKind.MAP -> false
    }
    if (!valid) resultInvalid()
}

private class SchemaNode(
    var field: QueryFieldSchema? = null,
    val children: MutableMap<String, SchemaNode> = linkedMapOf()
)

private fun requireText(record: ObjectNode, field: String, nonBlank: Boolean = true) {
    val value = record[field]
    if (value?.isString != true || nonBlank && value.stringValue().isBlank()) resultInvalid()
}

private fun resultInvalid(): Nothing = throw QueryException(QueryErrorCode.RESULT_INVALID, QueryStage.RESULT_POLICY)
