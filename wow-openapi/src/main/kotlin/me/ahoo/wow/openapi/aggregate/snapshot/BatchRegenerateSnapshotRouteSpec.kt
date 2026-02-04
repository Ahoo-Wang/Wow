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

package me.ahoo.wow.openapi.aggregate.snapshot

import me.ahoo.wow.api.naming.NamedBoundedContext
import me.ahoo.wow.openapi.BatchComponent.PathVariable.BATCH_AFTER_ID
import me.ahoo.wow.openapi.BatchComponent.PathVariable.BATCH_LIMIT
import me.ahoo.wow.openapi.BatchRouteSpec
import me.ahoo.wow.openapi.BatchRouteSpecFactory
import me.ahoo.wow.openapi.Https
import me.ahoo.wow.openapi.RouteIdSpec
import me.ahoo.wow.openapi.context.OpenAPIComponentContext
import me.ahoo.wow.openapi.metadata.AggregateRouteMetadata

class BatchRegenerateSnapshotRouteSpec(
    override val currentContext: NamedBoundedContext,
    override val aggregateRouteMetadata: AggregateRouteMetadata<*>,
    override val componentContext: OpenAPIComponentContext
) : BatchRouteSpec {
    override val id: String
        get() = RouteIdSpec()
            .aggregate(aggregateMetadata)
            .appendTenant(appendTenantPath)
            .resourceName("snapshot")
            .operation("batch_regenerate").build()
    override val summary: String
        get() = "Batch Regenerate Aggregate Snapshot"

    override val method: String
        get() = Https.Method.PUT
    override val appendOwnerPath: Boolean
        get() = false
    override val appendPathSuffix: String
        get() = "snapshot/{$BATCH_AFTER_ID}/{$BATCH_LIMIT}"
}

class BatchRegenerateSnapshotRouteSpecFactory : BatchRouteSpecFactory() {
    override fun create(
        currentContext: NamedBoundedContext,
        aggregateRouteMetadata: AggregateRouteMetadata<*>
    ): List<BatchRegenerateSnapshotRouteSpec> {
        return listOf(
            BatchRegenerateSnapshotRouteSpec(
                currentContext = currentContext,
                aggregateRouteMetadata = aggregateRouteMetadata,
                componentContext = componentContext
            )
        )
    }
}
