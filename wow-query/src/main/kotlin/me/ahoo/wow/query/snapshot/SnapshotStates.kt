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

package me.ahoo.wow.query.snapshot

import me.ahoo.wow.api.query.MaterializedSnapshot
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.serialization.state.StateAggregateRecords
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import tools.jackson.databind.node.ObjectNode

fun ObjectNode.toState(): ObjectNode {
    val state = path(StateAggregateRecords.STATE)
    check(state is ObjectNode) { "Snapshot state must be an ObjectNode." }
    return state
}

fun <S : Any> Mono<MaterializedSnapshot<S>>.toState(): Mono<S> {
    return map { it.state }
}

fun Mono<out ObjectNode>.toStateDocument(): Mono<ObjectNode> {
    return map { it.toState() }
}

fun <S : Any> Flux<MaterializedSnapshot<S>>.toState(): Flux<S> {
    return map { it.state }
}

fun Flux<out ObjectNode>.toStateDocument(): Flux<ObjectNode> {
    return map { it.toState() }
}

fun <S : Any> Mono<PagedList<MaterializedSnapshot<S>>>.toStatePagedList(): Mono<PagedList<S>> {
    return map { PagedList(it.total, it.list.map { snapshot -> snapshot.state }) }
}

fun <S : ObjectNode> Mono<PagedList<S>>.toStateDocumentPagedList(): Mono<PagedList<ObjectNode>> {
    return map { PagedList(it.total, it.list.map { node -> node.toState() }) }
}
