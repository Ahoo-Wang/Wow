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

internal class AggregationQueryJsonDeserializer : StdDeserializer<AggregationQuery>(AggregationQuery::class.java) {
    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): AggregationQuery =
        ctxt.constructQuery(AggregationQuery::class.java) {
            readQuery(
                p = p,
                ctxt = ctxt,
                inputType = AggregationQueryJson::class.java,
                filterRequired = false,
                strictCondition = true,
            ).toQuery()
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
    filterRequired: Boolean = true,
    strictCondition: Boolean = false,
): Q {
    val node = ctxt.readTree(p)
    if (node !is ObjectNode) {
        return ctxt.reportInputMismatch(inputType, "Query body must be a JSON object.")
    }
    node.prepareCompatibleFilter(ctxt, inputType, filterRequired, strictCondition)
    return ctxt.readTreeAsValue(node, inputType)
}

private fun ObjectNode.prepareCompatibleFilter(
    ctxt: DeserializationContext,
    inputType: Class<*>,
    filterRequired: Boolean,
    strictCondition: Boolean,
) {
    val hasFilter = has("filter")
    val hasCondition = has("condition")
    if (hasFilter && hasCondition) {
        ctxt.reportInputMismatch<Nothing>(inputType, "filter and condition cannot be used together.")
    }
    if (filterRequired && !hasFilter && !hasCondition) {
        ctxt.reportInputMismatch<Nothing>(inputType, "Exactly one of filter or condition is required.")
    }
    listOf("filter", "condition").forEach { property ->
        if (get(property)?.isNull == true) {
            ctxt.reportInputMismatch<Nothing>(inputType, "$property cannot be null.")
        }
    }
    get("condition")?.let { condition ->
        if (strictCondition) {
            condition.requireLegacyConditionProperties(ctxt, inputType)
        } else {
            condition.removeUnknownLegacyConditionProperties()
        }
    }
}

private val LEGACY_CONDITION_PROPERTIES = setOf("field", "operator", "value", "children", "options")

private fun JsonNode.requireLegacyConditionProperties(ctxt: DeserializationContext, inputType: Class<*>) {
    if (this !is ObjectNode) {
        return
    }
    propertyNames().firstOrNull { it !in LEGACY_CONDITION_PROPERTIES }?.let { property ->
        ctxt.reportInputMismatch<Nothing>(inputType, "Unknown condition property: $property.")
    }
    get("children")?.forEach { it.requireLegacyConditionProperties(ctxt, inputType) }
}

private fun JsonNode.removeUnknownLegacyConditionProperties() {
    if (this !is ObjectNode) {
        return
    }
    remove(propertyNames().filterNot(LEGACY_CONDITION_PROPERTIES::contains))
    get("children")?.forEach(JsonNode::removeUnknownLegacyConditionProperties)
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

private data class AggregationQueryJson(
    val filter: FilterExpression? = null,
    val condition: Condition? = null,
    val elements: List<AggregationElement> = emptyList(),
    val groupBy: List<AggregationGroup> = emptyList(),
    val metrics: List<AggregationMetric>,
    val sort: List<Sort> = emptyList(),
    val limit: Int = AggregationQuery.DEFAULT_LIMIT,
) {
    fun toQuery(): AggregationQuery {
        val resolvedFilter = filter ?: condition?.toFilterExpression()?.also(FilterExpression::requireScalarEqualityValues)
            ?: MatchAllFilter
        return AggregationQuery(resolvedFilter, elements, groupBy, metrics, sort, limit)
    }
}
