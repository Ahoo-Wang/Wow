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

package me.ahoo.wow.viewstore.domain.wording

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.test.AggregateSpec
import me.ahoo.wow.test.aggregateSpec
import me.ahoo.wow.viewstore.api.wording.ClearDatasetWording
import me.ahoo.wow.viewstore.api.wording.DatasetWordingCleared
import me.ahoo.wow.viewstore.api.wording.DatasetWordingSet
import me.ahoo.wow.viewstore.api.wording.SetDatasetWording
import me.ahoo.wow.viewstore.domain.ViewFixtures.ALICE
import me.ahoo.wow.viewstore.domain.ViewFixtures.APP
import me.ahoo.wow.viewstore.domain.ViewFixtures.BOB
import me.ahoo.wow.viewstore.domain.ViewFixtures.OTHER_APP
import me.ahoo.wow.viewstore.domain.ViewFixtures.nobody
import me.ahoo.wow.viewstore.domain.ViewFixtures.user
import me.ahoo.wow.viewstore.domain.ViewStoreRejections
import me.ahoo.wow.viewstore.domain.view.ViewConfigs
import org.junit.jupiter.api.Test

/** A tenant's wording over a dataset: the tenant's, whoever writes it (Ownership.TENANT, D189). */
class DatasetWordingSpec {
    private val model = datasetWordingModel()
    private val fields = mapOf("status" to mapOf("label" to "状态"))

    /** The wording, written by no user from the application by default: it is the tenant's. */
    private fun spec(block: AggregateSpec<DatasetWordingState>.() -> Unit) =
        aggregateSpec(model, caller = nobody(), block = block)

    @Test
    fun `the wording should be set, read by anyone of the tenant, and cleared`() = spec {
        whenCommand(
            SetDatasetWording("orders", " Orders ", " order ", fields),
            expectedVersion = 0,
            caller = user(ALICE)
        ) {
            expectEvents(DatasetWordingSet("orders", APP, "Orders", "order", fields))
            // The tenant's: no owner is recorded, so another owner writes it too.
            expectScope(Scope.NONE)
        }
        whenCommand(SetDatasetWording("orders", title = "Sales"), caller = user(BOB)) {
            expectEvents(DatasetWordingSet("orders", APP, "Sales", null, null))
            expectState {
                isEmpty.assert().isFalse()
                title.assert().isEqualTo("Sales")
            }
        }
        whenCommand(ClearDatasetWording("orders")) {
            expectEvents(DatasetWordingCleared)
            expectState {
                isEmpty.assert().isTrue()
                dataset.assert().isEqualTo("orders")
            }
        }
        whenCommand(ClearDatasetWording("orders")) { expectUnchanged() }
        whenCommand(ClearDatasetWording("orders"), caller = nobody(app = OTHER_APP)) {
            expectRejected(ViewStoreRejections.NOT_FOUND)
        }
    }

    @Test
    fun `wording of only a noun, or only fields, should say something until it is cleared`() = spec {
        whenCommand(SetDatasetWording("orders", recordNoun = "order")) {
            expectState { isEmpty.assert().isFalse() }
        }
        whenCommand(SetDatasetWording("orders", fields = fields)) {
            expectState { isEmpty.assert().isFalse() }
        }
        whenCommand(ClearDatasetWording("orders")) { expectEvents(DatasetWordingCleared) }
        // A blank application is none.
        whenCommand(SetDatasetWording("orders"), caller = nobody(app = "  ")) {
            expectRejected(ViewStoreRejections.APP_REQUIRED)
        }
    }

    @Test
    fun `wording that was never set should not be cleared`() = spec {
        whenCommand(ClearDatasetWording("orders")) { expectRejected(FrameworkCodes.AGGREGATE_NOT_FOUND) }
    }

    @Test
    fun `the wording should keep its application and dataset and hold what the engine reads`() = spec {
        whenCommand(SetDatasetWording("orders"), caller = nobody(app = null)) {
            expectRejected(ViewStoreRejections.APP_REQUIRED)
        }
        whenCommand(SetDatasetWording(" ")) {
            expectRejected(ViewStoreRejections.INVALID) { message.assert().contains("dataset") }
        }
        whenCommand(SetDatasetWording("orders", title = " ")) {
            expectRejected(ViewStoreRejections.INVALID) { message.assert().contains("title") }
        }
        whenCommand(SetDatasetWording("orders", recordNoun = "x".repeat(41))) {
            expectRejected(ViewStoreRejections.INVALID) { message.assert().contains("noun") }
        }
        val heavy = mapOf("status" to "x".repeat(DatasetWording.MAX_FIELDS_BYTES))
        whenCommand(SetDatasetWording("orders", fields = heavy)) {
            expectRejected(ViewStoreRejections.INVALID) { message.assert().contains("fields") }
        }
        givenCommands(SetDatasetWording("orders"))
        whenCommand(SetDatasetWording("items")) {
            expectRejected(ViewStoreRejections.INVALID) { message.assert().contains("[orders]") }
        }
        whenCommand(SetDatasetWording("orders"), caller = nobody(app = OTHER_APP)) {
            expectRejected(ViewStoreRejections.NOT_FOUND)
        }
        whenCommand(SetDatasetWording("x".repeat(ViewConfigs.MAX_ID_LENGTH + 1))) {
            expectRejected(ViewStoreRejections.INVALID)
        }
    }
}
