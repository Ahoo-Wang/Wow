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

import me.ahoo.wow.api.query.DynamicDocument
import me.ahoo.wow.api.query.MaterializedSnapshot
import me.ahoo.wow.api.query.gateway.QueryDocumentKind
import me.ahoo.wow.query.plan.QueryPlanResultShape
import me.ahoo.wow.query.result.ResultPolicy
import me.ahoo.wow.query.result.ResultPolicyContext
import reactor.core.publisher.Mono

/** Adapts the live legacy masker registries to the canonical result-policy pipeline. */
class MaskingResultPolicy(
    private val stateMaskers: StateDataMaskerRegistry,
    private val eventStreamMaskers: EventStreamMaskerRegistry
) : ResultPolicy {
    override fun apply(context: ResultPolicyContext, value: Any): Mono<Any> = Mono.defer {
        Mono.just(mask(context, value))
    }

    private fun mask(context: ResultPolicyContext, value: Any): Any {
        if (context.resultShape == QueryPlanResultShape.Count) {
            return value
        }
        if (value is DynamicDocument) {
            return dynamicMasker(context).mask(value)
        }
        if (context.target.documentKind == QueryDocumentKind.SNAPSHOT &&
            context.resultShape is QueryPlanResultShape.Typed && value is MaterializedSnapshot<*>
        ) {
            return value.tryMask()
        }
        return value
    }

    private fun dynamicMasker(context: ResultPolicyContext): AggregateDataMasker<out DynamicDocumentMasker> =
        when (context.target.documentKind) {
            QueryDocumentKind.SNAPSHOT -> stateMaskers.getAggregateDataMasker(context.target.namedAggregate)
            QueryDocumentKind.EVENT_STREAM -> eventStreamMaskers.getAggregateDataMasker(context.target.namedAggregate)
        }
}
