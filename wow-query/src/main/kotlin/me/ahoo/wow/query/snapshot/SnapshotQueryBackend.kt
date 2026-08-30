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
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.ISingleQuery
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.eventsourcing.snapshot.NoOpSnapshotStore
import me.ahoo.wow.query.QueryBackend
import me.ahoo.wow.query.schema.QueryModelSchemaProvider
import me.ahoo.wow.query.schema.QuerySchemaUnavailableException
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import tools.jackson.databind.node.ObjectNode

interface SnapshotQueryBackend : QueryBackend, Named

fun SnapshotQueryBackend.requiredQueryModelSchemaProvider(): QueryModelSchemaProvider =
    this as? QueryModelSchemaProvider
        ?: throw QuerySchemaUnavailableException("Snapshot query backend [$name] does not provide QueryModelSchema.")

class NoOpSnapshotQueryBackend(
    override val namedAggregate: NamedAggregate,
) : SnapshotQueryBackend {
    override val name: String = NoOpSnapshotStore.NAME
    override fun single(query: ISingleQuery): Mono<ObjectNode> = Mono.empty()
    override fun list(query: IListQuery): Flux<ObjectNode> = Flux.empty()
    override fun paged(query: IPagedQuery): Mono<PagedList<ObjectNode>> = Mono.just(PagedList.empty())
    override fun count(filter: FilterExpression): Mono<Long> = Mono.just(0L)
    override fun aggregate(query: AggregationQuery): Flux<ObjectNode> = Flux.empty()
}
