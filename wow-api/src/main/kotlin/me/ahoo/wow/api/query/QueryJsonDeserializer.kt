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

@file:Suppress("DEPRECATION")

package me.ahoo.wow.api.query

import tools.jackson.core.JsonParser
import tools.jackson.databind.DeserializationContext
import tools.jackson.databind.JsonNode
import tools.jackson.databind.deser.std.StdDeserializer
import tools.jackson.databind.node.ObjectNode

internal class ListQueryJsonDeserializer : StdDeserializer<ListQuery>(ListQuery::class.java) {
    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): ListQuery =
        ctxt.constructQuery(ListQuery::class.java) {
            readQuery(p, ctxt, ListQueryJson::class.java).toQuery()
        }
}

internal class PagedQueryJsonDeserializer : StdDeserializer<PagedQuery>(PagedQuery::class.java) {
    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): PagedQuery =
        ctxt.constructQuery(PagedQuery::class.java) {
            readQuery(p, ctxt, PagedQueryJson::class.java).toQuery()
        }
}

internal class SingleQueryJsonDeserializer : StdDeserializer<SingleQuery>(SingleQuery::class.java) {
    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): SingleQuery =
        ctxt.constructQuery(SingleQuery::class.java) {
            readQuery(p, ctxt, SingleQueryJson::class.java).toQuery()
        }
}

private inline fun <Q : Any> DeserializationContext.constructQuery(type: Class<Q>, factory: () -> Q): Q =
    try {
        factory()
    } catch (error: IllegalArgumentException) {
        reportInputMismatch(type, error.message ?: "Invalid query body.")
    }

private fun <Q : Any> readQuery(
    p: JsonParser,
    ctxt: DeserializationContext,
    inputType: Class<Q>,
): Q {
    val node = ctxt.readTree(p)
    if (node !is ObjectNode) {
        return ctxt.reportInputMismatch(inputType, "Query body must be a JSON object.")
    }
    node.prepareCompatibleFilter(ctxt, inputType)
    return ctxt.readTreeAsValue(node, inputType)
}

private fun ObjectNode.prepareCompatibleFilter(
    ctxt: DeserializationContext,
    inputType: Class<*>,
) {
    val hasFilter = has(QueryProtocol.QueryEnvelope.FILTER)
    val hasCondition = has(QueryProtocol.QueryEnvelope.CONDITION)
    if (hasFilter && hasCondition) {
        ctxt.reportInputMismatch<Nothing>(inputType, "filter and condition cannot be used together.")
    }
    if (!hasFilter && !hasCondition) {
        ctxt.reportInputMismatch<Nothing>(inputType, "Exactly one of filter or condition is required.")
    }
    listOf(QueryProtocol.QueryEnvelope.FILTER, QueryProtocol.QueryEnvelope.CONDITION).forEach { property ->
        if (get(property)?.isNull == true) {
            ctxt.reportInputMismatch<Nothing>(inputType, "$property cannot be null.")
        }
    }
    if (hasCondition) {
        removeUnknownLegacyQueryProperties(inputType)
    }
}

private val LEGACY_CONDITION_PROPERTIES = setOf(
    QueryProtocol.Condition.FIELD,
    QueryProtocol.Condition.OPERATOR,
    QueryProtocol.Condition.VALUE,
    QueryProtocol.Condition.CHILDREN,
    QueryProtocol.Condition.OPTIONS,
)
private val LEGACY_PROJECTION_PROPERTIES = setOf(
    QueryProtocol.Projection.INCLUDE,
    QueryProtocol.Projection.EXCLUDE,
)
private val LEGACY_SORT_PROPERTIES = setOf(QueryProtocol.Sort.FIELD, QueryProtocol.Sort.DIRECTION)
private val LEGACY_PAGINATION_PROPERTIES = setOf(QueryProtocol.Pagination.INDEX, QueryProtocol.Pagination.SIZE)

