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
import co.elastic.clients.elasticsearch._types.HealthStatus
import co.elastic.clients.elasticsearch._types.mapping.TypeMapping
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest
import co.elastic.clients.elasticsearch.indices.GetMappingRequest
import me.ahoo.wow.spi.StoreDdl
import java.io.StringReader

/**
 * The indices Wow keeps on Elasticsearch, each created with its definition (settings and mappings, a JSON document)
 * and recording its layout in the mapping's `_meta` (`wow_index`, `wow_format`, decision D59), so a reader of another
 * layout refuses it instead of misreading it.
 */
internal class ElasticsearchIndices(private val client: ElasticsearchAsyncClient) {
    /**
     * Makes sure of [index], of [kind] in [format], as [ddl] says: under `auto` creates it from [definition] when it is
     * missing, then (under `auto` and `validate`) checks the layout an existing one records; under `validate` a missing
     * index refuses the store; under `none` nothing is checked.
     */
    suspend fun ensure(index: String, kind: String, format: Int, definition: String, ddl: StoreDdl) {
        if (ddl == StoreDdl.NONE) return
        val mapping = mapping(index)
        if (mapping == null) {
            check(ddl == StoreDdl.AUTO) { "The Elasticsearch index [$index] does not exist (ddl: validate)." }
            create(index, definition)
            return
        }
        checkLayout(index, mapping, kind, format)
    }

    /** The mapping of [index], or null when it does not exist. */
    suspend fun mapping(index: String): TypeMapping? = try {
        client.indices().getMapping(GetMappingRequest.of { it.index(index) }).awaitElasticsearch()
            .mappings().values.firstOrNull()?.mappings()
    } catch (failure: Throwable) {
        if (ElasticsearchFailures.isIndexNotFound(failure)) null else throw failure
    }

    private suspend fun create(index: String, definition: String) {
        try {
            client.indices().create(
                CreateIndexRequest.of { it.index(index).withJson(StringReader(definition)) },
            ).awaitElasticsearch()
        } catch (failure: Throwable) {
            if (!failure.isAlreadyExists()) throw failure
            // Another node, or another call of this one, created it meanwhile: wait until its shard serves requests,
            // as the creation itself waits.
            client.cluster().health { it.index(index).waitForStatus(HealthStatus.Yellow) }.awaitElasticsearch()
        }
    }

    private fun Throwable.isAlreadyExists(): Boolean =
        this is co.elastic.clients.elasticsearch._types.ElasticsearchException &&
            error().type() == RESOURCE_ALREADY_EXISTS

    companion object {
        const val META_INDEX = "wow_index"
        const val META_FORMAT = "wow_format"
        private const val RESOURCE_ALREADY_EXISTS = "resource_already_exists_exception"

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
