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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.abac.DefaultApplyResourceTags
import me.ahoo.wow.api.abac.ResourceTagsApplied
import me.ahoo.wow.api.command.DefaultDeleteAggregate
import me.ahoo.wow.api.command.DefaultRecoverAggregate
import me.ahoo.wow.api.event.DefaultAggregateDeleted
import me.ahoo.wow.api.event.DefaultAggregateRecovered
import me.ahoo.wow.exception.NotFoundResourceException
import me.ahoo.wow.messaging.DefaultHeader
import me.ahoo.wow.modeling.command.IllegalAccessDeletedAggregateException
import me.ahoo.wow.modeling.command.IllegalAccessOwnerAggregateException
import me.ahoo.wow.serialization.JsonSerializer
import me.ahoo.wow.test.AggregateSpec
import me.ahoo.wow.viewstore.ViewStoreService.SHARED_OWNER_ID
import me.ahoo.wow.viewstore.api.ViewAudience
import me.ahoo.wow.viewstore.api.ViewKind
import me.ahoo.wow.viewstore.api.ViewStoreErrorCodes
import me.ahoo.wow.viewstore.api.view.ClaimView
import me.ahoo.wow.viewstore.api.view.CreateView
import me.ahoo.wow.viewstore.api.view.RenameView
import me.ahoo.wow.viewstore.api.view.SaveView
import me.ahoo.wow.viewstore.api.view.ShareView
import me.ahoo.wow.viewstore.api.view.ViewAudienceChanged
import me.ahoo.wow.viewstore.api.view.ViewCreated
import me.ahoo.wow.viewstore.api.view.ViewRenamed
import me.ahoo.wow.viewstore.api.view.ViewSaved
import me.ahoo.wow.viewstore.domain.ViewFixtures.ALICE
import me.ahoo.wow.viewstore.domain.ViewFixtures.APP
import me.ahoo.wow.viewstore.domain.ViewFixtures.BOB
import me.ahoo.wow.viewstore.domain.ViewFixtures.OTHER_APP
import me.ahoo.wow.viewstore.domain.ViewFixtures.appHeader
import me.ahoo.wow.viewstore.domain.ViewFixtures.dashboardConfig
import me.ahoo.wow.viewstore.domain.ViewFixtures.recordConfig
import me.ahoo.wow.viewstore.domain.ViewStoreException
import reactor.core.publisher.Flux

private val NO_BOARDS = SharedBoardReferences { _, _, _ -> Flux.empty() }

