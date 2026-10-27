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

package me.ahoo.wow.manifest

/**
 * The framework's [system][CommandManifest.system] commands as a Manifest lists them, with their routes
 * ([CommandRoutes]): each named by its full system name, its body the schema of [Manifest.defs] keyed by that name.
 *
 * - an aggregate's lifecycle commands ([lifecycle]), those its model handles;
 * - an aggregate's snapshot rebuild ([rebuild]), when it keeps snapshots;
 * - a context's schedule job commands ([jobs]), when a subscription of it runs a job.
 */
object SystemCommands {
    /** The lifecycle command routed as `DELETE` of the aggregate ([CommandRoutes.deleteRoute]). */
    const val DELETE_AGGREGATE: String = "wow.lifecycle.DeleteAggregate"

    /** The rebuild of an aggregate type's snapshots, the ids in its body. */
    const val REBUILD: String = "wow.snapshot.Rebuild"

    /** The commands of a context's schedule jobs, each to the job its route's id names. */
    val JOBS: List<String> = listOf("wow.schedule.Enable", "wow.schedule.Reschedule", "wow.schedule.Disable")

    /** The lifecycle command [name] (`wow.lifecycle.RestoreAggregate`) of [aggregate] in [context]. */
    fun lifecycle(context: String, aggregate: String, name: String): CommandManifest = system(
        name,
        if (name == DELETE_AGGREGATE) {
            CommandRoutes.deleteRoute(context, aggregate)
        } else {
            RouteManifest(CommandRoutes.POST, CommandRoutes.byIdPath(context, aggregate, name))
        },
    )

    /** The snapshot rebuild of [aggregate] in [context]: `POST /{context}/{aggregate}/wow.snapshot.rebuild`. */
    fun rebuild(context: String, aggregate: String): CommandManifest =
        system(REBUILD, CommandRoutes.unaddressed(context, aggregate, REBUILD))

    /** The schedule job commands of [context]: `POST /{context}/wow.schedule/{id}/enable`, ... */
    fun jobs(context: String): List<CommandManifest> = JOBS.map { system(it, CommandRoutes.jobRoute(context, it)) }

    private fun system(name: String, route: RouteManifest): CommandManifest =
        CommandManifest(name = name, schema = Manifest.schemaRef(name), routes = listOf(route), system = true)
}
