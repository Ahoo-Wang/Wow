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

package me.ahoo.wow.webflux.bi

import me.ahoo.wow.bi.BiDeploymentInspection
import me.ahoo.wow.bi.BiDeploymentInspector
import me.ahoo.wow.bi.BiObjectKind
import me.ahoo.wow.bi.BiObjectMetadataCodec
import me.ahoo.wow.bi.BiScriptOptions
import me.ahoo.wow.bi.ClickHouseTopology
import me.ahoo.wow.bi.ObservedBiDeployment
import me.ahoo.wow.bi.ObservedBiObject
import me.ahoo.wow.serialization.JsonSerializer
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono

class ClickHouseBiDeploymentInspector(private val webClient: WebClient) : BiDeploymentInspector {
    override fun inspect(options: BiScriptOptions): Mono<BiDeploymentInspection> {
        val cluster = options.topology as? ClickHouseTopology.Cluster
        val sql = if (cluster == null) STANDALONE_QUERY else CLUSTER_QUERY
        return webClient.post()
            .uri { builder ->
                val variables = mutableMapOf<String, Any>(
                    "database" to options.database,
                    "consumerDatabase" to options.consumerDatabase,
                )
                builder
                    .queryParam("wait_end_of_query", "1")
                    .queryParam("param_database", "{database}")
                    .queryParam("param_consumerDatabase", "{consumerDatabase}")
                    .apply {
                        cluster?.let {
                            queryParam("param_cluster", "{cluster}")
                            variables["cluster"] = it.name
                        }
                    }
                    .build(variables)
            }
            .contentType(MediaType.TEXT_PLAIN)
            .accept(NDJSON_MEDIA_TYPE)
            .bodyValue(sql)
            .retrieve()
            .bodyToMono(String::class.java)
            .defaultIfEmpty("")
            .map(::parseCatalog)
            .map(BiDeploymentInspection::Available)
    }

    private fun parseCatalog(body: String): ObservedBiDeployment {
        val objects = body.lineSequence()
            .filter(String::isNotBlank)
            .map(::parseObject)
            .toList()
        val consistentObjects = objects.groupBy(ObservedBiObject::key).map { (key, replicas) ->
            val definitions = replicas.map { observed ->
                CatalogDefinition(
                    engine = observed.engine,
                    engineFull = observed.engineFull,
                    createTableQuery = observed.createTableQuery,
                    metadata = observed.metadata,
                )
            }.distinct()
            require(definitions.size == 1) {
                "ClickHouse BI catalog object [${key.database}.${key.name}] differs across replicas"
            }
            replicas.first()
        }.sortedWith(compareBy<ObservedBiObject> { it.database }.thenBy { it.name })
        consistentObjects.filter { it.metadata?.kind == BiObjectKind.QUEUE }.forEach(::validateQueueIdentity)
        return ObservedBiDeployment(consistentObjects)
    }

    private fun parseObject(line: String): ObservedBiObject {
        val node = JsonSerializer.readTree(line)
        val comment = node.path("comment").asText("")
        return ObservedBiObject(
            database = node.path("database").asText(),
            name = node.path("name").asText(),
            engine = node.path("engine").asText(),
            engineFull = node.path("engine_full").asText(""),
            createTableQuery = node.path("create_table_query").asText(""),
            metadata = BiObjectMetadataCodec.decode(comment),
        )
    }

    private fun validateQueueIdentity(queue: ObservedBiObject) {
        require(queue.engine == "Kafka") {
            "Owned BI queue [${queue.database}.${queue.name}] must use the Kafka engine"
        }
        val identity = requireNotNull(queue.metadata?.consumerIdentity) {
            "Owned BI queue [${queue.database}.${queue.name}] is missing consumerIdentity"
        }
        val consumerName = queue.name.removeSuffix("_queue") + "_consumer"
        val expectedGroup = "wow-bi.$identity.$consumerName"
        require(queue.engineFull.contains("'$expectedGroup'")) {
            "Owned BI queue [${queue.database}.${queue.name}] has an unexpected Kafka consumer group"
        }
    }

    private data class CatalogDefinition(
        val engine: String,
        val engineFull: String,
        val createTableQuery: String,
        val metadata: me.ahoo.wow.bi.BiObjectMetadata?,
    )

    private companion object {
        val NDJSON_MEDIA_TYPE: MediaType = MediaType.parseMediaType("application/x-ndjson")

        val STANDALONE_QUERY: String = """
            SELECT database, name, engine, engine_full, create_table_query, comment
            FROM system.tables
            WHERE database IN ({database:String}, {consumerDatabase:String})
            FORMAT JSONEachRow
        """.trimIndent()

        val CLUSTER_QUERY: String = """
            SELECT database, name, engine, engine_full, create_table_query, comment
            FROM clusterAllReplicas({cluster:String}, system.tables)
            WHERE database IN ({database:String}, {consumerDatabase:String})
            SETTINGS skip_unavailable_shards = 0
            FORMAT JSONEachRow
        """.trimIndent()
    }
}