// The legacy WebFlux path ignored unknown properties across the entire query body.
private fun ObjectNode.removeUnknownLegacyQueryProperties(inputType: Class<*>) {
    val queryProperties = when (inputType) {
        ListQueryJson::class.java -> setOf(
            QueryProtocol.QueryEnvelope.CONDITION,
            QueryProtocol.QueryEnvelope.PROJECTION,
            QueryProtocol.QueryEnvelope.SORT,
            QueryProtocol.QueryEnvelope.LIMIT,
        )
        PagedQueryJson::class.java -> setOf(
            QueryProtocol.QueryEnvelope.CONDITION,
            QueryProtocol.QueryEnvelope.PROJECTION,
            QueryProtocol.QueryEnvelope.SORT,
            QueryProtocol.QueryEnvelope.PAGINATION,
        )
        SingleQueryJson::class.java -> setOf(
            QueryProtocol.QueryEnvelope.CONDITION,
            QueryProtocol.QueryEnvelope.PROJECTION,
            QueryProtocol.QueryEnvelope.SORT,
        )
        else -> error("Unsupported legacy query type: ${inputType.name}.")
    }
    remove(propertyNames().filterNot(queryProperties::contains))
    get(QueryProtocol.QueryEnvelope.CONDITION)?.removeUnknownLegacyConditionProperties()
    get(QueryProtocol.QueryEnvelope.PROJECTION)?.removeUnknownProperties(LEGACY_PROJECTION_PROPERTIES)
    get(QueryProtocol.QueryEnvelope.SORT)?.forEach { it.removeUnknownProperties(LEGACY_SORT_PROPERTIES) }
    get(QueryProtocol.QueryEnvelope.PAGINATION)?.removeUnknownProperties(LEGACY_PAGINATION_PROPERTIES)
}

private fun JsonNode.removeUnknownProperties(properties: Set<String>) {
    if (this is ObjectNode) {
        remove(propertyNames().filterNot(properties::contains))
    }
}

private fun JsonNode.removeUnknownLegacyConditionProperties() {
    if (this !is ObjectNode) {
        return
    }
    require(!(has(QueryProtocol.FilterExpression.OP) && has(QueryProtocol.Condition.OPERATOR))) {
        "op and operator cannot be used together."
    }
    remove(propertyNames().filterNot(LEGACY_CONDITION_PROPERTIES::contains))
    get(QueryProtocol.Condition.CHILDREN)?.forEach(JsonNode::removeUnknownLegacyConditionProperties)
}

internal fun JsonNode.toLegacyFilterExpression(ctxt: DeserializationContext): FilterExpression {
    removeUnknownLegacyConditionProperties()
    return ctxt.readTreeAsValue(this, Condition::class.java).toFilterExpression()
}

private fun compatibleFilter(filter: FilterExpression?, condition: Condition?): FilterExpression =
    filter ?: requireNotNull(condition).toFilterExpression()

private data class ListQueryJson(
    val filter: FilterExpression? = null,
    val condition: Condition? = null,
    val projection: Projection = Projection.ALL,
    val sort: List<Sort> = emptyList(),
    val limit: Int = 0,
) {
    fun toQuery(): ListQuery = ListQuery(compatibleFilter(filter, condition), projection, sort, limit)
}

private data class PagedQueryJson(
    val filter: FilterExpression? = null,
    val condition: Condition? = null,
    val projection: Projection = Projection.ALL,
    val sort: List<Sort> = emptyList(),
    val pagination: Pagination = Pagination.DEFAULT,
) {
    fun toQuery(): PagedQuery = PagedQuery(compatibleFilter(filter, condition), projection, sort, pagination)
}

private data class SingleQueryJson(
    val filter: FilterExpression? = null,
    val condition: Condition? = null,
    val projection: Projection = Projection.ALL,
    val sort: List<Sort> = emptyList(),
) {
    fun toQuery(): SingleQuery = SingleQuery(compatibleFilter(filter, condition), projection, sort)
}
