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


package me.ahoo.wow.elasticsearch.snapshot

import co.elastic.clients.elasticsearch.ElasticsearchAsyncClient
import co.elastic.clients.elasticsearch._types.ElasticsearchException
import me.ahoo.wow.api.wire.WireNames
import me.ahoo.wow.elasticsearch.ElasticsearchFailures
import me.ahoo.wow.elasticsearch.ElasticsearchNames
import me.ahoo.wow.elasticsearch.awaitElasticsearch
import me.ahoo.wow.spi.snapshot.SnapshotFill
import me.ahoo.wow.spi.snapshot.SnapshotTable
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode

/**
 * The records of an [ElasticsearchSnapshotStore]'s tables, one document each in the snapshot table index
 * ([me.ahoo.wow.elasticsearch.ElasticsearchNames.snapshotTables]), keyed by the snapshot index: how a [SnapshotTable] is
 * written and read, and the index's mapping.
 */
internal object ElasticsearchSnapshotTables {
    const val TABLE_KIND = "snapshot_table"
    const val TABLE_FORMAT = 1
    private const val REVISION = "revision"
    private const val REBUILDING = "rebuilding"
    private const val BACKFILLED = "backfilled"
    private const val FILL = "fill"
    private const val REMOVED = "removed"

    val TABLE_DEFINITION: String = """
        {
          "mappings": {
            "_meta": { "wow_index": "$TABLE_KIND", "wow_format": $TABLE_FORMAT },
            "dynamic": false,
            "properties": {
              "$REVISION": { "type": "integer" },
              "$REBUILDING": { "type": "boolean" },
              "$BACKFILLED": { "type": "boolean" },
              "$FILL": { "type": "keyword" },
              "$REMOVED": { "type": "boolean" }
            }
          }
        }
    """.trimIndent()

    /** Whether [aggregate]'s table in [context] has a record: an index of it missing then was removed under it. */
    suspend fun recorded(
        client: ElasticsearchAsyncClient,
        names: ElasticsearchNames,
        context: String,
        aggregate: String,
    ): Boolean = try {
        client.exists { it.index(names.snapshotTables).id(names.snapshots(context, aggregate)) }
            .awaitElasticsearch().value()
    } catch (failure: ElasticsearchException) {
        if (ElasticsearchFailures.isIndexNotFound(failure)) false else throw failure
    }

    fun tableOf(source: ObjectNode) = SnapshotTable(
        revision = source.path(REVISION).intValue(),
        rebuilding = source.path(REBUILDING).booleanValue(),
        backfilled = source.path(BACKFILLED).booleanValue(),
        fill = checkNotNull(WireNames.entryOf<SnapshotFill>(source.path(FILL).textValue())) {
            // By its wire name; no case is added within 10.x (SnapshotFill), so one this version does not know is
            // refused, naming it.
            "A snapshot table's fill ${source.path(FILL).textValue()} is not one this version of Wow reads."
        },
        removed = source.path(REMOVED).asBoolean(false),
    )

    fun document(table: SnapshotTable): ObjectNode = JsonNodeFactory.instance.objectNode()
        .put(REVISION, table.revision)
        .put(REBUILDING, table.rebuilding)
        .put(BACKFILLED, table.backfilled)
        .put(FILL, WireNames.of(table.fill))
        .put(REMOVED, table.removed)
}
