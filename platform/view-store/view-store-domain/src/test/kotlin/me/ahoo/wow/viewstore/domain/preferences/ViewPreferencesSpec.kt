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

package me.ahoo.wow.viewstore.domain.preferences

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.test.AggregateSpec
import me.ahoo.wow.test.aggregateSpec
import me.ahoo.wow.viewstore.api.preferences.SetViewPreferences
import me.ahoo.wow.viewstore.api.preferences.ViewPreferencesSet
import me.ahoo.wow.viewstore.domain.ViewFixtures.ALICE
import me.ahoo.wow.viewstore.domain.ViewFixtures.APP
import me.ahoo.wow.viewstore.domain.ViewFixtures.BOB
import me.ahoo.wow.viewstore.domain.ViewFixtures.OTHER_APP
import me.ahoo.wow.viewstore.domain.ViewFixtures.owner
import me.ahoo.wow.viewstore.domain.ViewFixtures.user
import me.ahoo.wow.viewstore.domain.ViewStoreRejections
import me.ahoo.wow.viewstore.domain.view.ViewConfigs
import org.junit.jupiter.api.Test

class ViewPreferencesSpec {
    private val model = viewPreferencesModel()

    /** Alice's preferences, written by her from the application by default. */
    private fun spec(block: AggregateSpec<ViewPreferencesState>.() -> Unit) =
        aggregateSpec(model, caller = user(ALICE), block = block)

    @Test
    fun `preferences should be created on the first write and replaced on the next`() = spec {
        val first = SetViewPreferences(
            "orders",
            listOf("v2", "v1"),
            "v2",
            autoRun = true,
            lastTabs = mapOf("v2" to "t")
        )
        whenCommand(first, expectedVersion = 0) {
            expectEvents(ViewPreferencesSet("orders", APP, listOf("v2", "v1"), "v2", true, mapOf("v2" to "t")))
            expectState {
                definitionId.assert().isEqualTo("orders")
                appId.assert().isEqualTo(APP)
                order.assert().containsExactly("v2", "v1")
                defaultInstanceId.assert().isEqualTo("v2")
                autoRun.assert().isTrue()
                lastTabs.assert().containsEntry("v2", "t")
            }
        }
        whenCommand(first) { expectUnchanged() }
        // "Never written" against written preferences is a stale version.
        whenCommand(first.copy(order = listOf("v1")), expectedVersion = 0) {
            expectRejected(FrameworkCodes.AGGREGATE_VERSION_MISMATCH)
        }
        whenCommand(SetViewPreferences("orders"), expectedVersion = 1) {
            expectEvents(ViewPreferencesSet("orders", APP, emptyList(), null, null, null))
        }
        whenCommand(SetViewPreferences("orders"), caller = user(BOB)) {
            expectRejected(FrameworkCodes.AGGREGATE_FORBIDDEN)
        }
    }

    @Test
    fun `preferences should keep their application and definition`() = spec {
        whenCommand(SetViewPreferences("orders"), caller = user(ALICE, app = null)) { expectRejected(ViewStoreRejections.APP_REQUIRED) }
        whenCommand(SetViewPreferences("orders")) {
            expectEventTypes(ViewPreferencesSet::class)
        }
        whenCommand(SetViewPreferences("orders"), caller = user(ALICE, app = OTHER_APP)) {
            expectRejected(ViewStoreRejections.NOT_FOUND)
        }
        whenCommand(SetViewPreferences("items")) {
            expectRejected(ViewStoreRejections.INVALID) { message.assert().contains("[orders]") }
        }
    }

    @Test
    fun `preferences should hold ids a store can index`() = spec {
        val long = "x".repeat(ViewConfigs.MAX_ID_LENGTH + 1)
        whenCommand(SetViewPreferences(" ")) {
            expectRejected(ViewStoreRejections.INVALID) { message.assert().contains("blank") }
        }
        whenCommand(SetViewPreferences(long)) {
            expectRejected(ViewStoreRejections.INVALID) { message.assert().contains("definitionId") }
        }
        whenCommand(SetViewPreferences("orders", order = listOf(long))) {
            expectRejected(ViewStoreRejections.INVALID) { message.assert().contains("order") }
        }
        whenCommand(SetViewPreferences("orders", defaultInstanceId = long)) {
            expectRejected(ViewStoreRejections.INVALID) { message.assert().contains("defaultInstanceId") }
        }
    }
}
