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

package me.ahoo.wow.bi.layout

import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.bi.BiObjectKey
import me.ahoo.wow.bi.BiScriptOptions
import me.ahoo.wow.bi.ClickHouseTopology

/**
 * The BI catalog layout: the only place that spells object names, engines and store schemas.
 *
 * Every other part of the module (planning, inspection, validation, rendering) asks the layout instead of building a
 * name or an engine string itself.
 */
internal class BiLayout(val options: BiScriptOptions) {
    private val naming = BiTableNaming(options)

    val anchor: BiObjectKey = BiObjectKey(options.consumerDatabase, ANCHOR_NAME)

    fun of(namedAggregate: NamedAggregate): BiAggregateLayout = BiAggregateLayout(
        command = stream(namedAggregate, COMMAND, BiStoreSchema.COMMAND),
        state = stream(namedAggregate, STATE, BiStoreSchema.STATE),
        stateLast = naming.toTableName(namedAggregate, STATE_LAST),
    )

    /** The table that holds a logical store's rows: the store itself, or its replicated `_local` table on a cluster. */
    fun physicalStore(store: String): String = when (options.topology) {
        is ClickHouseTopology.Cluster -> localStore(store)
        ClickHouseTopology.Standalone -> store
    }

    /** The engine a logical store reports in the catalog. */
    val storeEngine: String
        get() = when (options.topology) {
            is ClickHouseTopology.Cluster -> BiEngine.DISTRIBUTED
            ClickHouseTopology.Standalone -> BiEngine.REPLACING_MERGE_TREE
        }

    fun storeKey(store: String): BiObjectKey = BiObjectKey(options.database, store)

    fun viewKey(view: String): BiObjectKey = BiObjectKey(options.database, view)

    fun ingressKey(name: String): BiObjectKey = BiObjectKey(options.consumerDatabase, name)

    private fun stream(namedAggregate: NamedAggregate, suffix: String, schema: BiStoreSchema): BiStreamLayout {
        val table = naming.toTableName(namedAggregate, suffix)
        return BiStreamLayout(
            table = table,
            store = store(table),
            queue = "${table}$QUEUE_SUFFIX",
            consumer = consumer(table),
            topic = naming.toTopicName(namedAggregate, suffix),
            schema = schema,
        )
    }

    companion object {
        const val ANCHOR_NAME: String = "__wow_bi_deployment"
        const val COMMAND: String = "command"
        const val STATE: String = "state"
        const val STATE_LAST: String = "state_last"
        private const val STORE_SUFFIX: String = "_store"
        private const val LOCAL_SUFFIX: String = "_local"
        private const val QUEUE_SUFFIX: String = "_queue"
        private const val CONSUMER_SUFFIX: String = "_consumer"
        private const val EVENT_SUFFIX: String = "_event"

        fun store(table: String): String = "$table$STORE_SUFFIX"

        fun localStore(store: String): String = "$store$LOCAL_SUFFIX"

        fun isLocalStore(name: String): Boolean = name.endsWith(LOCAL_SUFFIX)

        fun logicalStore(physical: String): String = physical.removeSuffix(LOCAL_SUFFIX)

        fun consumer(table: String): String = "$table$CONSUMER_SUFFIX"

        fun event(stateTable: String): String = "$stateTable$EVENT_SUFFIX"

        /** The stream a queue feeds, recovered from its name; `null` when the name is not a BI queue. */
        fun streamOfQueue(queue: String): BiQueueStream? = listOf(COMMAND, STATE).firstNotNullOfOrNull { suffix ->
            val streamSuffix = "_$suffix$QUEUE_SUFFIX"
            if (queue.endsWith(streamSuffix)) {
                BiQueueStream(suffix = suffix, consumer = consumer(queue.removeSuffix(QUEUE_SUFFIX)))
            } else {
                null
            }
        }
    }
}

/** The objects one aggregate contributes. */
internal data class BiAggregateLayout(
    val command: BiStreamLayout,
    val state: BiStreamLayout,
    /** The latest-state view; its rows live in [stateLastStore], fed from the state store by [stateLastConsumer]. */
    val stateLast: String,
) {
    val stateEvent: String = BiLayout.event(state.table)
    val stateLastStore: String = BiLayout.store(stateLast)
    val stateLastConsumer: String = BiLayout.consumer(stateLast)
}

/** A Kafka stream: its topic, the queue reading it, the consumer writing the store, and the public view. */
internal data class BiStreamLayout(
    val table: String,
    val store: String,
    val queue: String,
    val consumer: String,
    val topic: String,
    val schema: BiStoreSchema,
)

internal data class BiQueueStream(val suffix: String, val consumer: String)

