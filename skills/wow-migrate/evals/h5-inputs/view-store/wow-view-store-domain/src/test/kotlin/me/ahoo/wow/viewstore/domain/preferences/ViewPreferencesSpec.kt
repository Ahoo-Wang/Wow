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
import me.ahoo.wow.api.abac.DefaultApplyResourceTags
import me.ahoo.wow.api.abac.ResourceTagsApplied
import me.ahoo.wow.api.command.DefaultDeleteAggregate
import me.ahoo.wow.api.command.DefaultRecoverAggregate
import me.ahoo.wow.api.event.DefaultAggregateDeleted
import me.ahoo.wow.api.event.DefaultAggregateRecovered
import me.ahoo.wow.exception.NotFoundResourceException
import me.ahoo.wow.messaging.DefaultHeader
import me.ahoo.wow.test.AggregateSpec
import me.ahoo.wow.viewstore.api.ViewStoreErrorCodes
import me.ahoo.wow.viewstore.api.preferences.SetViewPreferences
import me.ahoo.wow.viewstore.api.preferences.ViewPreferencesSet
import me.ahoo.wow.viewstore.domain.ViewFixtures.ALICE
import me.ahoo.wow.viewstore.domain.ViewFixtures.APP
import me.ahoo.wow.viewstore.domain.ViewFixtures.OTHER_APP
import me.ahoo.wow.viewstore.domain.ViewFixtures.appHeader
import me.ahoo.wow.viewstore.domain.ViewStoreException
import me.ahoo.wow.viewstore.domain.view.ViewConfigs

class ViewPreferencesSpec : AggregateSpec<ViewPreferences, ViewPreferencesState>({
    on(aggregateId = ViewPreferencesIds.of("tenant", ALICE, APP, "orders")) {
        val first = SetViewPreferences(
            definitionId = "orders",
            order = listOf("v2", "v1"),
            defaultInstanceId = "v2",
            autoRun = false,
            lastTabs = mapOf("board" to "tab-2"),
        )
        whenCommand(first, appHeader(), ALICE) {
            expectNoError()
            expectEventType(ViewPreferencesSet::class)
            expectStateAggregate {
                version.assert().isEqualTo(1)
                ownerId.assert().isEqualTo(ALICE)
            }
            expectState {
                definitionId.assert().isEqualTo("orders")
                appId.assert().isEqualTo(APP)
                order.assert().containsExactly("v2", "v1")
                defaultInstanceId.assert().isEqualTo("v2")
                autoRun.assert().isFalse()
                lastTabs.assert().isEqualTo(mapOf("board" to "tab-2"))
            }
            fork("set again replaces them") {
                whenCommand(SetViewPreferences(definitionId = "orders"), appHeader(), ALICE) {
                    expectNoError()
                    expectState {
                        order.assert().isEmpty()
                        defaultInstanceId.assert().isNull()
                        autoRun.assert().isNull()
                        lastTabs.assert().isNull()
                    }
                }
            }
            fork("another definition is refused") {
                whenCommand(SetViewPreferences(definitionId = "customers"), appHeader(), ALICE) {
                    expectError<ViewStoreException> {
                        errorCode.assert().isEqualTo(ViewStoreErrorCodes.VIEW_INVALID)
                    }
                }
            }
            fork("Wow's delete and recover, in process") {
                whenCommand(DefaultDeleteAggregate, appHeader(), ALICE) {
                    expectNoError()
                    expectEventType(DefaultAggregateDeleted::class)
                    fork("recover") {
                        whenCommand(DefaultRecoverAggregate, appHeader(), ALICE) {
                            expectNoError()
                            expectEventType(DefaultAggregateRecovered::class)
                        }
                    }
                }
            }
            fork("Wow's resource tags, in process") {
                whenCommand(DefaultApplyResourceTags(mapOf("team" to listOf("a"))), appHeader(), ALICE) {
                    expectNoError()
                    expectEventType(ResourceTagsApplied::class)
                }
            }
            fork("another application reads as not found") {
                whenCommand(SetViewPreferences(definitionId = "orders"), appHeader(OTHER_APP), ALICE) {
                    expectErrorType(NotFoundResourceException::class)
                }
            }
        }
    }
    on {
        whenCommand(SetViewPreferences(definitionId = "orders"), DefaultHeader.empty(), ALICE) {
            expectError<ViewStoreException> {
                errorCode.assert().isEqualTo(ViewStoreErrorCodes.VIEW_APP_REQUIRED)
            }
        }
    }
    on {
        whenCommand(SetViewPreferences(definitionId = " "), appHeader(), ALICE) {
            expectError<ViewStoreException> {
                errorCode.assert().isEqualTo(ViewStoreErrorCodes.VIEW_INVALID)
            }
        }
    }
    val id = "i".repeat(ViewConfigs.MAX_ID_LENGTH)
    on {
        whenCommand(
            SetViewPreferences(definitionId = id, order = listOf(id), defaultInstanceId = id),
            appHeader(),
            ALICE
        ) {
            expectNoError()
        }
    }
    listOf(
        SetViewPreferences(definitionId = id + "i"),
        SetViewPreferences(definitionId = "orders", order = listOf("a", id + "i")),
        SetViewPreferences(definitionId = "orders", defaultInstanceId = id + "i"),
    ).forEach { command ->
        on {
            whenCommand(command, appHeader(), ALICE) {
                expectError<ViewStoreException> {
                    errorCode.assert().isEqualTo(ViewStoreErrorCodes.VIEW_INVALID)
                }
            }
        }
    }
})
