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

import me.ahoo.wow.viewstore.api.ViewAudience
import me.ahoo.wow.viewstore.api.ViewKind
import me.ahoo.wow.viewstore.api.view.ViewAudienceChanged
import me.ahoo.wow.viewstore.api.view.ViewClaimed
import me.ahoo.wow.viewstore.api.view.ViewCreated
import me.ahoo.wow.viewstore.api.view.ViewRenamed
import me.ahoo.wow.viewstore.api.view.ViewSaved
import me.ahoo.wow.viewstore.api.view.ViewShared

/**
 * A saved view: a record view, an analysis view or a dashboard. Its tenant and owner are the aggregate's own; its id
 * is the snapshot's `aggregateId`.
 */
class ViewState {
    var definitionId: String = ""
        private set
    var title: String = ""
        private set

    /** The config's `kind`, kept apart so a query filters and lists by it: the config itself is free-form. */
    var kind: ViewKind = ViewKind.RECORD
        private set

    /** Follows the owner: `(shared)` is shared, `(system)` system, any other owner personal. */
    var audience: ViewAudience = ViewAudience.PERSONAL
        private set

    /** The application it was created under: its creation's request fact `app_id`. */
    var appId: String = ""
        private set

    /** The engine's `ViewConfig`, whole; the server does not read its meaning. */
    var config: Map<String, Any?> = emptyMap()
        private set

    fun onSourcing(event: ViewCreated) {
        definitionId = event.definitionId
        title = event.title
        kind = event.kind
        audience = event.audience
        appId = event.appId
        config = event.config
    }

    fun onSourcing(event: ViewSaved) {
        kind = event.kind
        config = event.config
    }

    fun onSourcing(event: ViewRenamed) {
        title = event.title
    }

    @Suppress("UnusedParameter")
    fun onSourcing(event: ViewShared) {
        audience = ViewAudience.SHARED
    }

    @Suppress("UnusedParameter")
    fun onSourcing(event: ViewClaimed) {
        audience = ViewAudience.PERSONAL
    }

    /** A 9.x view's move, history only: the audience it moved to, as [ViewShared] or [ViewClaimed] moves it. */
    fun onSourcing(event: ViewAudienceChanged) {
        audience = event.audience
    }
}