internal object BiEngine {
    const val VIEW: String = "View"
    const val MATERIALIZED_VIEW: String = "MaterializedView"
    const val KAFKA: String = "Kafka"
    const val REPLACING_MERGE_TREE: String = "ReplacingMergeTree"
    const val REPLICATED_REPLACING_MERGE_TREE: String = "ReplicatedReplacingMergeTree"
    const val DISTRIBUTED: String = "Distributed"

    val STORE_ENGINES: Set<String> = setOf(REPLACING_MERGE_TREE, REPLICATED_REPLACING_MERGE_TREE, DISTRIBUTED)
}

internal data class BiStoreColumn(val name: String, val type: String)

/**
 * The shape of each store, rendered as DDL by the renderers and compared with the catalog by the store validator.
 */
internal enum class BiStoreSchema(
    private val columns: (dateTime: String) -> List<BiStoreColumn>,
    val partitionColumn: String,
    val orderBy: List<String>,
    val versionColumn: String?,
    val shardingColumns: List<String>,
) {
    COMMAND(
        columns = ::commandColumns,
        partitionColumn = "create_time",
        orderBy = listOf("id"),
        versionColumn = null,
        shardingColumns = listOf("aggregate_id"),
    ),
    STATE(
        columns = ::stateColumns,
        partitionColumn = "create_time",
        orderBy = listOf("tenant_id", "aggregate_id", "version"),
        versionColumn = "version",
        shardingColumns = listOf("tenant_id", "aggregate_id"),
    ),
    STATE_LAST(
        columns = ::stateColumns,
        partitionColumn = "first_event_time",
        orderBy = listOf("tenant_id", "aggregate_id"),
        versionColumn = "version",
        shardingColumns = listOf("tenant_id", "aggregate_id"),
    ),
    ;

    /** [dateTime] is the rendered `DateTime64(3, '<timezone>')` type. */
    fun columns(dateTime: String): List<BiStoreColumn> = columns.invoke(dateTime)

    /** The partition key as the catalog reports it. */
    val catalogPartitionKey: String
        get() = "toYYYYMM($partitionColumn)"

    /** The sorting key as the catalog reports it. */
    val catalogSortingKey: String
        get() = orderBy.joinToString(", ")

    /** The Distributed sharding key as the catalog reports it. */
    val catalogShardingKey: String
        get() = "sipHash64(${shardingColumns.joinToString(", ")})"

    companion object {
        /** The schema of a store, recovered from its logical name; `null` when the name is not a BI store. */
        fun ofStore(store: String): BiStoreSchema? = when {
            store.endsWith("_${BiLayout.STATE_LAST}_store") -> STATE_LAST
            store.endsWith("_${BiLayout.STATE}_store") -> STATE
            store.endsWith("_${BiLayout.COMMAND}_store") -> COMMAND
            else -> null
        }
    }
}

private fun commandColumns(dateTime: String): List<BiStoreColumn> = listOf(
    BiStoreColumn("id", "String"),
    BiStoreColumn("context_name", "String"),
    BiStoreColumn("aggregate_name", "String"),
    BiStoreColumn("name", "String"),
    BiStoreColumn("header", "Map(String, String)"),
    BiStoreColumn("aggregate_id", "String"),
    BiStoreColumn("tenant_id", "String"),
    BiStoreColumn("owner_id", "String"),
    BiStoreColumn("space_id", "String"),
    BiStoreColumn("request_id", "String"),
    BiStoreColumn("aggregate_version", "Nullable(UInt32)"),
    BiStoreColumn("is_create", "Bool"),
    BiStoreColumn("is_void", "Bool"),
    BiStoreColumn("allow_create", "Bool"),
    BiStoreColumn("body_type", "String"),
    BiStoreColumn("body", "String"),
    BiStoreColumn("create_time", dateTime),
)

private fun stateColumns(dateTime: String): List<BiStoreColumn> = listOf(
    BiStoreColumn("id", "String"),
    BiStoreColumn("context_name", "String"),
    BiStoreColumn("aggregate_name", "String"),
    BiStoreColumn("header", "Map(String, String)"),
    BiStoreColumn("aggregate_id", "String"),
    BiStoreColumn("tenant_id", "String"),
    BiStoreColumn("owner_id", "String"),
    BiStoreColumn("space_id", "String"),
    BiStoreColumn("command_id", "String"),
    BiStoreColumn("request_id", "String"),
    BiStoreColumn("version", "UInt32"),
    BiStoreColumn("state", "String"),
    BiStoreColumn("body", "Array(String)"),
    BiStoreColumn("first_operator", "String"),
    BiStoreColumn("first_event_time", dateTime),
    BiStoreColumn("create_time", dateTime),
    BiStoreColumn("tags", "Map(String, Array(String))"),
    BiStoreColumn("deleted", "Bool"),
)
