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

package me.ahoo.wow.spring.boot.node

import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.query.events.UnpinnedEvents
import me.ahoo.wow.spi.id.AggregateIdGenerator
import me.ahoo.wow.spi.id.AggregateIdGeneratorSource
import me.ahoo.wow.spring.boot.WowProperties.CommandRouting
import me.ahoo.wow.spring.boot.WowProperties.Switch
import java.time.Duration

/**
 * An aggregate type's settings. [tenant] fixes the tenant of every aggregate of the type: its commands and state reads are in that tenant whoever the caller is
 * ([me.ahoo.wow.spi.tenant.TenantPolicy]). [idGenerator] names the
 * [me.ahoo.wow.spi.id.AggregateIdGenerator] bean that gives the type's new ids, or a generator an
 * [me.ahoo.wow.spi.id.AggregateIdGeneratorSource] bean finds by that name (CosId's, with wow-cosid); the type's ids
 * come from the `wowAggregateIdGenerator` bean otherwise. A type the node does not serve, or a name nothing gives
 * a generator of, is refused at startup.
 */
@InternalWowApi
class AggregateProperties {
    /** The tenant every aggregate of the type is in, whoever the caller is. */
    var tenant: String? = null

    /** The id generator that gives the type's new ids, by name. */
    var idGenerator: String? = null

    /**
     * How the commands of the type are routed, over `wow.routing` ([CommandRouting]): `topic` writes them to its
     * context's command topic, accepted once the broker acknowledged them, which needs a topic transport;
     * `partitioned` and `local` are the node's, so they name the routing `wow.routing` gives it.
     */
    var routing: CommandRouting = CommandRouting.AUTO

    /** The store of the type's log, over `wow.log.store`: the type's log group. */
    var log: AggregateStoreProperties = AggregateStoreProperties()

    /** The store of the type's snapshots, over `wow.snapshot.store`. */
    var snapshot: AggregateSnapshotProperties = AggregateSnapshotProperties()

    /**
     * How the ClickHouse Sink keeps the type's commits, in the same style as the other per-aggregate keys; it replaces
     * the removed `wow.clickhouse.sink.aggregates`.
     */
    var clickhouse: AggregateClickHouseProperties = AggregateClickHouseProperties()

    /** The type's queries: whether its `events` queries that name no aggregate run, and the store they read. */
    var query: AggregateQueryProperties = AggregateQueryProperties()
}

/** An aggregate type's ClickHouse settings: `wow.aggregates.[<context>.<aggregate>].clickhouse.*`. */
@InternalWowApi
class AggregateClickHouseProperties {
    var history: AggregateHistoryProperties = AggregateHistoryProperties()
}

/**
 * Whether every commit of the type is kept ([enabled]: `auto`, the Sink's default, keeps it; `true` or `false`
 * decide it), and how long ([ttl], forever by default).
 */
@InternalWowApi
class AggregateHistoryProperties {
    /** Whether every commit of the type is kept: `auto`, the Sink's default, keeps it; `true` or `false` decide. */
    var enabled: Switch = Switch.AUTO

    /** How long the type's history is kept; forever when not set. */
    var ttl: Duration? = null
}

/** An aggregate type's queries (`wow.aggregates.[<context>.<aggregate>].query.*`). */
@InternalWowApi
class AggregateQueryProperties {
    var events: AggregateQueryEventsProperties = AggregateQueryEventsProperties()
    var snapshot: AggregateQuerySnapshotProperties = AggregateQuerySnapshotProperties()
}

/**
 * An aggregate type's `snapshot` queries: [store], the store they read, its `snapshot.store` when not set. Another store
 * keeps a copy of the type's snapshots for them, written by that store's subscription (`wow.snapshot:<store>`): an
 * Elasticsearch store that searches the states, say, while the commands and the state reads stay on the type's store.
 */
@InternalWowApi
class AggregateQuerySnapshotProperties {
    /**
     * The store of `wow.stores` the type's `snapshot` queries read, which then keeps a copy of its snapshots; its
     * `snapshot.store` when not set.
     */
    var store: String? = null
}

/**
 * An aggregate type's `events` queries: [unpinned], whether one that names no aggregate runs,
 * over its events store's and `wow.query.events.unpinned`; [store], the store they read (one of `wow.stores`), its
 * log store when not set: an Elasticsearch store whose event search Sink writes the type's streams, say.
 */
@InternalWowApi
class AggregateQueryEventsProperties {
    /** Whether an `events` query of the type that names no aggregate runs, over its store's. */
    var unpinned: UnpinnedEvents? = null

    /** The store of `wow.stores` the type's `events` queries read; its log store when not set. */
    var store: String? = null
}

/**
 * One aggregate type's snapshot store: [store], the store of `wow.stores` its snapshots are kept on, over
 * `wow.snapshot.store`, which its write side, its state reads and its snapshot queries read, unless its
 * `query.snapshot.store` names another for the queries.
 */
@InternalWowApi
class AggregateSnapshotProperties {
    /** The store of `wow.stores` the type's snapshots are kept on, over `wow.snapshot.store`. */
    var store: String? = null
}

/** One aggregate type's store of its log or of its snapshots: one of `wow.stores`. */
@InternalWowApi
class AggregateStoreProperties {
    /** The store of `wow.stores` the type's log is on, over `wow.log.store`. */
    var store: String? = null
}
