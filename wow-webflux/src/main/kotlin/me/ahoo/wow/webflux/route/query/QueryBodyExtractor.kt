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

package me.ahoo.wow.webflux.route.query

import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.ElementMatchFilter
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.FilterCapable
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.NorFilter
import me.ahoo.wow.api.query.NotEqualFilter
import me.ahoo.wow.api.query.OrFilter
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.api.query.SingleQuery
import me.ahoo.wow.api.query.toFilterExpression
import me.ahoo.wow.serialization.JsonSerializer
import me.ahoo.wow.serialization.toObject
import me.ahoo.wow.webflux.route.mapRequestBodyDecodingException
import org.springframework.http.ReactiveHttpInputMessage
import org.springframework.web.reactive.function.BodyExtractor
import org.springframework.web.reactive.function.BodyExtractors
import reactor.core.publisher.Mono
import tools.jackson.core.JacksonException
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.node.ObjectNode

class QueryBodyExtractor<Q : Any>(private val queryType: Class<Q>) : BodyExtractor<Mono<Q>, ReactiveHttpInputMessage> {
    companion object {
        val AGGREGATION_QUERY_EXTRACTOR = QueryBodyExtractor(AggregationQuery::class.java)
        val FILTER_EXPRESSION_EXTRACTOR = QueryBodyExtractor(FilterExpression::class.java)
        val LIST_QUERY_EXTRACTOR = QueryBodyExtractor(ListQuery::class.java)
        val PAGED_QUERY_EXTRACTOR = QueryBodyExtractor(PagedQuery::class.java)
        val SINGLE_QUERY_EXTRACTOR = QueryBodyExtractor(SingleQuery::class.java)
    }

    override fun extract(
        inputMessage: ReactiveHttpInputMessage,
        context: BodyExtractor.Context
    ): Mono<Q> {
        return BodyExtractors.toMono(ObjectNode::class.java)
            .extract(inputMessage, context)
            .mapRequestBodyDecodingException()
            .map(::decode)
    }

    private fun decode(objectNode: ObjectNode): Q {
        if (queryType == FilterExpression::class.java) {
            return decodeCount(objectNode)
        }
        if (queryType == AggregationQuery::class.java) {
            return strictDecode(objectNode)
        }
        val hasFilter = objectNode.has("filter")
        val hasCondition = objectNode.has("condition")
        require(hasFilter.xor(hasCondition)) { "Exactly one of filter or condition is required." }
        if (hasFilter) {
            return strictDecode(objectNode)
        }
        val condition = objectNode.remove("condition").toObject(Condition::class.java)
        objectNode.set("filter", JsonSerializer.valueToTree(MatchAllFilter))
        val query = objectNode.toObject(queryType)
        @Suppress("UNCHECKED_CAST")
        return (query as FilterCapable<*>).withFilter(condition.toFilterExpression()) as Q
    }

    @Suppress("UNCHECKED_CAST")
    private fun decodeCount(objectNode: ObjectNode): Q {
        val hasFilter = objectNode.has("op")
        val hasCondition = objectNode.has("operator")
        require(!(hasFilter && hasCondition)) { "op and operator cannot be used together." }
        return if (hasFilter) {
            strictDecode(objectNode)
        } else {
            objectNode.toObject(Condition::class.java).toFilterExpression() as Q
        }
    }

    private fun strictDecode(objectNode: ObjectNode): Q = try {
        val decoded: Q = JsonSerializer.readerFor(queryType)
            .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .readValue(objectNode)
        requireStrictFilterValues(decoded)
        decoded
    } catch (error: JacksonException) {
        throw IllegalArgumentException("Invalid filter request body.", error)
    }

    private fun requireStrictFilterValues(decoded: Q) {
        when (decoded) {
            is FilterExpression -> decoded
            is FilterCapable<*> -> decoded.filter
            else -> null
        }?.requireScalarEqualityValues()
    }

    private fun FilterExpression.requireScalarEqualityValues() {
        when (this) {
            is EqualFilter -> value.requireScalarEqualityValue(operator.name)
            is NotEqualFilter -> value.requireScalarEqualityValue(operator.name)
            is AndFilter -> operands.forEach { it.requireScalarEqualityValues() }
            is OrFilter -> operands.forEach { it.requireScalarEqualityValues() }
            is NorFilter -> operands.forEach { it.requireScalarEqualityValues() }
            is ElementMatchFilter -> predicate.requireScalarEqualityValues()
            else -> Unit
        }
    }

    private fun tools.jackson.databind.JsonNode.requireScalarEqualityValue(operator: String) {
        require(isNull || isString || isNumber || isBoolean) {
            "$operator value must be a JSON scalar in filter payloads."
        }
    }
}