class ViewSpec : AggregateSpec<View, ViewState>({
    on {
        inject {
            register(NO_BOARDS)
        }
        whenCommand(CreateView("orders", "  Open orders  ", recordConfig()), appHeader(), ALICE) {
            expectNoError()
            expectEventType(ViewCreated::class)
            expectStateAggregate {
                ownerId.assert().isEqualTo(ALICE)
            }
            expectState {
                definitionId.assert().isEqualTo("orders")
                title.assert().isEqualTo("Open orders")
                audience.assert().isEqualTo(ViewAudience.PERSONAL)
                appId.assert().isEqualTo(APP)
                config.assert().isEqualTo(recordConfig())
            }
            ref("personal")
            fork("save a new config") {
                val config = recordConfig().put("pageSize", 50)
                whenCommand(SaveView(config), appHeader(), ALICE) {
                    expectNoError()
                    expectEventType(ViewSaved::class)
                    expectState {
                        this.config.assert().isEqualTo(config)
                    }
                }
            }
            fork("save a config of another kind") {
                whenCommand(SaveView(dashboardConfig("v1")), appHeader(), ALICE) {
                    expectNoError()
                    expectState {
                        config.get("kind").stringValue().assert().isEqualTo(ViewKind.DASHBOARD.value)
                    }
                }
            }
            fork("save an invalid config") {
                val config = JsonSerializer.createObjectNode().put("kind", "chart")
                whenCommand(SaveView(config), appHeader(), ALICE) {
                    expectErrorType(ViewStoreException::class)
                    expectError<ViewStoreException> {
                        errorCode.assert().isEqualTo(ViewStoreErrorCodes.VIEW_INVALID)
                    }
                }
            }
            fork("save from another application reads as not found") {
                whenCommand(SaveView(recordConfig()), appHeader(OTHER_APP), ALICE) {
                    expectErrorType(NotFoundResourceException::class)
                }
            }
            fork("save without an application") {
                whenCommand(SaveView(recordConfig()), DefaultHeader.empty(), ALICE) {
                    expectError<ViewStoreException> {
                        errorCode.assert().isEqualTo(ViewStoreErrorCodes.VIEW_APP_REQUIRED)
                    }
                }
            }
            fork("save under another owner is refused by Wow") {
                whenCommand(SaveView(recordConfig()), appHeader(), BOB) {
                    expectErrorType(IllegalAccessOwnerAggregateException::class)
                }
            }
            fork("rename trims the title") {
                whenCommand(RenameView(" Closed orders "), appHeader(), ALICE) {
                    expectNoError()
                    expectEventType(ViewRenamed::class)
                    expectState {
                        title.assert().isEqualTo("Closed orders")
                    }
                }
            }
            fork("rename to a blank title") {
                whenCommand(RenameView("   "), appHeader(), ALICE) {
                    expectError<ViewStoreException> {
                        errorCode.assert().isEqualTo(ViewStoreErrorCodes.VIEW_INVALID)
                    }
                }
            }
            fork("rename to a title over 120 characters") {
                whenCommand(RenameView("x".repeat(121)), appHeader(), ALICE) {
                    expectError<ViewStoreException> {
                        errorCode.assert().isEqualTo(ViewStoreErrorCodes.VIEW_INVALID)
                    }
                }
            }
            fork("rename from another application reads as not found") {
                whenCommand(RenameView("Mine"), appHeader(OTHER_APP), ALICE) {
                    expectErrorType(NotFoundResourceException::class)
                }
            }
            fork("share transfers the owner to (shared)") {
                whenCommand(ShareView, appHeader(), ALICE) {
                    expectNoError()
                    expectEventType(ViewAudienceChanged::class)
                    expectEventBody<ViewAudienceChanged> {
                        toOwnerId.assert().isEqualTo(SHARED_OWNER_ID)
                    }
                    expectStateAggregate {
                        ownerId.assert().isEqualTo(SHARED_OWNER_ID)
                        aggregateId.id.assert().isEqualTo(state.id)
                    }
                    expectState {
                        audience.assert().isEqualTo(ViewAudience.SHARED)
                    }
                }
            }
            fork("claimed again by its owner keeps the owner") {
                whenCommand(ClaimView(ALICE), appHeader(), ALICE) {
                    expectNoError()
                    expectEventBody<ViewAudienceChanged> {
                        toOwnerId.assert().isEqualTo(ALICE)
                    }
                    expectStateAggregate {
                        ownerId.assert().isEqualTo(ALICE)
                    }
                }
            }
            fork("a personal view is not claimed for another user") {
                whenCommand(ClaimView(BOB), appHeader(), ALICE) {
                    expectError<ViewStoreException> {
                        errorCode.assert().isEqualTo(ViewStoreErrorCodes.VIEW_INVALID)
                    }
                }
            }
            fork("share from another application reads as not found") {
                whenCommand(ShareView, appHeader(OTHER_APP), ALICE) {
                    expectErrorType(NotFoundResourceException::class)
                }
            }
            fork("claim from another application reads as not found") {
                whenCommand(ClaimView(ALICE), appHeader(OTHER_APP), ALICE) {
                    expectErrorType(NotFoundResourceException::class)
                }
            }
            fork("delete") {
                whenCommand(DefaultDeleteAggregate, appHeader(), ALICE) {
                    expectNoError()
                    expectEventType(DefaultAggregateDeleted::class)
                    expectStateAggregate {
                        deleted.assert().isTrue()
                    }
                    fork("a deleted view reads as gone") {
                        whenCommand(RenameView("Again"), appHeader(), ALICE) {
                            expectErrorType(IllegalAccessDeletedAggregateException::class)
                        }
                    }
                    fork("Wow's recover, in process") {
                        whenCommand(DefaultRecoverAggregate, appHeader(), ALICE) {
                            expectNoError()
                            expectEventType(DefaultAggregateRecovered::class)
                            expectStateAggregate {
                                deleted.assert().isFalse()
                            }
                        }
                    }
                }
            }
            fork("Wow's resource tags, in process") {
                whenCommand(DefaultApplyResourceTags(mapOf("team" to listOf("a"))), appHeader(), ALICE) {
                    expectNoError()
                    expectEventType(ResourceTagsApplied::class)
                    expectStateAggregate {
                        tags.assert().isEqualTo(mapOf("team" to listOf("a")))
                    }
                }
            }
            fork("delete from another application reads as not found") {
                whenCommand(DefaultDeleteAggregate, appHeader(OTHER_APP), ALICE) {
                    expectErrorType(NotFoundResourceException::class)
                }
            }
        }
    }
    on {
        inject {
            register(NO_BOARDS)
        }
        whenCommand(CreateView("orders", "Team orders", recordConfig()), appHeader(), SHARED_OWNER_ID) {
            expectNoError()
            expectState {
                audience.assert().isEqualTo(ViewAudience.SHARED)
            }
            expectStateAggregate {
                ownerId.assert().isEqualTo(SHARED_OWNER_ID)
            }
            fork("claim: the owner of the claiming path becomes the owner") {
                whenCommand(ClaimView(BOB), appHeader(), SHARED_OWNER_ID) {
                    expectNoError()
                    expectEventBody<ViewAudienceChanged> {
                        audience.assert().isEqualTo(ViewAudience.PERSONAL)
                        toOwnerId.assert().isEqualTo(BOB)
                    }
                    expectStateAggregate {
                        ownerId.assert().isEqualTo(BOB)
                    }
                    expectState {
                        audience.assert().isEqualTo(ViewAudience.PERSONAL)
                    }
                }
            }
            listOf("", " ", SHARED_OWNER_ID, "(0)", "alice ", "al ice", "alice\t", "alice\u0000").forEach { owner ->
                fork("claim for the reserved or blank owner [$owner]") {
                    whenCommand(ClaimView(owner), appHeader(), SHARED_OWNER_ID) {
                        expectError<ViewStoreException> {
                            errorCode.assert().isEqualTo(ViewStoreErrorCodes.VIEW_INVALID)
                        }
                    }
                }
            }
            fork("claim sent to another owner than the view's is refused by Wow") {
                whenCommand(ClaimView(BOB), appHeader(), BOB) {
                    expectErrorType(IllegalAccessOwnerAggregateException::class)
                }
            }
            fork("share again keeps the owner") {
                whenCommand(ShareView, appHeader(), SHARED_OWNER_ID) {
                    expectNoError()
                    expectStateAggregate {
                        ownerId.assert().isEqualTo(SHARED_OWNER_ID)
                    }
                }
            }
        }
    }
    on {
        inject {
            register(
                SharedBoardReferences { tenantId, appId, viewId ->
                    Flux.just(BoardReference("board-1", "Sales $tenantId $appId $viewId"))
                }
            )
        }
        whenCommand(CreateView("orders", "Team orders", recordConfig()), appHeader(), SHARED_OWNER_ID) {
            expectNoError()
            fork("a view a shared dashboard references stays shared") {
                whenCommand(ClaimView(BOB), appHeader(), SHARED_OWNER_ID) {
                    expectError<ViewStoreException> {
                        errorCode.assert().isEqualTo(ViewStoreErrorCodes.VIEW_INVALID)
                        bindingErrors.assert().hasSize(1)
                        bindingErrors.first().name.assert().isEqualTo("board-1")
                        bindingErrors.first().code.assert().isEqualTo(View.REFERENCED_BY_SHARED_DASHBOARD)
                    }
                    expectStateAggregate {
                        ownerId.assert().isEqualTo(SHARED_OWNER_ID)
                    }
                }
            }
            fork("a referenced view can still be deleted") {
                whenCommand(DefaultDeleteAggregate, appHeader(), SHARED_OWNER_ID) {
                    expectNoError()
                    expectEventType(DefaultAggregateDeleted::class)
                }
            }
        }
    }
    on {
        whenCommand(CreateView("orders", "Board", dashboardConfig("v1", "v2", "v1")), appHeader(), SHARED_OWNER_ID) {
            expectNoError()
            expectState {
                config.assert().isEqualTo(dashboardConfig("v1", "v2", "v1"))
            }
        }
    }
    on {
        whenCommand(CreateView("orders", "Open orders", recordConfig()), DefaultHeader.empty(), ALICE) {
            expectError<ViewStoreException> {
                errorCode.assert().isEqualTo(ViewStoreErrorCodes.VIEW_APP_REQUIRED)
            }
        }
    }
    on {
        whenCommand(CreateView("orders", " ", recordConfig()), appHeader(), ALICE) {
            expectError<ViewStoreException> {
                errorCode.assert().isEqualTo(ViewStoreErrorCodes.VIEW_INVALID)
            }
        }
    }
    on {
        whenCommand(CreateView(" ", "Open orders", recordConfig()), appHeader(), ALICE) {
            expectError<ViewStoreException> {
                errorCode.assert().isEqualTo(ViewStoreErrorCodes.VIEW_INVALID)
            }
        }
    }
    on {
        val tooLarge = recordConfig().put("note", "x".repeat(ViewConfigs.MAX_CONFIG_BYTES))
        whenCommand(CreateView("orders", "Open orders", tooLarge), appHeader(), ALICE) {
            expectError<ViewStoreException> {
                errorCode.assert().isEqualTo(ViewStoreErrorCodes.VIEW_INVALID)
            }
        }
    }
})
