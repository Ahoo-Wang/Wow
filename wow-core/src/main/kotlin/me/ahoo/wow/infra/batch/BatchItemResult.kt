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

package me.ahoo.wow.infra.batch

import reactor.core.publisher.Mono

/**
 * The terminal result for one input in a storage batch.
 */
sealed interface BatchItemResult {
    data object Success : BatchItemResult

    data class Failure(val error: Throwable) : BatchItemResult
}

/**
 * Executes one storage batch.
 *
 * The emitted list must contain exactly one result for every input and preserve
 * input order. A terminal publisher failure marks every input in that batch as
 * failed without terminating the coordinator.
 */
fun interface ReactiveBatchWriter<T : Any> {
    fun write(items: List<T>): Mono<List<BatchItemResult>>
}
