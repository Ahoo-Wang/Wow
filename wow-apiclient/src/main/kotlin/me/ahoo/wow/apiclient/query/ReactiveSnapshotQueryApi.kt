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

package me.ahoo.wow.apiclient.query

import org.springframework.web.reactive.function.client.WebClientResponseException
import reactor.core.publisher.Mono

interface ReactiveSnapshotQueryApi<S : Any> :
    ReactiveSnapshotSingleQueryApi<S>,
    ReactiveSnapshotListQueryApi<S>,
    ReactiveSnapshotPagedQueryApi<S>,
    ReactiveSnapshotCountQueryApi

fun <T> Mono<T>.switchNotFoundToEmpty(): Mono<T> {
    return onErrorResume(WebClientResponseException.NotFound::class.java) {
        Mono.empty()
    }
}
