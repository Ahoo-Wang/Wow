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
import kotlinx.coroutines.delay
import me.ahoo.wow.spi.Ddl
import me.ahoo.wow.spi.StorageUnavailableException
import java.io.StringReader
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * The indices Wow keeps on Elasticsearch, each created with its definition (settings and mappings, a JSON document)
 * and recording its layout in the mapping's `_meta` (`wow_index`, `wow_format`), so a reader of another
 * layout refuses it instead of misreading it.
 *
 * Each is a pair: a concrete index `<name>--1` ([concreteOf]) behind the alias `<name>`, its write index. The stores
 * read and write by the name, and every write carries `require_alias`, so a write to a name that has no alias is
 * refused (`404`) instead of having Elasticsearch create an index of that name with a mapping it guesses, whatever
 * made it missing: never created under `none` or `validate`, or deleted under a running node. The `--1` is room for a
 * later version to reindex into another index behind the same alias; nothing in 10.0 moves it.
 */
internal class ElasticsearchIndices(private val client: ElasticsearchAsyncClient) {
    /**
     * Makes sure of the alias [index], of [kind] in [format], as [ddl] says:
     * - under `auto`, a missing pair is created from [definition], the index and its alias in one request;
     * - under `auto` and `validate`, the index behind an existing alias must record the layout, and the alias must
     *   point at that one index as its write index;
     * - under `validate`, a missing alias refuses the store, naming the pair and how to create it;
     * - under `none`, nothing is checked, and a write to a missing alias is refused by Elasticsearch
     *   ([missingAlias]).
     *
     * A concrete index of the alias's name, which an earlier pre-release created, is refused under `auto` and
     * `validate` ([concreteIndex]).
     *
     * Either way it returns once the index's primary shard serves requests ([awaitActive]): an index just created,
     * here or by another node a moment ago, recovers its primary before it takes a read or a write, and a first write
     * that races the recovery is answered `503`.
     *
     * A missing alias that [removed] says was removed under its owner's record is neither created nor refused: the
     * answer is then false, true otherwise (`none` included, which checks nothing).
     */
    suspend fun ensure(
        index: String,
        kind: String,
        format: Int,
        definition: String,
        ddl: Ddl,
        removed: suspend () -> Boolean = { false },
    ): Boolean {
        if (!ddl.checks) return true
        var found = resolve(index)
        if (found == Found.Missing && removed()) return false
        if (found == Found.Missing && ddl.creates) {
            create(index, definition)
            found = resolve(index)
        }
        when (found) {
            Found.Missing -> error(missingAlias(index))
            is Found.Concrete -> error(concreteIndex(index))
            is Found.Unwritable -> error(
                "The Elasticsearch alias [$index] ${found.reason}: Wow writes only through an alias of one index, " +
                    "its write index.",
            )
            is Found.Alias -> {
                val mapping = checkNotNull(mapping(found.index)) { missingAlias(index) }
                checkLayout(index, mapping, kind, format)
            }
        }
        awaitActive(index)
        return true
    }

    /**
     * What the name [alias] is on the cluster, read in one request (`GET <alias>`, which answers the concrete indices a
     * name stands for, each with its aliases), so that another node creating the pair meanwhile cannot make an alias
     * read as an index of its own name.
     */
    private suspend fun resolve(alias: String): Found {
        val indices = try {
            client.indices().get { it.index(alias) }.awaitElasticsearch().indices()
        } catch (failure: ElasticsearchException) {
            if (ElasticsearchFailures.isIndexNotFound(failure)) return Found.Missing
            throw failure
        }
        if (alias in indices) return Found.Concrete
        val (index, state) = indices.entries.singleOrNull()
            ?: return Found.Unwritable("points at ${indices.size} indices, ${indices.keys.sorted()}")
        if (state.aliases()[alias]?.isWriteIndex() == false) {
            return Found.Unwritable("is not the write alias of [$index]")
        }
        return Found.Alias(index)
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
            // Keyed by the index behind the alias.
            val stats = health?.indices()?.values?.singleOrNull()
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

    /** Creates [alias]'s index from [definition], with [alias] as its write alias, in one request. */
    private suspend fun create(alias: String, definition: String) {
        try {
            client.indices().create(
                CreateIndexRequest.of {
                    it.withJson(StringReader(definition)).index(concreteOf(alias))
                        .aliases(alias) { pair -> pair.isWriteIndex(true) }
                },
            ).awaitElasticsearch()
        } catch (failure: ElasticsearchException) {
            // Another node, or another call of this one, created it meanwhile: [ensure] resolves what is there.
            if (!failure.isAlreadyExists()) throw failure
        }
    }

    private fun ElasticsearchException.isAlreadyExists(): Boolean =
        error().type() == RESOURCE_ALREADY_EXISTS || error().type() == INVALID_ALIAS_NAME

    /** What a name is: missing, an index of its own (an earlier pre-release's), an alias Wow cannot write through, or its pair. */
    private sealed interface Found {
        data object Missing : Found

        data object Concrete : Found

        class Unwritable(val reason: String) : Found

        class Alias(val index: String) : Found
    }

    companion object {
        const val META_INDEX = "wow_index"
        const val META_FORMAT = "wow_format"
        private const val RESOURCE_ALREADY_EXISTS = "resource_already_exists_exception"

        /** Elasticsearch's answer to an alias whose name an index already has. */
        private const val INVALID_ALIAS_NAME = "invalid_alias_name_exception"

        /** The suffix of the concrete index behind an alias: its first, which no version moves yet. */
        const val CONCRETE_SUFFIX = "--1"

        /** The concrete index behind the alias [alias]. */
        fun concreteOf(alias: String): String = alias + CONCRETE_SUFFIX

        /** Why a write to [alias] is refused when it does not exist, and how to create it. */
        fun missingAlias(alias: String): String =
            "The Elasticsearch alias [$alias] does not exist, and Wow writes only through it. Create it with " +
                "ddl auto once, from an account that may create indices, or by hand: the index " +
                "[${concreteOf(alias)}] with the definition ddl auto gives it and the alias as its write index " +
                "(`\"aliases\": {\"$alias\": {\"is_write_index\": true}}`)."

        /** Why [alias] is refused when it is a concrete index of its own, as an earlier pre-release created it. */
        fun concreteIndex(alias: String): String =
            "The Elasticsearch index [$alias] is an index, not the alias Wow writes through: an earlier pre-release " +
                "created it. It holds nothing the log does not. Delete it and rebuild it from the log (an events " +
                "index by its sink's subscription from the beginning, a snapshot index by a snapshot rebuild): ddl " +
                "auto then creates [${concreteOf(alias)}] behind the alias [$alias]."

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
