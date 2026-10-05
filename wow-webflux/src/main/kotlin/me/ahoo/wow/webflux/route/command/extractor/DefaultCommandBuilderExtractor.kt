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

import me.ahoo.wow.api.annotation.OwnerPolicy
import me.ahoo.wow.command.CommandOperator.withOperator
import me.ahoo.wow.command.annotation.commandMetadata
import me.ahoo.wow.command.factory.CommandBuilder
import me.ahoo.wow.command.factory.CommandBuilder.Companion.commandBuilder
import me.ahoo.wow.identity.IdentityFact
import me.ahoo.wow.identity.IdentityHint
import me.ahoo.wow.identity.IdentityResolver
import me.ahoo.wow.identity.IdentitySource
import me.ahoo.wow.messaging.withLocalFirst
import me.ahoo.wow.openapi.aggregate.command.CommandComponent.Header.AGGREGATE_VERSION
import me.ahoo.wow.openapi.metadata.AggregateRouteMetadata
import me.ahoo.wow.webflux.route.command.getLocalFirst
import me.ahoo.wow.webflux.route.identity.identity
import org.springframework.web.reactive.function.server.ServerRequest
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono

/**
 * Builds a command from its route's request. Each identity fact comes from where the route's
 * [RouteIdentityBinding][me.ahoo.wow.webflux.route.identity.RouteIdentityBinding] says (path variable, static tenant,
 * header), decided by [IdentityResolver]; the space (`Wow-Space-Id`, then any space header alias) is taken only for a
 * [spaced][AggregateRouteMetadata.spaced] aggregate, so any other aggregate's command carries the default space
 * whatever the request sends.
 *
 * Since 9.3.0 (V3), a command body whose `@TenantId` or `@OwnerId` contradicts the tenant or owner the route fixes
 * (static tenant, `{tenantId}`, `{ownerId}`, or `{id}` of an aggregate owned by its ID) is rejected with an
 * [IllegalArgumentException] (`IllegalArgument`, 400), as is a `Command-Tenant-Id` / `Command-Owner-Id` header that
 * does.
 */
object DefaultCommandBuilderExtractor : CommandBuilderExtractor {
    override fun extract(
        aggregateRouteMetadata: AggregateRouteMetadata<*>,
        commandBody: Any,
        request: ServerRequest
    ): Mono<CommandBuilder> {
        val aggregateMetadata = aggregateRouteMetadata.aggregateMetadata
        val identity = request.identity(aggregateRouteMetadata)
        val commandMetadata = commandBody.javaClass.commandMetadata()
        val tenantId = identity.tenantId(body = commandMetadata.tenantIdGetter?.get(commandBody))
        val ownerId = identity.ownerId(body = commandMetadata.ownerIdGetter?.get(commandBody))
        val spaceId = identity.spaceId()
        val aggregateId = identity.aggregateId()
        val aggregateVersion = request.headers().firstHeader(AGGREGATE_VERSION)?.toIntOrNull()
        val requestId = identity.requestId()
        val commandBuilder = commandBody.commandBuilder()
            .aggregateId(aggregateId)
            .tenantId(tenantId)
            .ownerId(ownerId)
            .spaceId(spaceId)
            .aggregateVersion(aggregateVersion)
            .requestId(requestId)
            .namedAggregate(aggregateMetadata.namedAggregate)
            .ownerIdSameAsAggregateId(aggregateRouteMetadata.ownerPolicy == OwnerPolicy.AGGREGATE_ID)
        request.getLocalFirst()?.let {
            commandBuilder.header { header ->
                header.withLocalFirst(it)
            }
        }
        return request.principal().map { principal ->
            IdentityResolver.resolve(
                IdentityFact.OPERATOR,
                IdentityHint.of(IdentitySource.AUTH, principal.name)
            )?.let { operator ->
                commandBuilder.header { header ->
                    header.withOperator(operator)
                }
            }
            commandBuilder
        }.switchIfEmpty(commandBuilder.toMono())
    }
}
