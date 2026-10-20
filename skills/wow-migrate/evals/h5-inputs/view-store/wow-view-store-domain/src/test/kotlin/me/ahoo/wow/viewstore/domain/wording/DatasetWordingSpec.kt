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
import me.ahoo.wow.api.event.DefaultAggregateDeleted
import me.ahoo.wow.exception.NotFoundResourceException
import me.ahoo.wow.messaging.DefaultHeader
import me.ahoo.wow.serialization.JsonSerializer
import me.ahoo.wow.test.AggregateSpec
import me.ahoo.wow.viewstore.ViewStoreService
import me.ahoo.wow.viewstore.api.ViewStoreErrorCodes
import me.ahoo.wow.viewstore.api.wording.ClearDatasetWording
import me.ahoo.wow.viewstore.api.wording.DatasetWordingSet
import me.ahoo.wow.viewstore.api.wording.SetDatasetWording
import me.ahoo.wow.viewstore.domain.ViewFixtures.APP
import me.ahoo.wow.viewstore.domain.ViewFixtures.OTHER_APP
import me.ahoo.wow.viewstore.domain.ViewFixtures.appHeader
import me.ahoo.wow.viewstore.domain.ViewStoreException
import me.ahoo.wow.viewstore.domain.view.ViewConfigs

private const val SHARED = ViewStoreService.SHARED_OWNER_ID
private const val ORDERS = "analytics.orders"

private fun fields() = JsonSerializer.createObjectNode().apply {
    put("buyer", "买家")
    put("discount", false)
    putObject("amount").put("label", "实付")
}

class DatasetWordingSpec : AggregateSpec<DatasetWording, DatasetWordingState>({
    on(aggregateId = DatasetWordingIds.of("tenant", APP, ORDERS)) {
        val first = SetDatasetWording(
            dataset = ORDERS,
            title = "  订单  ",
            recordNoun = "订单",
            fields = fields(),
        )
        whenCommand(first, appHeader(), SHARED) {
            expectNoError()
            expectEventType(DatasetWordingSet::class)
            expectStateAggregate {
                version.assert().isEqualTo(1)
                ownerId.assert().isEqualTo(SHARED)
            }
            expectState {
                dataset.assert().isEqualTo(ORDERS)
                appId.assert().isEqualTo(APP)
                title.assert().isEqualTo("订单")
                recordNoun.assert().isEqualTo("订单")
                fields.assert().isEqualTo(fields())
            }
            fork("set again replaces it") {
                whenCommand(SetDatasetWording(dataset = ORDERS), appHeader(), SHARED) {
                    expectNoError()
                    expectState {
                        title.assert().isNull()
                        recordNoun.assert().isNull()
                        fields.assert().isNull()
                    }
                }
            }
            fork("another dataset is refused") {
                whenCommand(SetDatasetWording(dataset = "analytics.refunds"), appHeader(), SHARED) {
                    expectError<ViewStoreException> {
                        errorCode.assert().isEqualTo(ViewStoreErrorCodes.VIEW_INVALID)
                    }
                }
            }
            fork("another application reads as not found") {
                whenCommand(SetDatasetWording(dataset = ORDERS), appHeader(OTHER_APP), SHARED) {
                    expectErrorType(NotFoundResourceException::class)
                }
            }
            fork("clear") {
                whenCommand(ClearDatasetWording, appHeader(), SHARED) {
                    expectNoError()
                    expectEventType(DefaultAggregateDeleted::class)
                }
            }
            fork("another application's clear reads as not found") {
                whenCommand(ClearDatasetWording, appHeader(OTHER_APP), SHARED) {
                    expectErrorType(NotFoundResourceException::class)
                }
            }
        }
    }
    on {
        whenCommand(SetDatasetWording(dataset = ORDERS), DefaultHeader.empty(), SHARED) {
            expectError<ViewStoreException> {
                errorCode.assert().isEqualTo(ViewStoreErrorCodes.VIEW_APP_REQUIRED)
            }
        }
    }
    val tooBig = JsonSerializer.createObjectNode().put("buyer", "x".repeat(DatasetWording.MAX_FIELDS_BYTES))
    listOf(
        SetDatasetWording(dataset = " "),
        SetDatasetWording(dataset = "d".repeat(ViewConfigs.MAX_ID_LENGTH + 1)),
        SetDatasetWording(dataset = ORDERS, title = " "),
        SetDatasetWording(dataset = ORDERS, title = "t".repeat(ViewConfigs.MAX_TITLE_LENGTH + 1)),
        SetDatasetWording(dataset = ORDERS, recordNoun = "n".repeat(DatasetWording.MAX_NOUN_LENGTH + 1)),
        SetDatasetWording(dataset = ORDERS, fields = tooBig),
    ).forEach { command ->
        on {
            whenCommand(command, appHeader(), SHARED) {
                expectError<ViewStoreException> {
                    errorCode.assert().isEqualTo(ViewStoreErrorCodes.VIEW_INVALID)
                }
            }
        }
    }
})
