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

package me.ahoo.wow.elasticsearch

import co.elastic.clients.elasticsearch.ElasticsearchAsyncClient
import co.elastic.clients.elasticsearch._types.Refresh
import co.elastic.clients.json.jackson.Jackson3JsonpMapper
import co.elastic.clients.transport.rest5_client.Rest5ClientTransport
import co.elastic.clients.transport.rest5_client.low_level.Rest5Client
import me.ahoo.wow.elasticsearch.snapshot.ElasticsearchSnapshotStore
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.ledger.FailureLedger
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.reservation.ReservationStore
import me.ahoo.wow.spi.snapshot.SnapshotStore
import me.ahoo.wow.spi.store.Store
import me.ahoo.wow.spi.store.StoreConfig
import me.ahoo.wow.spi.store.StoreProvider
import me.ahoo.wow.spi.subscription.CheckpointStore
import me.ahoo.wow.spi.subscription.LeaseStore
import me.ahoo.wow.spi.timer.TimerStore

/**
 * The Elasticsearch stores (`wow.stores.<name>.type: elasticsearch`, work package 6.7): snapshots only, never a log
 * (decision D13), so a store of this type serves `wow.snapshot.store`, `wow.snapshot.targets` and an aggregate's
 * `snapshot.store`, never `wow.log.store`. Each store opens over the application's low-level `Rest5Client` that its
 * `client` option names (else the only one), with the settings:
 *
 * - `index-prefix`: a prefix of every index name (`<prefix>_wow_snapshot__<context>__<aggregate>`), for a cluster
 *   several services or environments share; none by default;
 * - `refresh`: when a write becomes visible to searches, `wait_for` (the default: a snapshot is queryable once its
 *   write returns), `true` (an immediate refresh, for tests) or `false` (within the index's refresh interval);
 * - `query-client`: the `Rest5Client` bean the store's queries use, so they draw on a connection pool of their own
 *   instead of the one the snapshot writes use; the store's `client` by default.
 *
 * Found by `ServiceLoader`. It is never `available`: Elasticsearch holds no log, so `wow.backend=auto` never takes it.
 */
internal class ElasticsearchStoreProvider : StoreProvider {
    override val type: String = TYPE

    override val requirement: String = "wow-elasticsearch on the classpath and a Rest5Client bean"

    override fun open(config: StoreConfig): Store {
        val restClient = config.resources.resource(
            Rest5Client::class.java,
            config.option(CLIENT),
            "${config.key}.$CLIENT"
        )
        val queryClient = config.option(QUERY_CLIENT)?.let {
            config.resources.resource(Rest5Client::class.java, it, "${config.key}.$QUERY_CLIENT")
        }
        return ElasticsearchStore(
            config,
            restClient,
            ElasticsearchNames(config.option(INDEX_PREFIX).orEmpty()),
            refreshOf(config.option(REFRESH), "${config.key}.$REFRESH"),
            queryRestClient = queryClient?.takeIf { it !== restClient },
        )
    }

    companion object {
        const val TYPE = "elasticsearch"

        /** The option naming the store's `Rest5Client` bean. */
        const val CLIENT = "client"

        /** The option naming the `Rest5Client` bean of the store's queries, when they have a pool of their own. */
        const val QUERY_CLIENT = "query-client"

        /** The option of the prefix of the store's index names. */
        const val INDEX_PREFIX = "index-prefix"

        /** The option of when a write becomes visible to searches. */
        const val REFRESH = "refresh"

        /** The refresh policy [value] names (`wait_for`, `true`, `false`), `wait_for` when it is not set. */
        fun refreshOf(value: String?, key: String = REFRESH): Refresh = when (value?.lowercase()) {
            null, "wait_for" -> Refresh.WaitFor
            "true" -> Refresh.True
            "false" -> Refresh.False
            else -> throw IllegalArgumentException("$key is $value; it is one of wait_for, true and false.")
        }
    }
}

/**
 * A store on Elasticsearch over [restClient]: each context's snapshots, one index per aggregate type, in the indices
 * [names] gives; its queries over [queryRestClient] when it has one, else over [restClient] too. It holds no log, so
 * no checkpoints, leases, failure ledger, reservations or timers either: the node keeps those with its log.
 */
internal class ElasticsearchStore(
    config: StoreConfig,
    val restClient: Rest5Client,
    val names: ElasticsearchNames,
    val refresh: Refresh = Refresh.WaitFor,
    val queryRestClient: Rest5Client? = null,
) : Store(config) {
    /** The client of Wow's JSON (decision D122): a state's numbers are read and written exactly. */
    val client: ElasticsearchAsyncClient by lazy { client(restClient) }

    /** The client of the store's queries: its own pool's when it has one, else [client]. */
    val queryClient: ElasticsearchAsyncClient by lazy { queryRestClient?.let(::client) ?: client }

    /** Whether the queries draw on the connection pool the snapshot writes use. */
    val queriesShareWrites: Boolean get() = queryRestClient == null

    override val location: Any get() = restClient

    override val holdsLogs: Boolean get() = false

    override fun openSnapshots(context: String): SnapshotStore =
        ElasticsearchSnapshotStore(client, names, context, ddl = config.ddl, refresh = refresh)
            .also { schema(it::createSchema) }

    override fun openCheckpoints(): CheckpointStore = throw noLog()

    override fun openLeases(): LeaseStore = throw noLog()

    override fun openLedger(): FailureLedger = throw noLog()

    override fun openReservations(): ReservationStore = throw noLog()

    override fun openLog(context: String, partitions: Int): EventLog = throw noLog()

    override fun openTimers(context: String, partitions: Int): TimerStore = throw noLog()

    private fun noLog(): UnsupportedOperationException =
        UnsupportedOperationException(
            "The Elasticsearch store $name holds no log: snapshots only."
        )

    companion object {
        /** An asynchronous client over [restClient] that reads and writes Wow's JSON. */
        fun client(restClient: Rest5Client): ElasticsearchAsyncClient =
            ElasticsearchAsyncClient(Rest5ClientTransport(restClient, Jackson3JsonpMapper(WowJson.mapper)))
    }
}
