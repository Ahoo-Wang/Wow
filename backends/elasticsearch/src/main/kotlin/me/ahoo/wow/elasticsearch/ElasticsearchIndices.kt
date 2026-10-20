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
import co.elastic.clients.elasticsearch._types.ElasticsearchException
import co.elastic.clients.elasticsearch._types.HealthStatus
import co.elastic.clients.elasticsearch._types.Level
import co.elastic.clients.elasticsearch._types.mapping.TypeMapping
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest
import co.elastic.clients.elasticsearch.indices.GetMappingRequest
import co.elastic.clients.elasticsearch.indices.PutMappingRequest
import kotlinx.coroutines.delay
import me.ahoo.wow.spi.StorageUnavailableException
import me.ahoo.wow.spi.StoreDdl
import java.io.StringReader
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * The indices Wow keeps on Elasticsearch, each created with its definition (settings and mappings, a JSON document)
 * and recording its layout in the mapping's `_meta` (`wow_index`, `wow_format`, decision D59), so a reader of another
 * layout refuses it instead of misreading it.
 */
internal class ElasticsearchIndices(private val client: ElasticsearchAsyncClient) {
    /**
     * Makes sure of [index], of [kind] in [format], as [ddl] says: under `auto` creates it from [definition] when it is
     * missing, then (under `auto` and `validate`) checks the layout an existing one records; under `validate` a missing
     * index refuses the store; under `none` nothing is checked. Under `auto` an existing index that lacks one of the
     * fields its format [added] later gets them from [additions], a mapping of their properties (decision D59).
     *
     * Either way it returns once the index's primary shard serves requests ([awaitActive]): an index just created,
     * here or by another node a moment ago, recovers its primary before it takes a read or a write, and a first write
     * that races the recovery is answered `503`.
     */
    @Suppress("LongParameterList")
    suspend fun ensure(
        index: String,
        kind: String,
        format: Int,
        definition: String,
        ddl: StoreDdl,
        added: List<String> = emptyList(),
        additions: String? = null,
    ) {
        if (ddl == StoreDdl.NONE) return
        val mapping = mapping(index)
        if (mapping == null) {
            check(ddl == StoreDdl.AUTO) { "The Elasticsearch index [$index] does not exist (ddl: validate)." }
            create(index, definition)
        } else {
            checkLayout(index, mapping, kind, format)
            if (ddl == StoreDdl.AUTO && additions != null && !mapping.properties().keys.containsAll(added)) {
                client.indices().putMapping(
                    PutMappingRequest.of { it.index(index).withJson(StringReader(additions)) },
                ).awaitElasticsearch()
            }
        }
        awaitActive(index)
    }

    /**
     * Waits until every primary shard of [index] is active, at most [ACTIVE_TIMEOUT]; a cluster that does not get
     * there in time is unavailable for now.
     *
     * `wait_for_status=yellow` alone is not enough: Elasticsearch reports the primary of a new index, unassigned or
     * initializing from an empty store, as yellow rather than red (`ClusterShardHealth.getInactivePrimaryHealth`), so
     * the health answers before the primary serves a request (a real-time `GET` then fails with
     * `illegal_index_shard_state_exception`, `RECOVERING`). Each round waits server-side for no initializing shard,
     * then the index's active primaries are compared with its shards, until they are all active.
     */
    suspend fun awaitActive(index: String) {
        val start = TimeSource.Monotonic.markNow()
        var pause = ACTIVE_FIRST_PAUSE
        while (true) {
            val remaining = ACTIVE_TIMEOUT - start.elapsedNow()
            val health = if (remaining.isPositive()) {
                client.cluster().health {
                    it.index(index).level(Level.Indices).waitForStatus(HealthStatus.Yellow)
                        .waitForNoInitializingShards(true)
                        .timeout { time -> time.time("${remaining.inWholeMilliseconds.coerceAtLeast(1)}ms") }
                }.awaitElasticsearch()
            } else {
                null
            }
            val stats = health?.indices()?.get(index)
            if (stats != null && stats.numberOfShards() > 0 && stats.activePrimaryShards() == stats.numberOfShards()) {
                return
            }
            if (health == null || start.elapsedNow() + pause > ACTIVE_TIMEOUT) {
                throw StorageUnavailableException(
                    "The Elasticsearch index [$index] has no active primary shard yet; retry later."
                )
            }
            delay(pause)
            pause = minOf(pause * 2, ACTIVE_MAX_PAUSE)
        }
    }

    /** The mapping of [index], or null when it does not exist. */
    suspend fun mapping(index: String): TypeMapping? = try {
        client.indices().getMapping(GetMappingRequest.of { it.index(index) }).awaitElasticsearch()
            .mappings().values.firstOrNull()?.mappings()
    } catch (failure: ElasticsearchException) {
        if (ElasticsearchFailures.isIndexNotFound(failure)) null else throw failure
    }

    private suspend fun create(index: String, definition: String) {
        try {
            client.indices().create(
                CreateIndexRequest.of { it.index(index).withJson(StringReader(definition)) },
            ).awaitElasticsearch()
        } catch (failure: ElasticsearchException) {
            // Another node, or another call of this one, created it meanwhile: [ensure] waits for its primary.
            if (!failure.isAlreadyExists()) throw failure
        }
    }

    private fun ElasticsearchException.isAlreadyExists(): Boolean = error().type() == RESOURCE_ALREADY_EXISTS

    companion object {
        const val META_INDEX = "wow_index"
        const val META_FORMAT = "wow_format"
        private const val RESOURCE_ALREADY_EXISTS = "resource_already_exists_exception"

        /** How long [awaitActive] waits for the primary shards. */
        val ACTIVE_TIMEOUT: Duration = 30.seconds
        private val ACTIVE_FIRST_PAUSE: Duration = 50.milliseconds
        private val ACTIVE_MAX_PAUSE: Duration = 1.seconds

        /** Refuses [mapping] of [index] unless it records the layout of [kind] in [format]. */
        fun checkLayout(index: String, mapping: TypeMapping, kind: String, format: Int) {
            val meta = mapping.meta()
            val recordedKind = meta[META_INDEX]?.to(String::class.java)
            val recordedFormat = meta[META_FORMAT]?.to(Number::class.java)?.toInt()
            check(recordedKind == kind && recordedFormat == format) {
                "The Elasticsearch index [$index] records the layout $recordedKind/$recordedFormat; this version " +
                    "reads $kind/$format."
            }
        }
    }
}
