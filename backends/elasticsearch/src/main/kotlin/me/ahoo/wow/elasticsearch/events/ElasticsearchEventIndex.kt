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


package me.ahoo.wow.elasticsearch.events

import co.elastic.clients.elasticsearch._types.mapping.TypeMapping
import me.ahoo.wow.elasticsearch.snapshot.ElasticsearchSnapshotIndex
import java.io.StringReader

/**
 * The definition of an aggregate type's events index (decisions D13, D235), format [FORMAT]: one document per event
 * stream, the commit of one command at one version, written by the event search Sink ([ElasticsearchEventSink]).
 *
 * - The stream's system fields are mapped explicitly under the `events` model's names: the ids, tenant, command type,
 *   owner and space as `keyword`, the version as `integer`, the commit time as `long`, the operator as an object of
 *   `keyword` and `boolean`.
 * - `events` is `nested`, each event `{name: keyword, revision: integer, body}`, so a predicate matches one event and
 *   an aggregation expands them (`expand("events")`).
 * - The bodies are **not** mapped dynamically (`dynamic: false`): a body field is indexed only once the index maps it,
 *   and the query side maps the fields of the events' schemas (`ElasticsearchPayloadMappings`), as a snapshot index's
 *   state. A body of any shape is kept in `_source` all the same, so a write never fails on it, and a value of another
 *   type than its field's is kept unindexed (`index.mapping.ignore_malformed`).
 */
internal object ElasticsearchEventIndex {
    /** [MAPPINGS] as the client's mapping. */
    fun typeMapping(): TypeMapping = TypeMapping.of { it.withJson(StringReader(MAPPINGS)) }

    /** The kind and the version of this layout (decision D59), recorded in the mapping's `_meta`. */
    const val KIND = "events"
    const val FORMAT = 1

    /** The mappings of the index (without its settings): what a missing index's queries are admitted against. */
    val MAPPINGS: String = """
          {
            "_meta": { "wow_index": "$KIND", "wow_format": $FORMAT },
            "dynamic": false,
            "date_detection": false,
            "numeric_detection": false,
            "properties": {
              "aggregateId": { "type": "keyword" },
              "tenantId": { "type": "keyword" },
              "version": { "type": "integer" },
              "commitTime": { "type": "long" },
              "commandId": { "type": "keyword" },
              "requestId": { "type": "keyword" },
              "commandType": { "type": "keyword", "ignore_above": ${ElasticsearchSnapshotIndex.KEYWORD_LIMIT} },
              "ownerId": { "type": "keyword" },
              "spaceId": { "type": "keyword" },
              "operator": {
                "properties": {
                  "id": { "type": "keyword" },
                  "tenantId": { "type": "keyword" },
                  "crossScope": { "type": "boolean" }
                }
              },
              "events": {
                "type": "nested",
                "properties": {
                  "name": { "type": "keyword", "ignore_above": ${ElasticsearchSnapshotIndex.KEYWORD_LIMIT} },
                  "revision": { "type": "integer" },
                  "body": { "type": "object", "dynamic": false }
                }
              }
            }
          }
    """.trimIndent()

    val DEFINITION: String = """
        {
          "settings": {
            "index.mapping.ignore_malformed": true
          },
          "mappings": $MAPPINGS
        }
    """.trimIndent()
}
