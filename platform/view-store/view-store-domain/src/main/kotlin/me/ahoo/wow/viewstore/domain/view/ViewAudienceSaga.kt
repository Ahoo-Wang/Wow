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

import me.ahoo.wow.api.annotation.OnEvent
import me.ahoo.wow.api.annotation.Saga
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.lifecycle.TransferOwner
import me.ahoo.wow.api.subscribe.SagaContext
import me.ahoo.wow.api.subscribe.StateEvent
import me.ahoo.wow.viewstore.api.ViewStoreContext
import me.ahoo.wow.viewstore.api.view.ViewClaimed
import me.ahoo.wow.viewstore.api.view.ViewShared

/**
 * Moves a view to the owner its audience now names (the owner changes only through
 * `wow.lifecycle.TransferOwner`): `(shared)` once it is shared, the claiming user once it is claimed. The command
 * keeps the commit's scope and operator, and a derived command is not checked against the target's scope.
 * A caller who waits for the move asks for `Wow-Wait: handled(ViewAudienceSaga.onAudienceChanged)>snapshot`.
 */
@Saga
class ViewAudienceSaga {
    @OnEvent
    fun onAudienceChanged(event: StateEvent<ViewState>, context: SagaContext): Command<TransferOwner>? {
        val ownerId = event.events.firstNotNullOfOrNull { changed ->
            when (changed) {
                ViewShared -> ViewStoreContext.SHARED_OWNER_ID
                is ViewClaimed -> changed.toOwnerId
                else -> null
            }
        } ?: return null
        return context.command(event.ref, TransferOwner.of(ownerId))
    }
}
