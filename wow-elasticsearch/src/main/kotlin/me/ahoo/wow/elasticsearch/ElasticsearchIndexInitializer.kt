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

import co.elastic.clients.elasticsearch._types.ElasticsearchException
import co.elastic.clients.elasticsearch._types.mapping.Property
import co.elastic.clients.elasticsearch._types.mapping.PropertyBase
import co.elastic.clients.elasticsearch._types.mapping.TypeMapping
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest
import co.elastic.clients.elasticsearch.indices.CreateIndexResponse
import co.elastic.clients.elasticsearch.indices.ExistsRequest
import co.elastic.clients.elasticsearch.indices.GetMappingRequest
import co.elastic.clients.json.JsonpUtils
import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.configuration.WowResource
import me.ahoo.wow.configuration.WowResourceLocator
import me.ahoo.wow.serialization.toObjectNode
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

/**
 * Creates the concrete Elasticsearch indices whose definitions an application ships: for each aggregate,
 * `META-INF/wow/elasticsearch/{indexName}.json` on the classpath or `config/wow/elasticsearch/{indexName}.json` in the
 * working directory, a native create-index request (settings, mappings, aliases). An index that does not exist yet is
 * created from it; the index templates matching its name still apply beneath it, the definition winning where both map
 * a field. The definition is found under the index's unprefixed name and creates the index [indexNaming] names.
 *
 * An existing index keeps its mapping: Wow never changes it. When the mapping differs from its definition at a path the
 * definition maps (an index created from the templates alone, before the definition shipped), startup logs a warning
 * naming those paths, since queries on them are refused or answer from a mapping the application did not intend.
 */
