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

package me.ahoo.wow.webflux.route.query

import me.ahoo.wow.api.query.RewritableCondition
import me.ahoo.wow.modeling.matedata.AggregateMetadata
import me.ahoo.wow.query.dsl.condition
import me.ahoo.wow.webflux.route.command.getOwnerId
import me.ahoo.wow.webflux.route.command.getTenantId
import org.springframework.web.reactive.function.server.ServerRequest

internal class RewriteRequestCondition(
    private val request: ServerRequest,
    private val aggregateMetadata: AggregateMetadata<*, *>,
) {

    fun <Q : RewritableCondition<Q>> rewrite(rewritableCondition: Q): Q {
        val tenantId = request.getTenantId(aggregateMetadata)
        val ownerId = request.getOwnerId()
        if (tenantId.isNullOrBlank() && ownerId.isNullOrBlank()) {
            return rewritableCondition
        }
        val appendCondition = condition {
            if (!tenantId.isNullOrBlank()) {
                tenantId(tenantId)
            }
            if (!ownerId.isNullOrBlank()) {
                ownerId(ownerId)
            }
        }
        return rewritableCondition.appendCondition(appendCondition)
    }
}
