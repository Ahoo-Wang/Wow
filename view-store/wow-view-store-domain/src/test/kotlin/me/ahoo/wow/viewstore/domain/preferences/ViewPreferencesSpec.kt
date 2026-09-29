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
import me.ahoo.wow.exception.NotFoundResourceException
import me.ahoo.wow.messaging.DefaultHeader
import me.ahoo.wow.test.AggregateSpec
import me.ahoo.wow.viewstore.api.ViewStoreErrorCodes
import me.ahoo.wow.viewstore.api.preferences.ApplyViewPreferencesTags
import me.ahoo.wow.viewstore.api.preferences.DeleteViewPreferences
import me.ahoo.wow.viewstore.api.preferences.RecoverViewPreferences
import me.ahoo.wow.viewstore.api.preferences.SetViewPreferences
import me.ahoo.wow.viewstore.api.preferences.ViewPreferencesDeleted
import me.ahoo.wow.viewstore.api.preferences.ViewPreferencesRecovered
import me.ahoo.wow.viewstore.api.preferences.ViewPreferencesSet
import me.ahoo.wow.viewstore.api.preferences.ViewPreferencesTagsApplied
import me.ahoo.wow.viewstore.domain.ViewFixtures.ALICE
import me.ahoo.wow.viewstore.domain.ViewFixtures.APP
import me.ahoo.wow.viewstore.domain.ViewFixtures.OTHER_APP
import me.ahoo.wow.viewstore.domain.ViewFixtures.appHeader
import me.ahoo.wow.viewstore.domain.ViewStoreException

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
            fork("delete and recover in process") {
                whenCommand(DeleteViewPreferences(stateRoot.id), appHeader(), ALICE) {
                    expectNoError()
                    expectEventType(ViewPreferencesDeleted::class)
                    fork("recover") {
                        whenCommand(RecoverViewPreferences(stateRoot.id), appHeader(), ALICE) {
                            expectNoError()
                            expectEventType(ViewPreferencesRecovered::class)
                        }
                    }
                    fork("recover from another application") {
                        whenCommand(RecoverViewPreferences(stateRoot.id), appHeader(OTHER_APP), ALICE) {
                            expectErrorType(NotFoundResourceException::class)
                        }
                    }
                }
            }
            fork("delete from another application") {
                whenCommand(DeleteViewPreferences(stateRoot.id), appHeader(OTHER_APP), ALICE) {
                    expectErrorType(NotFoundResourceException::class)
                }
            }
            fork("apply tags in process") {
                whenCommand(ApplyViewPreferencesTags(stateRoot.id, mapOf("team" to listOf("a"))), appHeader(), ALICE) {
                    expectNoError()
                    expectEventType(ViewPreferencesTagsApplied::class)
                }
            }
            fork("apply tags from another application") {
                whenCommand(
                    ApplyViewPreferencesTags(stateRoot.id, mapOf("team" to listOf("a"))),
                    appHeader(OTHER_APP),
                    ALICE
                ) {
                    expectErrorType(NotFoundResourceException::class)
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
})
