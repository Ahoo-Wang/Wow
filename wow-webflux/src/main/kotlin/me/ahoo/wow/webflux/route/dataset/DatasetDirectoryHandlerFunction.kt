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

package me.ahoo.wow.webflux.route.dataset

import me.ahoo.wow.openapi.contract.HttpRouteContract
import me.ahoo.wow.openapi.contract.HttpRouteHandlerMetadata
import me.ahoo.wow.query.QueryEntry
import me.ahoo.wow.query.dataset.DatasetQueryGateway
import me.ahoo.wow.query.dataset.DatasetQueryGateways
import me.ahoo.wow.webflux.exception.RequestExceptionHandler
import me.ahoo.wow.webflux.route.HttpRouteHandlerFunctionFactory
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.server.HandlerFunction
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

/**
 * One dataset of the directory: its name, its title, and the version of its capability descriptor, `null` while its
 * schema cannot be compiled (its table does not match its declaration, say), which the dataset's own schema route
 * explains.
 */
data class DatasetDirectoryEntry(
    val contextName: String,
    val name: String,
    val title: String?,
    val version: String?,
)

/**
 * `GET /dataset`: the datasets this instance serves, by name, as of the request (dataset platform design §4.4). Who
 * may see the directory, and each dataset, is the gateway's path rules' to decide; the directory adds no access model.
 */
class DatasetDirectoryHandlerFunction(
    private val gateways: DatasetQueryGateways,
    private val exceptionHandler: RequestExceptionHandler,
) : HandlerFunction<ServerResponse> {
    override fun handle(request: ServerRequest): Mono<ServerResponse> =
        Flux.fromIterable(gateways.all.sortedBy { it.dataset.namedAggregate.toString() })
            .concatMap(::entry)
            .collectList()
            .flatMap { ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValue(it) }
            .onErrorResume { exceptionHandler.handle(request, it) }

    private fun entry(gateway: DatasetQueryGateway): Mono<DatasetDirectoryEntry> {
        val dataset = gateway.dataset
        fun entry(version: String?) = DatasetDirectoryEntry(
            contextName = dataset.namedAggregate.contextName,
            name = dataset.namedAggregate.aggregateName,
            title = dataset.title,
            version = version,
        )
        return gateway.describe(QueryEntry.HTTP)
            .map { entry(it.version) }
            .onErrorResume { Mono.just(entry(null)) }
    }
}

/** Creates the handler of the dataset directory route, identified by [handlerKey]. */
class DatasetDirectoryHandlerFunctionFactory(
    override val handlerKey: String,
    private val gateways: DatasetQueryGateways,
    private val exceptionHandler: RequestExceptionHandler,
) : HttpRouteHandlerFunctionFactory {
    override fun create(
        contract: HttpRouteContract,
        metadata: HttpRouteHandlerMetadata
    ): HandlerFunction<ServerResponse> = DatasetDirectoryHandlerFunction(gateways, exceptionHandler)
}
