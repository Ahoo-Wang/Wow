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

package me.ahoo.wow.openapi

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.Paths
import io.swagger.v3.oas.models.info.Info
import me.ahoo.wow.api.command.DefaultDeleteAggregate
import me.ahoo.wow.api.naming.NamedBoundedContext
import me.ahoo.wow.configuration.MetadataSearcher
import me.ahoo.wow.modeling.annotation.asAggregateMetadata
import me.ahoo.wow.modeling.matedata.AggregateMetadata
import me.ahoo.wow.openapi.command.CommandRouteSpec
import me.ahoo.wow.openapi.command.CommandStageSchema
import me.ahoo.wow.openapi.command.CommandWaitRouteSpec
import me.ahoo.wow.openapi.compensation.DomainEventCompensateRouteSpec
import me.ahoo.wow.openapi.compensation.StateEventCompensateRouteSpec
import me.ahoo.wow.openapi.query.AggregateTracingRouteSpec
import me.ahoo.wow.openapi.query.IdsQueryAggregateRouteSpec
import me.ahoo.wow.openapi.query.LoadAggregateRouteSpec
import me.ahoo.wow.openapi.query.ScanAggregateRouteSpec
import me.ahoo.wow.openapi.route.asCommandRouteMetadata
import me.ahoo.wow.openapi.snapshot.BatchRegenerateSnapshotRouteSpec
import me.ahoo.wow.openapi.snapshot.LoadSnapshotRouteSpec
import me.ahoo.wow.openapi.snapshot.RegenerateSnapshotRouteSpec
import me.ahoo.wow.openapi.state.RegenerateStateEventRouteSpec

class Router(
    private val currentContext: NamedBoundedContext,
    private val routes: MutableList<RouteSpec> = mutableListOf()
) : MutableList<RouteSpec> by routes {
    @Volatile
    private var built: Boolean = false
    private val openAPI = OpenAPI().apply {
        info = Info()
        paths = Paths()
        components = Components()
    }

    fun addLocalAggregateRouteSpec(): Router {
        MetadataSearcher.namedAggregateType.forEach { aggregateEntry ->
            val aggregateType = aggregateEntry.value
            val aggregateMetadata = aggregateType.asAggregateMetadata<Any, Any>()
            addAggregateRouteSpec(aggregateMetadata)
        }
        return this
    }

    private fun Class<*>.addCommandRouteSpec(aggregateMetadata: AggregateMetadata<*, *>) {
        val appendTenantPath = aggregateMetadata.staticTenantId.isNullOrBlank()
        val commandRouteMetadata = asCommandRouteMetadata().copy(appendTenantPath = appendTenantPath)
        if (!commandRouteMetadata.enabled) {
            return
        }
        val commandRouteSpec = CommandRouteSpec(currentContext, aggregateMetadata, commandRouteMetadata).build()
        add(commandRouteSpec)
    }

    private fun addAggregateRouteSpec(aggregateMetadata: AggregateMetadata<*, *>): Router {
        //region command route
        aggregateMetadata.command.commandFunctionRegistry
            .forEach { entry ->
                entry.key.addCommandRouteSpec(aggregateMetadata)
            }
        if (!aggregateMetadata.command.registeredDeleteAggregate) {
            DefaultDeleteAggregate::class.java.addCommandRouteSpec(aggregateMetadata)
        }
        //endregion

        val loadAggregateRouteSpec = LoadAggregateRouteSpec(currentContext, aggregateMetadata).build()
        add(loadAggregateRouteSpec)
        val idsQueryAggregateRouteSpec = IdsQueryAggregateRouteSpec(currentContext, aggregateMetadata).build()
        add(idsQueryAggregateRouteSpec)
        val scanAggregateRouteSpec = ScanAggregateRouteSpec(currentContext, aggregateMetadata).build()
        add(scanAggregateRouteSpec)
        val aggregateTracingRouteSpec = AggregateTracingRouteSpec(currentContext, aggregateMetadata).build()
        add(aggregateTracingRouteSpec)

        //region snapshot
        val loadSnapshotRouteSpec = LoadSnapshotRouteSpec(currentContext, aggregateMetadata).build()
        add(loadSnapshotRouteSpec)
        val regenerateSnapshotRouteSpec = RegenerateSnapshotRouteSpec(currentContext, aggregateMetadata).build()
        add(regenerateSnapshotRouteSpec)
        val batchRegenerateSnapshotRouteSpec =
            BatchRegenerateSnapshotRouteSpec(currentContext, aggregateMetadata).build()
        add(batchRegenerateSnapshotRouteSpec)
        //endregion

        //region compensate
        val regenerateStateEventRouteSpec = RegenerateStateEventRouteSpec(currentContext, aggregateMetadata).build()
        add(regenerateStateEventRouteSpec)
        val domainEventCompensateRouteSpec = DomainEventCompensateRouteSpec(currentContext, aggregateMetadata).build()
        add(domainEventCompensateRouteSpec)
        val stateEventCompensateRouteSpec = StateEventCompensateRouteSpec(currentContext, aggregateMetadata).build()
        add(stateEventCompensateRouteSpec)
        //endregion

        return this
    }

    private fun addCommonSchema() {
        openAPI.components.addSchemas(CommandStageSchema.name, CommandStageSchema.schema)
    }

    fun openAPI(): OpenAPI {
        build()
        return openAPI
    }

    fun build(): Router {
        if (built) {
            return this
        }
        built = true
        addCommonSchema()
        add(CommandWaitRouteSpec)
        val groupedPathRoutes = routes.groupBy {
            it.path
        }
        for ((path, routeSpecs) in groupedPathRoutes) {
            routeSpecs.forEach { routeSpec ->
                routeSpec.schemas.forEach {
                    openAPI.components.addSchemas(it.key, it.value)
                }
            }

            openAPI.paths.addPathItem(path, routeSpecs.toPathItem())
        }

        return this
    }
}
