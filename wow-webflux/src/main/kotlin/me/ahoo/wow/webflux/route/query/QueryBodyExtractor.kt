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

import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.api.query.SingleQuery
import me.ahoo.wow.serialization.toObject
import org.springframework.http.ReactiveHttpInputMessage
import org.springframework.web.reactive.function.BodyExtractor
import org.springframework.web.reactive.function.BodyExtractors
import reactor.core.publisher.Mono
import tools.jackson.databind.node.ObjectNode

class QueryBodyExtractor<Q : Any>(private val queryType: Class<Q>) : BodyExtractor<Mono<Q>, ReactiveHttpInputMessage> {
    companion object {
        val CONDITION_EXTRACTOR = QueryBodyExtractor(Condition::class.java)
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
            .map { objectNode ->
                objectNode.toObject(queryType)
            }
    }
}
