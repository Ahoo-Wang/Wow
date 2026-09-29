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

package me.ahoo.wow.viewstore.api.view

import me.ahoo.wow.api.annotation.CommandRoute
import me.ahoo.wow.api.annotation.CreateAggregate
import me.ahoo.wow.api.annotation.Order
import me.ahoo.wow.api.annotation.Summary
import me.ahoo.wow.viewstore.api.ViewAudience
import me.ahoo.wow.viewstore.api.ViewKind
import tools.jackson.databind.node.ObjectNode

/**
 * Saves a new view under the path's owner, which is its audience. The server generates its id and takes its
 * application from `CoSec-App-Id`.
 */
@Order(1)
@Summary("Create a view")
@CreateAggregate
@CommandRoute(action = "", method = CommandRoute.Method.POST, appendIdPath = CommandRoute.AppendPath.NEVER)
data class CreateView(
    val definitionId: String,
    val title: String,
    /** The engine's `ViewConfig`, whole. The server checks only its shape and size. */
    val config: ObjectNode,
)

data class ViewCreated(
    val definitionId: String,
    val title: String,
    val audience: ViewAudience,
    val appId: String,
    val kind: ViewKind,
    val config: ObjectNode,
    /** The saved views the config's panels reference, for a dashboard; empty otherwise. */
    val references: Set<String> = emptySet(),
)
