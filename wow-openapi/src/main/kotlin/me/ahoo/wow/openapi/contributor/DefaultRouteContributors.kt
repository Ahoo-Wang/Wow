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

package me.ahoo.wow.openapi.contributor

import me.ahoo.wow.openapi.catalog.RouteContributor
import me.ahoo.wow.openapi.contributor.aggregate.command.CommandRouteContributor
import me.ahoo.wow.openapi.contributor.aggregate.event.EventRouteContributor
import me.ahoo.wow.openapi.contributor.aggregate.snapshot.SnapshotRouteContributor
import me.ahoo.wow.openapi.contributor.aggregate.state.StateRouteContributor
import me.ahoo.wow.openapi.contributor.global.CommandFacadeRouteContributor
import me.ahoo.wow.openapi.contributor.global.CommandWaitRouteContributor
import me.ahoo.wow.openapi.contributor.global.GenerateBIScriptRouteContributor
import me.ahoo.wow.openapi.contributor.global.GenerateGlobalIdRouteContributor
import me.ahoo.wow.openapi.contributor.global.GetWowMetadataRouteContributor

object DefaultRouteContributors {
    fun all(): List<RouteContributor> {
        return listOf(
            CommandWaitRouteContributor,
            CommandFacadeRouteContributor,
            GetWowMetadataRouteContributor,
            GenerateGlobalIdRouteContributor,
            GenerateBIScriptRouteContributor,
            CommandRouteContributor,
            StateRouteContributor,
            SnapshotRouteContributor,
            EventRouteContributor
        )
    }
}
