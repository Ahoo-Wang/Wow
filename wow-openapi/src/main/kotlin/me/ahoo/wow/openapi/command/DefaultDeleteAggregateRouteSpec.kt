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

package me.ahoo.wow.openapi.command

import me.ahoo.wow.api.command.DefaultDeleteAggregate
import me.ahoo.wow.api.naming.NamedBoundedContext
import me.ahoo.wow.command.annotation.commandMetadata
import me.ahoo.wow.modeling.matedata.AggregateMetadata
import me.ahoo.wow.openapi.Https
import me.ahoo.wow.openapi.route.CommandRouteMetadata

private val DeleteCommandRoute = CommandRouteMetadata(
    path = "",
    method = Https.Method.DELETE,
    enabled = true,
    commandMetadata = commandMetadata<DefaultDeleteAggregate>()
)

class DefaultDeleteAggregateRouteSpec(
    currentContext: NamedBoundedContext,
    aggregateMetadata: AggregateMetadata<*, *>
) : CommandRouteSpec(currentContext, aggregateMetadata, DeleteCommandRoute) {
    override val appendIdPath: Boolean
        get() = true
}