abstract class ElasticsearchIndexInitializer(
    private val elasticsearchClient: ReactiveElasticsearchClient,
    private val resourceLocator: WowResourceLocator,
    private val namedAggregates: Iterable<NamedAggregate>,
    private val indexNaming: ElasticsearchIndexNaming,
) {
    /** The constructor from before the index prefix, kept for binary compatibility: Wow's unprefixed names. */
    constructor(
        elasticsearchClient: ReactiveElasticsearchClient,
        resourceLocator: WowResourceLocator,
        namedAggregates: Iterable<NamedAggregate>,
    ) : this(elasticsearchClient, resourceLocator, namedAggregates, ElasticsearchIndexNaming.DEFAULT)

    /** What the indices hold, for messages: `snapshot` or `event stream`. */
    protected abstract val indexKind: String

    /**
     * The unprefixed name of the index of [namedAggregate] this initializer creates: the name its definition is found
     * under. The index created is that name as [ElasticsearchIndexNaming.resolve] gives it.
     */
    protected abstract fun indexName(namedAggregate: NamedAggregate): String

    fun ensureAll(): Mono<Void> = Flux.defer {
        Flux.fromIterable(namedAggregates.map(::indexName).sorted())
    }.concatMap(::ensureIndex).then()

    private fun ensureIndex(definitionName: String): Mono<Void> = Mono.defer {
        val resource = findResource(definitionName) ?: return@defer Mono.empty()
        val indexName = indexNaming.resolve(definitionName)
        val request = parseRequest(indexName, resource)
        elasticsearchClient.indices().exists(ExistsRequest.Builder().index(indexName).build())
            .switchIfEmpty(Mono.error(failure(indexName, resource, null)))
            .flatMap { exists ->
                if (exists.value()) {
                    warnOnDrift(request, resource)
                } else {
                    create(request, resource)
                }
            }.onErrorMap { error ->
                if (error is IndexInitializationException) {
                    error
                } else {
                    failure(indexName, resource, error)
                }
            }
    }

    private fun findResource(indexName: String): WowResource? {
        resourceLocator.findWorkingDirectory(FEATURE, indexName)?.let { return it }
        val resources = resourceLocator.findClasspath(FEATURE, indexName)
        check(resources.size <= 1) {
            "Elasticsearch $indexKind index [$indexName] has ${resources.size} classpath resources: " +
                resources.joinToString { it.location }
        }
        return resources.singleOrNull()
    }

    @Suppress("TooGenericExceptionCaught")
    private fun parseRequest(indexName: String, resource: WowResource): CreateIndexRequest = try {
        resource.readText().byteInputStream().use { input ->
            CreateIndexRequest.Builder().withJson(input).index(indexName).build()
        }
    } catch (error: Exception) {
        throw failure(indexName, resource, error)
    }

    private fun create(request: CreateIndexRequest, resource: WowResource): Mono<Void> {
        return elasticsearchClient.indices().create(request)
            .switchIfEmpty(Mono.error<CreateIndexResponse>(failure(request.index(), resource, null)))
            .flatMap { response ->
                if (response.acknowledged()) {
                    Mono.empty<Void>()
                } else {
                    Mono.error<Void>(failure(request.index(), resource, null))
                }
            }.onErrorResume { error ->
                if (error.isResourceAlreadyExists()) {
                    Mono.empty<Void>()
                } else {
                    val failure = if (error is IndexInitializationException) {
                        error
                    } else {
                        failure(request.index(), resource, error)
                    }
                    Mono.error<Void>(
                        failure,
                    )
                }
            }
    }

    /**
     * Logs the paths at which the existing index's mapping differs from [request]'s. Reading the mapping is advisory:
     * a failure is logged and startup goes on, as it did before the check.
     */
    private fun warnOnDrift(request: CreateIndexRequest, resource: WowResource): Mono<Void> {
        val expected = request.mappings() ?: return Mono.empty()
        val indexName = request.index()
        return Mono.defer {
            elasticsearchClient.indices().getMapping(GetMappingRequest.of { it.index(indexName) })
        }.doOnNext { response ->
            response.mappings().forEach { (physicalIndex, record) ->
                val drift = IndexMappingDrift.between(expected, record.mappings())
                if (drift.isNotEmpty()) {
                    log.warn {
                        "Elasticsearch $indexKind index [$physicalIndex] exists with a mapping that differs from " +
                            "[${resource.location}] at ${drift.describe()}. Wow does not change an existing index's " +
                            "mapping: queries on these paths are refused or read the existing mapping. Reindex into " +
                            "an index created from the definition, or delete the index while it is empty, before " +
                            "the application starts."
                    }
                }
            }
        }.onErrorResume { error ->
            log.warn(error) {
                "Unable to compare Elasticsearch $indexKind index [$indexName] with [${resource.location}]."
            }
            Mono.empty()
        }.then()
    }

    private fun Throwable.isResourceAlreadyExists(): Boolean =
        this is ElasticsearchException && error().type() == RESOURCE_ALREADY_EXISTS

    private fun failure(indexName: String, resource: WowResource, cause: Throwable?): IndexInitializationException =
        IndexInitializationException(
            "Unable to initialize Elasticsearch $indexKind index [$indexName] from [${resource.location}].",
            cause,
        )

    private class IndexInitializationException(message: String, cause: Throwable?) :
        IllegalStateException(message, cause)

    companion object {
        private val log = KotlinLogging.logger {}
        private const val FEATURE = "elasticsearch"
        private const val RESOURCE_ALREADY_EXISTS = "resource_already_exists_exception"
        private const val MAX_DRIFT_PATHS = 20

        private fun List<String>.describe(): String =
            take(MAX_DRIFT_PATHS).joinToString(prefix = "[", postfix = "]") +
                if (size > MAX_DRIFT_PATHS) " and ${size - MAX_DRIFT_PATHS} more" else ""
    }
}

/**
 * The paths a definition maps differently from an index: each field of the definition (object and nested fields,
 * their properties and multi-fields) whose own parameters are not those of the index's field at the same path, or that
 * the index does not map. Fields only the index maps (added by dynamic mapping or the templates) are not drift.
 * Only `properties` are compared: the mapping's root parameters (a root `dynamic`, `_source`, dynamic templates) and
 * the index settings are not.
 */
internal object IndexMappingDrift {
    private val CHILDREN = setOf("properties", "fields")

    fun between(expected: TypeMapping, actual: TypeMapping): List<String> {
        val actualFields = flatten(actual.properties())
        return flatten(expected.properties()).filter { (path, definition) -> actualFields[path] != definition }
            .keys.sorted()
    }

    private fun flatten(properties: Map<String, Property>, prefix: String = ""): Map<String, String> {
        val fields = linkedMapOf<String, String>()
        properties.forEach { (name, property) ->
            val path = prefix + name
            fields[path] = property.ownParameters()
            val children = when {
                property.isObject -> property.`object`().properties()
                property.isNested -> property.nested().properties()
                else -> emptyMap()
            }
            fields += flatten(children, "$path.")
            (property._get() as? PropertyBase)?.fields()?.let { fields += flatten(it, "$path.") }
        }
        return fields
    }

    /** The field's parameters without its sub-fields, as the client writes them (so both sides read alike). */
    private fun Property.ownParameters(): String {
        val node = JsonpUtils.toJsonString(this, WowJsonpMapper).toObjectNode()
        CHILDREN.forEach(node::remove)
        return node.toString()
    }
}
