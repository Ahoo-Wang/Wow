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

package me.ahoo.wow.viewstore.domain.view

import me.ahoo.wow.api.annotation.OnSourcing
import me.ahoo.wow.serialization.JsonSerializer
import me.ahoo.wow.viewstore.api.ViewAudience
import me.ahoo.wow.viewstore.api.view.ViewAudienceChanged
import me.ahoo.wow.viewstore.api.view.ViewCreated
import me.ahoo.wow.viewstore.api.view.ViewRenamed
import me.ahoo.wow.viewstore.api.view.ViewSaved
import tools.jackson.databind.node.ObjectNode

/**
 * A saved view: a record view, an analysis view or a dashboard. Its tenant and owner are the aggregate's own.
 */
class ViewState(val id: String) {
    var definitionId: String = ""
        private set
    var title: String = ""
        private set

    /** Follows the owner: `(shared)` is shared, `(system)` system, any other owner is personal. */
    var audience: ViewAudience = ViewAudience.PERSONAL
        private set

    /** The `CoSec-App-Id` it was created under. */
    var appId: String = ""
        private set

    /**
     * The engine's `ViewConfig`, whole; the server does not read its meaning. Its `kind` is the view's kind, and a
     * list reads `config.kind` alone (the starter declares it in the query schema).
     */
    var config: ObjectNode = JsonSerializer.createObjectNode()
        private set

    @OnSourcing
    fun onCreated(event: ViewCreated) {
        definitionId = event.definitionId
        title = event.title
        audience = event.audience
        appId = event.appId
        config = event.config
    }

    @OnSourcing
    fun onSaved(event: ViewSaved) {
        config = event.config
    }

    @OnSourcing
    fun onRenamed(event: ViewRenamed) {
        title = event.title
    }

    @OnSourcing
    fun onAudienceChanged(event: ViewAudienceChanged) {
        audience = event.audience
    }
}
