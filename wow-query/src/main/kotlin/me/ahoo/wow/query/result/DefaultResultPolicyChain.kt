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

import me.ahoo.wow.api.query.error.QueryErrorCode
import me.ahoo.wow.api.query.error.QueryErrorReason
import me.ahoo.wow.api.query.error.QueryException
import me.ahoo.wow.api.query.error.QueryStage
import reactor.core.Exceptions
import reactor.core.publisher.Mono
import java.util.Collections

internal class DefaultResultPolicyChain(policies: List<ResultPolicy>) {
    private val policies: List<ResultPolicy> = Collections.unmodifiableList(ArrayList(policies))

    fun apply(context: ResultPolicyContext, value: Any): Mono<Any> = policies.fold(Mono.just(value)) { result, policy ->
        result.flatMap { current ->
            Mono.defer { policy.apply(context, current) }
                .flux()
                .collectList()
                .flatMap { values ->
                    if (values.size == 1) Mono.just(values.single()) else Mono.error(resultInvalid())
                }
        }
    }.onErrorMap { error ->
        Exceptions.throwIfFatal(error)
        if (error is QueryException && error.code == QueryErrorCode.RESULT_VALIDATION_FAILED) {
            error
        } else {
            resultInvalid()
        }
    }

    private fun resultInvalid(): QueryException = QueryException(
        QueryErrorCode.RESULT_VALIDATION_FAILED,
        QueryStage.RESULT_POLICY,
        QueryErrorReason.RESULT_INVALID
    )
}
