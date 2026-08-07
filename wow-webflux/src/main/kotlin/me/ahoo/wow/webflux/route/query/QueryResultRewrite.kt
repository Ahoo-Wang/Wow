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

@file:OptIn(me.ahoo.wow.query.gateway.ExperimentalQueryGatewayApi::class)

package me.ahoo.wow.webflux.route.query

import me.ahoo.wow.query.gateway.QueryErrorCategory
import me.ahoo.wow.query.gateway.QueryExecutionException
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

/** Runs a legacy result transformer only after the Gateway source emitted one value. */
internal fun <T : Any> Mono<T>.rewriteResultOneToOne(
    rewrite: (Mono<T>) -> Mono<T>,
    validate: (original: T, rewritten: T) -> Boolean = { _, _ -> true },
): Mono<T> = flatMap { original ->
    Mono.defer { rewrite(Mono.just(original)) }
        .switchIfEmpty(Mono.error(resultRewriteContractViolation()))
        .flatMap { rewritten ->
            if (validate(original, rewritten)) {
                Mono.just(rewritten)
            } else {
                Mono.error(resultRewriteContractViolation())
            }
        }
}

/** Runs a legacy result transformer once per Gateway item and requires exactly one output for that item. */
internal fun <T : Any> Flux<T>.rewriteResultOneToOne(rewrite: (Flux<T>) -> Flux<T>): Flux<T> =
    concatMap { original ->
        Flux.defer { rewrite(Flux.just(original)) }
            .take(2)
            .collectList()
            .flatMap { rewritten ->
                if (rewritten.size == 1) {
                    Mono.just(rewritten.single())
                } else {
                    Mono.error(resultRewriteContractViolation())
                }
            }
    }

private fun resultRewriteContractViolation(): QueryExecutionException = QueryExecutionException(
    QueryErrorCategory.INTERNAL_FAILURE,
    "$.result.rewrite",
    "RESULT_REWRITE_CONTRACT_VIOLATION",
)
