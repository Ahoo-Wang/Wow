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
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest
import co.elastic.clients.elasticsearch.indices.CreateIndexResponse
import co.elastic.clients.elasticsearch.indices.ExistsRequest
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.configuration.MetadataSearcher
import me.ahoo.wow.configuration.WowResource
import me.ahoo.wow.configuration.WowResourceLocator
import me.ahoo.wow.elasticsearch.IndexNameConverter.toSnapshotIndexName
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

class ElasticsearchSnapshotIndexInitializer(
    private val elasticsearchClient: ReactiveElasticsearchClient,
    private val resourceLocator: WowResourceLocator = WowResourceLocator(),
    private val namedAggregates: Iterable<NamedAggregate> = MetadataSearcher.namedAggregateType.keys,
) {
    fun ensureAll(): Mono<Void> = Flux.defer {
        Flux.fromIterable(namedAggregates.sortedBy { it.toSnapshotIndexName() })
    }.concatMap(::ensureIndex).then()

    private fun ensureIndex(namedAggregate: NamedAggregate): Mono<Void> = Mono.defer {
        val indexName = namedAggregate.toSnapshotIndexName()
        val resource = findResource(indexName) ?: return@defer Mono.empty()
        val request = parseRequest(indexName, resource)
        elasticsearchClient.indices().exists(ExistsRequest.Builder().index(indexName).build())
            .switchIfEmpty(Mono.error(failure(indexName, resource, null)))
            .flatMap { exists ->
                if (exists.value()) {
                    Mono.empty()
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
            "Elasticsearch snapshot index [$indexName] has ${resources.size} classpath resources: " +
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

    private fun Throwable.isResourceAlreadyExists(): Boolean =
        this is ElasticsearchException && error().type() == RESOURCE_ALREADY_EXISTS

    private fun failure(indexName: String, resource: WowResource, cause: Throwable?): IndexInitializationException =
        IndexInitializationException(
            "Unable to initialize Elasticsearch snapshot index [$indexName] from [${resource.location}].",
            cause,
        )

    private class IndexInitializationException(message: String, cause: Throwable?) :
        IllegalStateException(message, cause)

    companion object {
        private const val FEATURE = "elasticsearch"
        private const val RESOURCE_ALREADY_EXISTS = "resource_already_exists_exception"
    }
}
