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

package me.ahoo.wow.test.query

import me.ahoo.wow.api.query.expression.PortableExpression
import me.ahoo.wow.query.policy.QueryPolicy
import me.ahoo.wow.query.policy.QueryPolicyContext
import me.ahoo.wow.query.policy.QueryPolicyDeniedException
import me.ahoo.wow.query.policy.QueryPolicyResult
import reactor.core.publisher.Mono

/** Executes one [QueryPolicy] through its published SPI with a fixed context. */
class QueryPolicyTestKit(
    private val policy: QueryPolicy,
    private val context: QueryPolicyContext
) {
    fun evaluate(): Mono<QueryPolicyResult> = Mono.defer { policy.evaluate(context) }

    fun expectDenied(reasonCode: String): Mono<Void> = evaluate()
        .flatMap<Void> { Mono.error(AssertionError("Expected query policy denial.")) }
        .switchIfEmpty(Mono.error(AssertionError("Expected query policy denial, but policy completed empty.")))
        .onErrorResume(QueryPolicyDeniedException::class.java) { denied ->
            if (denied.reasonCode == reasonCode) {
                Mono.empty()
            } else {
                Mono.error(AssertionError("Query policy denial reason did not match."))
            }
        }

    fun expectMandatory(expected: PortableExpression): Mono<Void> = evaluate()
        .switchIfEmpty(Mono.error(AssertionError("Expected a query policy result, but policy completed empty.")))
        .flatMap { result ->
            if (result.mandatoryExpression == expected) {
                Mono.empty()
            } else {
                Mono.error(AssertionError("Query policy mandatory expression did not match."))
            }
        }

    override fun toString(): String = "QueryPolicyTestKit(policy=<redacted>, context=<redacted>)"
}
