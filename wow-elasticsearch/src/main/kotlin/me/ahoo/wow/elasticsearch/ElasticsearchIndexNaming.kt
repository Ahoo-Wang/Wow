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

import me.ahoo.wow.api.Wow
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.elasticsearch.IndexNameConverter.toEventStreamIndexName
import me.ahoo.wow.elasticsearch.IndexNameConverter.toSnapshotIndexName
import java.util.Locale

/**
 * The names Wow gives its Elasticsearch indices and index templates on one cluster.
 *
 * [prefix] is put verbatim before every name Wow derives: the snapshot and event stream indices
 * (`{prefix}wow.{context}.{aggregate}.snapshot|es`), the index templates (`{prefix}wow-snapshot-template`, …) and their
 * `index_patterns` (`{prefix}wow.*.snapshot`, …), so deployments with different prefixes share a cluster without
 * reading or writing each other's indices. Empty (the default), every name is the one [IndexNameConverter] gives.
 *
 * An index definition shipped under `META-INF/wow/elasticsearch/` keeps its unprefixed file name: it describes the
 * aggregate's index whatever the deployment's prefix.
 */
class ElasticsearchIndexNaming(val prefix: String = "") {
    init {
        validatePrefix(prefix)
    }

    /** The snapshot index of [namedAggregate]. */
    fun snapshotIndexName(namedAggregate: NamedAggregate): String = resolve(namedAggregate.toSnapshotIndexName())

    /** The event stream index of [namedAggregate]. */
    fun eventStreamIndexName(namedAggregate: NamedAggregate): String =
        resolve(namedAggregate.toEventStreamIndexName())

    /** The name on the cluster of the index, template or index pattern Wow names [name] by default. */
    fun resolve(name: String): String = prefix + name

    override fun toString(): String = "ElasticsearchIndexNaming(prefix='$prefix')"

    companion object {
        /** Wow's names as they are without a prefix. */
        @JvmField
        val DEFAULT = ElasticsearchIndexNaming()

        private const val INVALID_CHARACTERS = "\\/*?\"<>|,#: "
        private const val INVALID_LEADING_CHARACTERS = "-_+."

        /**
         * Fails unless [prefix] can start an Elasticsearch index name: lowercase, none of `\ / * ? " < > | , # :` or
         * whitespace, and not starting with `-`, `_`, `+` or `.`. A prefix may not start with `wow.` either: the index
         * patterns would overlap Wow's unprefixed templates at the same priority, which Elasticsearch refuses.
         */
        fun validatePrefix(prefix: String) {
            if (prefix.isEmpty()) {
                return
            }
            require(prefix == prefix.lowercase(Locale.ROOT)) {
                "Elasticsearch index prefix [$prefix] must be lowercase."
            }
            require(prefix.none { it in INVALID_CHARACTERS || it.isWhitespace() }) {
                "Elasticsearch index prefix [$prefix] must not contain whitespace or any of [$INVALID_CHARACTERS]."
            }
            require(prefix.first() !in INVALID_LEADING_CHARACTERS) {
                "Elasticsearch index prefix [$prefix] must not start with any of [$INVALID_LEADING_CHARACTERS]."
            }
            require(!prefix.startsWith(Wow.WOW_PREFIX)) {
                "Elasticsearch index prefix [$prefix] must not start with [${Wow.WOW_PREFIX}]: its index templates " +
                    "would overlap Wow's unprefixed ones."
            }
        }
    }
}
