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

import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.naming.Named
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.Queryable
import me.ahoo.wow.eventsourcing.snapshot.NoOpSnapshotStore
import me.ahoo.wow.query.AdmittedQuery
import me.ahoo.wow.query.BackendPage
import me.ahoo.wow.query.CursorPositionCodec
import me.ahoo.wow.query.GroupWindow
import me.ahoo.wow.query.PageWindow
import me.ahoo.wow.query.QueryBackend
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import tools.jackson.databind.node.ObjectNode

interface SnapshotQueryBackend : QueryBackend, Named

class NoOpSnapshotQueryBackend(
    override val namedAggregate: NamedAggregate,
) : SnapshotQueryBackend {
    override val name: String = NoOpSnapshotStore.NAME
    override val cursorPositions: CursorPositionCodec = CursorPositionCodec.JSON
    override fun stream(query: AdmittedQuery<IListQuery>): Flux<ObjectNode> = Flux.empty()
    override fun page(query: AdmittedQuery<Queryable<*>>, window: PageWindow): Mono<BackendPage> =
        Mono.just(BackendPage(emptyList(), 0, emptyList()))
    override fun count(query: AdmittedQuery<FilterExpression>): Mono<Long> = Mono.just(0L)
    override fun aggregate(query: AdmittedQuery<AggregationQuery>, window: GroupWindow): Flux<ObjectNode> =
        Flux.empty()
}
