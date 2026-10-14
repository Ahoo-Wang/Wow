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

package me.ahoo.wow.platform.viewstore.domain.view

import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.lifecycle.TransferOwner
import me.ahoo.wow.kernel.Dependencies
import me.ahoo.wow.kernel.test.sagaSpec
import me.ahoo.wow.kernel.test.stateEvent
import me.ahoo.wow.platform.viewstore.api.ViewStoreContext
import me.ahoo.wow.platform.viewstore.api.view.ViewClaimed
import me.ahoo.wow.platform.viewstore.api.view.ViewRenamed
import me.ahoo.wow.platform.viewstore.api.view.ViewShared
import me.ahoo.wow.platform.viewstore.domain.ViewFixtures
import org.junit.jupiter.api.Test

/** A view's audience change moves it to the owner the audience names (D189: only TransferOwner changes the owner). */
class ViewAudienceSagaTest {
    private val handlers = viewAudienceSagaHandlers(Dependencies.of())
    private val view = AggregateType(ViewStoreContext.NAME, ViewStoreContext.VIEW).ref(ViewFixtures.TENANT, "view-1")

    @Test
    fun `a shared view should go to the owner (shared), a claimed one to the claiming user`() = sagaSpec(handlers) {
        whenEvent(stateEvent(view, ViewState(), ViewShared, version = 2)) {
            expectCommand(TransferOwner(ViewStoreContext.SHARED_OWNER_ID), to = view)
            expectDeterministic()
        }
        whenEvent(stateEvent(view, ViewState(), ViewClaimed(ViewFixtures.BOB), version = 3)) {
            expectCommand(TransferOwner(ViewFixtures.BOB), to = view)
        }
    }

    @Test
    fun `any other change of a view should send nothing`() = sagaSpec(handlers) {
        whenEvent(stateEvent(view, ViewState(), ViewRenamed("Orders"), version = 2)) { expectNoCommand() }
    }
}
