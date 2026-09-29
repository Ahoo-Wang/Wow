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
import me.ahoo.wow.viewstore.api.view.ApplyViewTags
import me.ahoo.wow.viewstore.api.view.ChangeViewAudience
import me.ahoo.wow.viewstore.api.view.CreateView
import me.ahoo.wow.viewstore.api.view.DeleteView
import me.ahoo.wow.viewstore.api.view.RecoverView
import me.ahoo.wow.viewstore.api.view.RenameView
import me.ahoo.wow.viewstore.api.view.SaveView
import me.ahoo.wow.viewstore.api.view.ViewAudienceChanged
import me.ahoo.wow.viewstore.api.view.ViewCreated
import me.ahoo.wow.viewstore.api.view.ViewDeleted
import me.ahoo.wow.viewstore.api.view.ViewRecovered
import me.ahoo.wow.viewstore.api.view.ViewRenamed
import me.ahoo.wow.viewstore.api.view.ViewSaved
import me.ahoo.wow.viewstore.api.view.ViewTagsApplied
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
                kind.assert().isEqualTo(ViewKind.RECORD)
                config.assert().isEqualTo(recordConfig())
                references.assert().isEmpty()
            }
            ref("personal")
            fork("save a new config") {
                val config = recordConfig().put("pageSize", 50)
                whenCommand(SaveView(stateRoot.id, config), appHeader(), ALICE) {
                    expectNoError()
                    expectEventType(ViewSaved::class)
                    expectState {
                        this.config.assert().isEqualTo(config)
                        kind.assert().isEqualTo(ViewKind.RECORD)
                    }
                }
            }
            fork("save a config of another kind") {
                whenCommand(SaveView(stateRoot.id, dashboardConfig("v1")), appHeader(), ALICE) {
                    expectNoError()
                    expectState {
                        kind.assert().isEqualTo(ViewKind.DASHBOARD)
                        references.assert().containsExactly("v1")
                    }
                }
            }
            fork("save an invalid config") {
                val config = JsonSerializer.createObjectNode().put("kind", "chart")
                whenCommand(SaveView(stateRoot.id, config), appHeader(), ALICE) {
                    expectErrorType(ViewStoreException::class)
                    expectError<ViewStoreException> {
                        errorCode.assert().isEqualTo(ViewStoreErrorCodes.VIEW_INVALID)
                    }
                }
            }
            fork("save from another application reads as not found") {
                whenCommand(SaveView(stateRoot.id, recordConfig()), appHeader(OTHER_APP), ALICE) {
                    expectErrorType(NotFoundResourceException::class)
                }
            }
            fork("save without an application") {
                whenCommand(SaveView(stateRoot.id, recordConfig()), DefaultHeader.empty(), ALICE) {
                    expectError<ViewStoreException> {
                        errorCode.assert().isEqualTo(ViewStoreErrorCodes.VIEW_APP_REQUIRED)
                    }
                }
            }
            fork("save under another owner is refused by Wow") {
                whenCommand(SaveView(stateRoot.id, recordConfig()), appHeader(), BOB) {
                    expectErrorType(IllegalAccessOwnerAggregateException::class)
                }
            }
            fork("rename trims the title") {
                whenCommand(RenameView(stateRoot.id, " Closed orders "), appHeader(), ALICE) {
                    expectNoError()
                    expectEventType(ViewRenamed::class)
                    expectState {
                        title.assert().isEqualTo("Closed orders")
                    }
                }
            }
            fork("rename to a blank title") {
                whenCommand(RenameView(stateRoot.id, "   "), appHeader(), ALICE) {
                    expectError<ViewStoreException> {
                        errorCode.assert().isEqualTo(ViewStoreErrorCodes.VIEW_INVALID)
                    }
                }
            }
            fork("rename to a title over 120 characters") {
                whenCommand(RenameView(stateRoot.id, "x".repeat(121)), appHeader(), ALICE) {
                    expectError<ViewStoreException> {
                        errorCode.assert().isEqualTo(ViewStoreErrorCodes.VIEW_INVALID)
                    }
                }
            }
            fork("rename from another application reads as not found") {
                whenCommand(RenameView(stateRoot.id, "Mine"), appHeader(OTHER_APP), ALICE) {
                    expectErrorType(NotFoundResourceException::class)
                }
            }
            fork("share transfers the owner to (shared)") {
                whenCommand(ChangeViewAudience(stateRoot.id, ViewAudience.SHARED), appHeader(operator = ALICE), ALICE) {
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
            fork("personal again keeps the owner") {
                whenCommand(
                    ChangeViewAudience(stateRoot.id, ViewAudience.PERSONAL),
                    appHeader(operator = ALICE),
                    ALICE
                ) {
                    expectNoError()
                    expectEventBody<ViewAudienceChanged> {
                        toOwnerId.assert().isEqualTo(ALICE)
                    }
                    expectStateAggregate {
                        ownerId.assert().isEqualTo(ALICE)
                    }
                }
            }
            fork("change audience from another application reads as not found") {
                whenCommand(
                    ChangeViewAudience(stateRoot.id, ViewAudience.SHARED),
                    appHeader(OTHER_APP, ALICE),
                    ALICE
                ) {
                    expectErrorType(NotFoundResourceException::class)
                }
            }
            fork("delete") {
                whenCommand(DeleteView(stateRoot.id), appHeader(), ALICE) {
                    expectNoError()
                    expectEventType(ViewDeleted::class)
                    expectStateAggregate {
                        deleted.assert().isTrue()
                    }
                    fork("a deleted view reads as gone") {
                        whenCommand(RenameView(stateRoot.id, "Again"), appHeader(), ALICE) {
                            expectErrorType(IllegalAccessDeletedAggregateException::class)
                        }
                    }
                    fork("recover in process") {
                        whenCommand(RecoverView(stateRoot.id), appHeader(), ALICE) {
                            expectNoError()
                            expectEventType(ViewRecovered::class)
                            expectStateAggregate {
                                deleted.assert().isFalse()
                            }
                        }
                    }
                    fork("recover from another application reads as not found") {
                        whenCommand(RecoverView(stateRoot.id), appHeader(OTHER_APP), ALICE) {
                            expectErrorType(NotFoundResourceException::class)
                        }
                    }
                }
            }
            fork("apply tags in process") {
                whenCommand(ApplyViewTags(stateRoot.id, mapOf("team" to listOf("a"))), appHeader(), ALICE) {
                    expectNoError()
                    expectEventType(ViewTagsApplied::class)
                    expectStateAggregate {
                        tags.assert().isEqualTo(mapOf("team" to listOf("a")))
                    }
                }
            }
            fork("apply tags from another application reads as not found") {
                whenCommand(ApplyViewTags(stateRoot.id, mapOf("team" to listOf("a"))), appHeader(OTHER_APP), ALICE) {
                    expectErrorType(NotFoundResourceException::class)
                }
            }
            fork("delete from another application reads as not found") {
                whenCommand(DeleteView(stateRoot.id), appHeader(OTHER_APP), ALICE) {
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
            fork("make it personal: the operator becomes the owner") {
                whenCommand(
                    ChangeViewAudience(stateRoot.id, ViewAudience.PERSONAL),
                    appHeader(operator = BOB),
                    SHARED_OWNER_ID
                ) {
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
            fork("make it personal without an operator") {
                whenCommand(ChangeViewAudience(stateRoot.id, ViewAudience.PERSONAL), appHeader(), SHARED_OWNER_ID) {
                    expectError<ViewStoreException> {
                        errorCode.assert().isEqualTo(ViewStoreErrorCodes.VIEW_OPERATOR_REQUIRED)
                    }
                }
            }
            fork("share again keeps the owner") {
                whenCommand(ChangeViewAudience(stateRoot.id, ViewAudience.SHARED), appHeader(), SHARED_OWNER_ID) {
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
                whenCommand(
                    ChangeViewAudience(stateRoot.id, ViewAudience.PERSONAL),
                    appHeader(operator = BOB),
                    SHARED_OWNER_ID
                ) {
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
                whenCommand(DeleteView(stateRoot.id), appHeader(), SHARED_OWNER_ID) {
                    expectNoError()
                    expectEventType(ViewDeleted::class)
                }
            }
        }
    }
    on {
        whenCommand(CreateView("orders", "Board", dashboardConfig("v1", "v2", "v1")), appHeader(), SHARED_OWNER_ID) {
            expectNoError()
            expectState {
                kind.assert().isEqualTo(ViewKind.DASHBOARD)
                references.assert().containsExactlyInAnyOrder("v1", "v2")
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
