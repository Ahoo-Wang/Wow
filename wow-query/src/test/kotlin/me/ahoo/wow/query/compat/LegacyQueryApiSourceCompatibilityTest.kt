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

package me.ahoo.wow.query.compat

import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.ISingleQuery
import me.ahoo.wow.query.QueryService
import me.ahoo.wow.query.event.EventStreamQueryService
import me.ahoo.wow.query.event.EventStreamQueryServiceFactory
import me.ahoo.wow.query.event.RoutingEventStreamQueryServiceFactory
import me.ahoo.wow.query.mask.EventStreamMaskerRegistry
import me.ahoo.wow.query.mask.StateDataMaskerRegistry
import me.ahoo.wow.query.snapshot.RoutingSnapshotQueryServiceFactory
import me.ahoo.wow.query.snapshot.SnapshotQueryService
import me.ahoo.wow.query.snapshot.SnapshotQueryServiceFactory

class LegacyQueryApiSourceCompatibilityTest {
    @Suppress("Unused")
    private fun compileOnly(
        service: QueryService<Any>,
        snapshotService: SnapshotQueryService<Any>,
        eventStreamService: EventStreamQueryService,
        snapshotFactory: SnapshotQueryServiceFactory,
        eventStreamFactory: EventStreamQueryServiceFactory,
        namedAggregate: NamedAggregate,
        singleQuery: ISingleQuery,
        listQuery: IListQuery,
        pagedQuery: IPagedQuery,
        condition: Condition,
    ) {
        service.single(singleQuery)
        service.dynamicSingle(singleQuery)
        service.list(listQuery)
        service.dynamicList(listQuery)
        service.paged(pagedQuery)
        service.dynamicPaged(pagedQuery)
        service.count(condition)

        snapshotService.single(singleQuery)
        eventStreamService.single(singleQuery)
        snapshotFactory.create<Any>(namedAggregate)
        eventStreamFactory.create(namedAggregate)
        RoutingSnapshotQueryServiceFactory(snapshotFactory, emptyMap())
        RoutingEventStreamQueryServiceFactory(eventStreamFactory, emptyMap())
        StateDataMaskerRegistry()
        EventStreamMaskerRegistry()
    }
}
