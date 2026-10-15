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

import me.ahoo.wow.api.annotation.CreateAggregate
import me.ahoo.wow.api.annotation.Summary

/**
 * Saves a new view under the caller's owner, which is its audience: a user's, `(shared)` or, under the tenant
 * `(platform)`, `(system)`. The server generates its id unless the route names one, and takes its application from
 * the request's `CoSec-App-Id`. The [config] is the view engine's `ViewConfig`, whole: the server checks only its
 * shape and size.
 */
@CreateAggregate
@Summary("Create a view")
data class CreateView(
    val definitionId: String,
    val title: String,
    val config: Map<String, Any?>,
)

/** Overwrites a view's config. */
@Summary("Save a view's config")
data class SaveView(val config: Map<String, Any?>)

/** Renames a view. */
@Summary("Rename a view")
data class RenameView(val title: String)

/**
 * Makes a personal view shared, in place: the view's audience changes at once, and its owner becomes `(shared)`
 * right after (the view's audience saga transfers it). The id stays.
 */
@Summary("Make a view shared")
data object ShareView

/**
 * Makes a shared view personal to the caller, in place: sent as the owner `(shared)` (`Wow-Scope`), it moves the
 * view to the operator who sent it. The audience changes at once, the owner right after. A view a shared dashboard
 * shows stays shared.
 */
@Summary("Make a shared view personal")
data object ClaimView
