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

package me.ahoo.wow.query.policy.abac

import me.ahoo.wow.api.abac.AbacTagKey
import me.ahoo.wow.api.abac.AbacTagValue
import me.ahoo.wow.api.abac.AbacTags
import me.ahoo.wow.api.abac.wildcard
import me.ahoo.wow.api.query.expression.LogicalField
import me.ahoo.wow.api.query.expression.LogicalOperator
import me.ahoo.wow.api.query.expression.PortableExpression
import me.ahoo.wow.api.query.expression.PortableLogicalExpression
import me.ahoo.wow.api.query.expression.PortableOperator
import me.ahoo.wow.api.query.expression.PredicateExpression
import me.ahoo.wow.api.query.expression.QueryValue
import me.ahoo.wow.api.query.gateway.QueryDocumentKind
import me.ahoo.wow.query.policy.QueryPolicy
import me.ahoo.wow.query.policy.QueryPolicyContext
import me.ahoo.wow.query.policy.QueryPolicyDeniedException
import me.ahoo.wow.query.policy.QueryPolicyResult
import reactor.core.Exceptions
import reactor.core.publisher.Mono

/** Reproduces legacy Snapshot ABAC matching as mandatory portable expressions. */
class AbacQueryPolicy(
    private val principalTags: PrincipalTagResolver
) : QueryPolicy {
    override fun evaluate(context: QueryPolicyContext): Mono<QueryPolicyResult> {
        if (context.target.documentKind == QueryDocumentKind.EVENT_STREAM) {
            return Mono.just(QueryPolicyResult())
        }
        return principalTags.resolve(context)
            .onErrorMap { error ->
                Exceptions.throwIfFatal(error)
                if (error is QueryPolicyDeniedException) error else denied("ABAC_TAGS_UNAVAILABLE")
            }
            .switchIfEmpty(Mono.error(denied("ABAC_TAGS_REQUIRED")))
            .flatMap { tags -> evaluateTags(tags) }
    }

    private fun evaluateTags(tags: AbacTags): Mono<QueryPolicyResult> = Mono.defer {
        if (tags.isEmpty()) {
            return@defer Mono.error(denied("ABAC_TAGS_REQUIRED"))
        }
        if (!principalTags.declaredKeys.containsAll(tags.keys)) {
            return@defer Mono.error(denied("ABAC_TAGS_UNDECLARED"))
        }
        Mono.just(QueryPolicyResult(mandatoryExpression = combine(LogicalOperator.AND, tags.map(::tagExpression))))
    }

    private fun tagExpression(tag: Map.Entry<AbacTagKey, AbacTagValue>): PortableExpression {
        val field = LogicalField("tags.${tag.key}")
        if (tag.value.wildcard) {
            return predicate(field, PortableOperator.EXISTS, QueryValue.BooleanValue(true))
        }
        val expressions = mutableListOf<PortableExpression>(
            predicate(field, PortableOperator.EXISTS, QueryValue.BooleanValue(false)),
            predicate(field, PortableOperator.EMPTY_COLLECTION)
        )
        if (tag.value.isNotEmpty()) {
            expressions += predicate(
                field,
                PortableOperator.IN,
                *tag.value.map { value -> QueryValue.StringValue(value) }.toTypedArray()
            )
        }
        return combine(LogicalOperator.OR, expressions)
    }

    private fun combine(operator: LogicalOperator, expressions: List<PortableExpression>): PortableExpression =
        if (expressions.size == 1) expressions.single() else PortableLogicalExpression(operator, expressions)

    private fun predicate(
        field: LogicalField,
        operator: PortableOperator,
        vararg values: QueryValue
    ): PredicateExpression = PredicateExpression(field, operator, values.toList())

    private fun denied(reasonCode: String): QueryPolicyDeniedException = QueryPolicyDeniedException(reasonCode)
}
