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

import me.ahoo.wow.api.annotation.Revision
import me.ahoo.wow.api.annotation.Summary
import me.ahoo.wow.viewstore.api.ViewAudience
import me.ahoo.wow.viewstore.api.ViewKind

// region view-created
/** Revision 2 records the [kind]; a 9.x record (revision 1) has none, and is read with its config's `kind`. */
@Summary("A view was saved")
@Revision(2)
data class ViewCreated(
    val definitionId: String,
    val title: String,
    val kind: ViewKind,
    val audience: ViewAudience,
    val appId: String,
    val config: Map<String, Any?>,
)
// endregion

/** Revision 2 records the [kind]; a 9.x record (revision 1) has none, and is read with its config's `kind`. */
@Summary("A view's config was overwritten")
@Revision(2)
data class ViewSaved(val kind: ViewKind, val config: Map<String, Any?>)

@Summary("A view was renamed")
data class ViewRenamed(val title: String)

@Summary("A view was made shared; its owner becomes (shared)")
data object ViewShared

@Summary("A shared view was made personal to toOwnerId, who becomes its owner")
data class ViewClaimed(val toOwnerId: String)

/**
 * **History only: never produced, only evolved.** The 9.x view's move to another audience (its `ViewAudienceChanged`,
 * a 9.x owner transfer), kept so the 9.x history `migrate-data` imports still loads: a view moves to [audience] as
 * [ViewShared] or [ViewClaimed] moves it. No command produces it (the view store's tests check it); a view shared or
 * claimed in V10 records [ViewShared] or [ViewClaimed]. [toOwnerId] is the owner 9.x moved the view to, which the import
 * records as the stream's owner (its map's `owner` field).
 */
@Summary("A 9.x view moved to another audience; history only, never produced")
data class ViewAudienceChanged(val audience: ViewAudience, val toOwnerId: String)
