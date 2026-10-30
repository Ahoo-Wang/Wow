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

package me.ahoo.wow.client

import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.CommandManifest
import me.ahoo.wow.manifest.CommandRoutes
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.Creation
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.ManifestFragment
import me.ahoo.wow.manifest.StateManifest

/**
 * A peer's contract declaring [commands] on [type], each named by its class's simple name and routed by id and
 * without one (a command that may create its aggregate), as a domain's would; one whose body names its aggregate in
 * [idProperty] by its route without an id alone.
 */
fun contractOf(
    type: AggregateType,
    vararg commands: Class<*>,
    creation: Creation = Creation.ALLOWED,
    idProperty: String? = null,
): List<ManifestFragment> {
    val aggregate = AggregateManifest(
        type.name,
        state = StateManifest(Manifest.schemaRef("${type}State")),
        commands = commands.map {
            CommandManifest(
                it.simpleName,
                schema = Manifest.schemaRef(it.name),
                creation = creation,
                routes = if (idProperty == null) {
                    CommandRoutes.of(type.context, type.name, it.simpleName, creation)
                } else {
                    listOf(CommandRoutes.unaddressed(type.context, type.name, it.simpleName))
                },
                idProperty = idProperty,
            )
        },
    )
    val context = ContextManifest(type.context, aggregates = listOf(aggregate))
    return listOf(ManifestFragment("${type.context}-api.jar", Manifest(contexts = listOf(context))))
}
