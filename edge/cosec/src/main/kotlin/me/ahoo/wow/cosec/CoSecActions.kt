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

package me.ahoo.wow.cosec

import me.ahoo.cosec.runtime.route.Route
import me.ahoo.cosec.runtime.route.RouteMapDocument
import me.ahoo.wow.api.annotation.ExperimentalWowApi
import me.ahoo.wow.api.schedule.ScheduleJobs
import me.ahoo.wow.manifest.CommandRoutes
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.RouteManifest
import me.ahoo.wow.runtime.admission.CommandAction
import me.ahoo.wow.runtime.admission.RouteAction
import me.ahoo.wow.spi.snapshot.SnapshotRebuilds

/**
 * The CoSec actions and resources of Wow's commands and service routes, one name whether CoSec
 * decides the HTTP request or an authorizer decides the command in the process:
 *
 * - a command is the action `<context>:<command>`, the command's name as the Manifest lists it
 *   (`eshop-ordering:CreateOrder`, `shop:wow.lifecycle.DeleteAggregate`), on the resource `<aggregate>/<id>`
 *   (`order/o-1`). The snapshot rebuild of an aggregate type is on `snapshot/<aggregate>`, and a job command on
 *   `schedule/<job>`;
 * - a service route is the action `<context>:<route>` on the resource `route/<route>`.
 *
 * [routes] gives the HTTP side: CoSec's route map (`cosec-routes.json`) of the command and service routes of a
 * Manifest. A route that names no aggregate id (a creation, a mounted or `@Unaddressed` command) is on `<aggregate>`
 * alone, the id being the server's to choose: a policy for such a command names the aggregate type both with and
 * without an id. So is the route of a command whose body names its aggregate (`@AggregateId`), whose action the
 * authorizer then decides on `<aggregate>/<id>`, the id its body names. The query and state routes
 * are not mapped: CoSec reads them as `http:<METHOD>` on their path.
 */
@ExperimentalWowApi
object CoSecActions {
    /** The resource type of a service route. */
    const val ROUTE: String = "route"

    /** The action of the command [command] of [context]. */
    @JvmStatic
    fun command(context: String, command: String): String = "$context:$command"

    /** The action of [action]: its context and its command's name, as the Manifest lists it. */
    @JvmStatic
    fun command(action: CommandAction): String {
        val type = action.ref.type
        return command(type.context, action.type.name.removePrefix("$type."))
    }

    /** The resource of [action]: its aggregate's name and id. */
    @JvmStatic
    fun resource(action: CommandAction): String = "${action.ref.type.name}/${action.ref.id}"

    /** The action of the service route [route] of [context]. */
    @JvmStatic
    fun route(context: String, route: String): String = "$context:$route"

    /** The action of [action]: its context and its route's name. */
    @JvmStatic
    fun route(action: RouteAction): String = route(action.context, action.name)

    /** The resource of the service route [route]. */
    @JvmStatic
    fun routeResource(route: String): String = "$ROUTE/$route"

    /** CoSec's route map of the command and service routes of [manifest]. */
    @JvmStatic
    fun routes(manifest: Manifest): RouteMapDocument {
        val routes = buildList {
            manifest.contexts.forEach { context ->
                context.aggregates.forEach { aggregate ->
                    aggregate.commands.filterNot { it.mounted }.forEach { command ->
                        command.routes.forEach { route ->
                            val resource = when {
                                command.name == SnapshotRebuilds.REBUILD ->
                                    "${SnapshotRebuilds.AGGREGATE}/${aggregate.name}"

                                CommandRoutes.ID in route.path -> "${aggregate.name}/${CommandRoutes.ID}"
                                else -> aggregate.name
                            }
                            add(routeOf(route, command(context.name, command.name), resource))
                        }
                    }
                }
                context.commands.forEach { command ->
                    command.routes.forEach { route ->
                        add(
                            routeOf(
                                route,
                                command(context.name, command.name),
                                "${ScheduleJobs.AGGREGATE}/${CommandRoutes.ID}"
                            )
                        )
                    }
                }
                context.routes.forEach { route ->
                    add(routeOf(route.route, route(context.name, route.name), routeResource(route.name)))
                }
            }
        }
        return RouteMapDocument(manifest.service?.name ?: SERVICE, routes)
    }

    private fun routeOf(route: RouteManifest, action: String, resource: String) =
        Route(route.method, route.path, action, resource)

    /** The service a route map names when the Manifest names none. */
    private const val SERVICE = "wow"
}
