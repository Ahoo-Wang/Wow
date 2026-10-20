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

import co.elastic.clients.elasticsearch._types.mapping.TypeMapping
import java.io.StringReader

/**
 * The definition of an aggregate type's snapshot index (decision D161), format [FORMAT].
 *
 * - The system fields are mapped explicitly: the ids, tenant, owner and space as `keyword`, the version and the state
 *   revision as `integer`, the event times as `long`, the operators as objects of `keyword` and `boolean`.
 * - The tags and the schedules are dynamic objects: each tag a `keyword` array, each rule's `epochMillis` and `millis`
 *   a `long`, its other parts `keyword`.
 * - The state is **not** mapped dynamically (`dynamic: false`): a state field is indexed only once the index maps it,
 *   and the query side maps the fields of the aggregate's state schema (`ElasticsearchStateMappings`). An untyped
 *   state (an array mixing numbers and objects, say) is kept in `_source` all the same, so a save never fails on the
 *   state's shape, and a value of another type than its field's is kept unindexed (`index.mapping.ignore_malformed`).
 * - The schema fingerprint is kept, not indexed.
 */
internal object ElasticsearchSnapshotIndex {
    /** [MAPPINGS] as the client's mapping. */
    fun typeMapping(): TypeMapping = TypeMapping.of { it.withJson(StringReader(MAPPINGS)) }

    /** The kind and the version of this layout (decision D59), recorded in the mapping's `_meta`. */
    const val KIND = "snapshot"
    const val FORMAT = 1

    /** The longest keyword Elasticsearch indexes: Lucene's 32766-byte term limit at four UTF-8 bytes a character. */
    const val KEYWORD_LIMIT = 8191

    /** The mappings of the index (without its settings): what a missing index's queries are admitted against. */
    val MAPPINGS: String = """
          {
            "_meta": { "wow_index": "$KIND", "wow_format": $FORMAT },
            "dynamic": false,
            "date_detection": false,
            "numeric_detection": false,
            "dynamic_templates": [
              { "tags_as_keyword": { "path_match": "tags.*", "mapping": { "type": "keyword", "ignore_above": $KEYWORD_LIMIT } } },
              { "schedule_epoch_millis": { "path_match": "schedules.*.epochMillis", "mapping": { "type": "long" } } },
              { "schedule_millis": { "path_match": "schedules.*.millis", "mapping": { "type": "long" } } },
              { "schedule_strings": { "path_match": "schedules.*", "match_mapping_type": "string", "mapping": { "type": "keyword", "ignore_above": $KEYWORD_LIMIT } } }
            ],
            "properties": {
              "tenantId": { "type": "keyword" },
              "aggregateId": { "type": "keyword" },
              "version": { "type": "integer" },
              "ownerId": { "type": "keyword" },
              "spaceId": { "type": "keyword" },
              "deleted": { "type": "boolean" },
              "tags": { "type": "object", "dynamic": true },
              "firstEventTime": { "type": "long" },
              "lastEventTime": { "type": "long" },
              "operator": {
                "properties": {
                  "id": { "type": "keyword" },
                  "tenantId": { "type": "keyword" },
                  "crossScope": { "type": "boolean" }
                }
              },
              "firstOperator": {
                "properties": {
                  "id": { "type": "keyword" },
                  "tenantId": { "type": "keyword" },
                  "crossScope": { "type": "boolean" }
                }
              },
              "schedules": { "type": "object", "dynamic": true },
              "state": { "type": "object", "dynamic": false },
              "schemaFingerprint": { "type": "keyword", "index": false, "doc_values": false },
              "stateRevision": { "type": "integer" },
              "staleVersion": { "type": "integer" }
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
