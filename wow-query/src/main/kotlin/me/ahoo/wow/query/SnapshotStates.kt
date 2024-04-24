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

package me.ahoo.wow.query

import me.ahoo.wow.api.query.DynamicDocument
import me.ahoo.wow.api.query.MaterializedSnapshot
import me.ahoo.wow.api.query.PagedList
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

private const val STATE_FIELD = "state"

fun DynamicDocument.toState(): DynamicDocument {
    return getNestedDocument(STATE_FIELD)
}

fun <S : Any> Mono<MaterializedSnapshot<S>>.toState(): Mono<S> {
    return map { it.state }
}

fun Mono<out DynamicDocument>.toStateDocument(): Mono<DynamicDocument> {
    return map { it.toState() }
}

fun <S : Any> Flux<MaterializedSnapshot<S>>.toState(): Flux<S> {
    return map { it.state }
}

fun Flux<out DynamicDocument>.toStateDocument(): Flux<DynamicDocument> {
    return map { it.toState() }
}

fun <S : Any> Mono<PagedList<MaterializedSnapshot<S>>>.toStatePagedList(): Mono<PagedList<S>> {
    return map { PagedList(it.total, it.list.map { snapshot -> snapshot.state }) }
}

fun <S : DynamicDocument> Mono<PagedList<S>>.toStateDocumentPagedList(): Mono<PagedList<DynamicDocument>> {
    return map { PagedList(it.total, it.list.map { dynamicDocument -> dynamicDocument.toState() }) }
}
