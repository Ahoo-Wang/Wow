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

package me.ahoo.wow.query.filter

import reactor.core.publisher.Mono
import reactor.util.context.ContextView

object Contexts {
    private const val RAW_REQUEST_KEY = "__RAW_REQUEST___"

    fun <T : Any> Mono<T>.writeRawRequest(request: Any): Mono<T> {
        return this.contextWrite {
            it.put(RAW_REQUEST_KEY, request)
        }
    }

    fun <R> ContextView.getRawRequest(): R? {
        @Suppress("IMPLICIT_NOTHING_TYPE_ARGUMENT_IN_RETURN_POSITION")
        return this.getOrDefault(RAW_REQUEST_KEY, null)
    }
}
