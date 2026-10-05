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

package me.ahoo.wow.cosec.query

import me.ahoo.wow.cosec.identity.CoSecIdentityHeaders.SPACE_ID
import me.ahoo.wow.infra.ifNotBlank
import me.ahoo.wow.modeling.metadata.AggregateMetadata
import me.ahoo.wow.webflux.route.command.getSpaceId
import me.ahoo.wow.webflux.route.query.AbstractQueryRequestScope
import org.springframework.web.reactive.function.server.ServerRequest

/**
 * The request scope with CoSec's space: `Wow-Space-Id`, else `CoSec-Space-Id`. As for every
 * [AbstractQueryRequestScope], the space applies only to a spaced aggregate.
 */
@Deprecated(
    "Scheduled for removal in 10.0.0. CoSec contributes CoSec-Space-Id as a space header alias " +
        "(CoSecIdentityHeaders.ALIASES): the router applies it to every route it materializes, and the " +
        "QueryRequestScope bean to a query handler invoked outside the router."
)
object CoSecQueryRequestScope : AbstractQueryRequestScope() {

    @Suppress("DEPRECATION")
    override fun ServerRequest.resolveSpaceId(aggregateMetadata: AggregateMetadata<*, *>): String? {
        getSpaceId().ifNotBlank {
            return it
        }
        return this.headers().firstHeader(SPACE_ID)
    }
}
