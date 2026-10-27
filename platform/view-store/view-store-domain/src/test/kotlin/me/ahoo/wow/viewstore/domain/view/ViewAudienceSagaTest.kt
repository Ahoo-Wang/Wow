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

import me.ahoo.wow.api.lifecycle.TransferOwner
import me.ahoo.wow.kernel.Dependencies
import me.ahoo.wow.test.sagaSpec
import me.ahoo.wow.viewstore.api.ViewAudience
import me.ahoo.wow.viewstore.api.ViewKind
import me.ahoo.wow.viewstore.api.ViewStoreContext
import me.ahoo.wow.viewstore.api.view.ClaimView
import me.ahoo.wow.viewstore.api.view.RenameView
import me.ahoo.wow.viewstore.api.view.ShareView
import me.ahoo.wow.viewstore.api.view.ViewClaimed
import me.ahoo.wow.viewstore.api.view.ViewCreated
import me.ahoo.wow.viewstore.api.view.ViewRenamed
import me.ahoo.wow.viewstore.api.view.ViewShared
import me.ahoo.wow.viewstore.domain.ViewFixtures.ALICE
import me.ahoo.wow.viewstore.domain.ViewFixtures.APP
import me.ahoo.wow.viewstore.domain.ViewFixtures.BOB
import me.ahoo.wow.viewstore.domain.ViewFixtures.SHARED
import me.ahoo.wow.viewstore.domain.ViewFixtures.owner
import me.ahoo.wow.viewstore.domain.ViewFixtures.recordConfig
import org.junit.jupiter.api.Test

/** A view's audience change moves it to the owner the audience names (D189: only TransferOwner changes the owner). */
class ViewAudienceSagaTest {
    private val handlers = viewAudienceSagaHandlers(Dependencies.of())
    private val model = viewModel(sharedBoardReferences = { _, _, _ -> emptyList() })
    private val created = ViewCreated("orders", "Orders", ViewKind.RECORD, ViewAudience.PERSONAL, APP, recordConfig())

    @Test
    fun `a shared view should go to the owner (shared), a claimed one to the claiming user`() = sagaSpec(handlers) {
        val personal = givenAggregate(model, "view-1", created, scope = owner(ALICE))
        val shared = givenAggregate(model, "view-2", created, ViewShared, scope = SHARED)
        whenEvent(ViewShared, on = personal, by = ShareView::class) {
            expectCommand(TransferOwner.of(ViewStoreContext.SHARED_OWNER_ID), to = personal.ref)
            expectDeterministic()
        }
        whenEvent(ViewClaimed(BOB), on = shared, by = ClaimView::class) {
            expectCommand(TransferOwner.of(BOB), to = shared.ref)
        }
    }

    @Test
    fun `any other change of a view should send nothing`() = sagaSpec(handlers) {
        givenAggregate(model, "view-1", created, scope = owner(ALICE))
        whenEvent(ViewRenamed("Orders"), by = RenameView::class) { expectNoCommand() }
    }
}
