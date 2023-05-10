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

package me.ahoo.wow.webflux.route

import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.api.modeling.TenantId
import me.ahoo.wow.command.CommandOperator
import me.ahoo.wow.command.asCommandMessage
import me.ahoo.wow.modeling.matedata.AggregateMetadata
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.webflux.route.appender.CommandHeaders
import org.springframework.web.reactive.function.server.ServerRequest
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.switchIfEmpty
import reactor.kotlin.core.publisher.toMono

object CommandParser {
    fun ServerRequest.getTenantId(aggregateMetadata: AggregateMetadata<*, *>): String {
        aggregateMetadata.staticTenantId?.let {
            return it
        }
        return pathVariables()[MessageRecords.TENANT_ID] ?: TenantId.DEFAULT_TENANT_ID
    }

    fun ServerRequest.parse(
        aggregateMetadata: AggregateMetadata<*, *>,
        commandBody: Any,
        aggregateId: String? = null,
    ): Mono<CommandMessage<Any>> {
        val aggregateVersion = headers().firstHeader(CommandHeaders.AGGREGATE_VERSION)?.toIntOrNull()
        return principal()
            .map {
                val header = CommandOperator.withOperator(it.name)
                commandBody.asCommandMessage(
                    namedAggregate = aggregateMetadata,
                    aggregateId = aggregateId,
                    tenantId = getTenantId(aggregateMetadata),
                    aggregateVersion = aggregateVersion,
                    header = header,
                )
            }.switchIfEmpty {
                commandBody.asCommandMessage(
                    namedAggregate = aggregateMetadata,
                    aggregateId = aggregateId,
                    tenantId = getTenantId(aggregateMetadata),
                    aggregateVersion = aggregateVersion
                ).toMono()
            }
    }
}
