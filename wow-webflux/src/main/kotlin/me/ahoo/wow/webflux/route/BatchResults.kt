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

package me.ahoo.wow.webflux.route

import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.exception.toErrorInfo
import me.ahoo.wow.openapi.BatchResult
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

private val log = org.slf4j.LoggerFactory.getLogger("Wow.BatchResult")

fun Flux<AggregateId>.toBatchResult(afterId: String): Mono<BatchResult> {
    return this.materialize().reduce(BatchResult(afterId, 0)) { acc, signal ->
        if (signal.isOnError) {
            if (log.isWarnEnabled) {
                log.warn("Reduce onError.", signal.throwable)
            }
            val error = signal.throwable!!.toErrorInfo()
            return@reduce acc.copy(
                errorCode = error.errorCode,
                errorMsg = error.errorMsg
            )
        }
        if (signal.isOnNext) {
            val aggregateId = signal.get()!!
            val nextAfterId = if (aggregateId.id > acc.afterId) {
                aggregateId.id
            } else {
                acc.afterId
            }
            return@reduce BatchResult(afterId = nextAfterId, size = acc.size + 1)
        }
        acc
    }
}
