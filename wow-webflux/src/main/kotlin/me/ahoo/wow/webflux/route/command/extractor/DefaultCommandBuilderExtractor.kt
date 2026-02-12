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

package me.ahoo.wow.webflux.route.command.extractor

import me.ahoo.wow.api.annotation.AggregateRoute
import me.ahoo.wow.command.CommandOperator.withOperator
import me.ahoo.wow.command.factory.CommandBuilder
import me.ahoo.wow.command.factory.CommandBuilder.Companion.commandBuilder
import me.ahoo.wow.infra.ifNotBlank
import me.ahoo.wow.messaging.withLocalFirst
import me.ahoo.wow.openapi.aggregate.command.CommandComponent.Header.AGGREGATE_VERSION
import me.ahoo.wow.openapi.aggregate.command.CommandComponent.Header.REQUEST_ID
import me.ahoo.wow.openapi.metadata.AggregateRouteMetadata
import me.ahoo.wow.webflux.route.command.getAggregateId
import me.ahoo.wow.webflux.route.command.getLocalFirst
import me.ahoo.wow.webflux.route.command.getOwnerId
import me.ahoo.wow.webflux.route.command.getSpaceId
import me.ahoo.wow.webflux.route.command.getTenantId
import org.springframework.web.reactive.function.server.ServerRequest
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono

object DefaultCommandBuilderExtractor : CommandBuilderExtractor {
    override fun extract(
        aggregateRouteMetadata: AggregateRouteMetadata<*>,
        commandBody: Any,
        request: ServerRequest
    ): Mono<CommandBuilder> {
        val aggregateMetadata = aggregateRouteMetadata.aggregateMetadata
        val tenantId = request.getTenantId(aggregateMetadata)
        val ownerId = request.getOwnerId()
        val spaceId = request.getSpaceId()
        val aggregateId = request.getAggregateId(aggregateRouteMetadata.owner, ownerId)
        val aggregateVersion = request.headers().firstHeader(AGGREGATE_VERSION)?.toIntOrNull()
        val requestId = request.headers().firstHeader(REQUEST_ID).ifNotBlank { it }
        val commandBuilder = commandBody.commandBuilder()
            .aggregateId(aggregateId)
            .tenantId(tenantId)
            .ownerId(ownerId)
            .spaceId(spaceId)
            .aggregateVersion(aggregateVersion)
            .requestId(requestId)
            .namedAggregate(aggregateMetadata.namedAggregate)
            .ownerIdSameAsAggregateId(aggregateRouteMetadata.owner == AggregateRoute.Owner.AGGREGATE_ID)
        request.getLocalFirst()?.let {
            commandBuilder.header { header ->
                header.withLocalFirst(it)
            }
        }
        return request.principal().map {
            commandBuilder.header { header ->
                header.withOperator(it.name)
            }
        }.switchIfEmpty(commandBuilder.toMono())
    }
}
