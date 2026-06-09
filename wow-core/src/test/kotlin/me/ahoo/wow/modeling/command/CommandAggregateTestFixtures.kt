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

package me.ahoo.wow.modeling.command

import com.google.common.base.Preconditions
import me.ahoo.wow.api.Version
import me.ahoo.wow.api.abac.AbacTags
import me.ahoo.wow.api.abac.ApplyResourceTags
import me.ahoo.wow.api.annotation.AggregateId
import me.ahoo.wow.api.annotation.AggregateRoot
import me.ahoo.wow.api.annotation.AggregateVersion
import me.ahoo.wow.api.annotation.CreateAggregate
import me.ahoo.wow.api.annotation.OnError
import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.api.command.DeleteAggregate
import me.ahoo.wow.api.command.RecoverAggregate
import me.ahoo.wow.api.modeling.aware.VersionAware

class MockCommandAggregate(private val id: String) : VersionAware {
    private var state: String? = null
    private var otherState: String? = null
    override var version: Int = Version.UNINITIALIZED_VERSION

    fun id(): String = id

    fun state(): String? = state

    fun otherState(): String? = otherState

    @Suppress("UNUSED_PARAMETER")
    private fun onCommand(create: Create): StateChanged = StateChanged(create.id, create.state)

    private fun onCommand(changeState: CommandMessage<ChangeState>): StateChanged =
        StateChanged(changeState.id, changeState.body.state)

    private fun onCommand(changeState: ChangeStateGivenExpectedVersion): StateChanged =
        StateChanged(changeState.id, changeState.state)

    private fun onCommand(
        changeStateDependExternalService: ChangeStateDependExternalService,
        externalService: ExternalService
    ): OtherStateChanged {
        Preconditions.checkNotNull(externalService)
        return OtherStateChanged(
            changeStateDependExternalService.id,
            changeStateDependExternalService.otherState,
        )
    }

    private fun onSourcing(stateChanged: StateChanged) {
        state = stateChanged.state
    }

    private fun onSourcing(otherStateChanged: OtherStateChanged) {
        otherState = otherStateChanged.otherState
    }
}

@CreateAggregate
data class Create(@AggregateId val id: String, val state: String)

data class ChangeState(@AggregateId val id: String, val state: String)

data class ChangeStateGivenExpectedVersion(
    @AggregateId val id: String,
    val state: String,
    @AggregateVersion val version: Int
)

data class ChangeStateDependExternalService(
    @AggregateId val id: String,
    val otherState: String
)

data class StateChanged(@AggregateId val id: String, val state: String)

data class OtherStateChanged(@AggregateId val id: String, val otherState: String)

class ExternalService

@AggregateRoot
class CustomInternalCommandAggregate(private val id: String) : VersionAware {
    override var version: Int = Version.UNINITIALIZED_VERSION

    fun id(): String = id

    @Suppress("UNUSED_PARAMETER")
    private fun onCommand(command: CustomRecoverCommand): StateChanged =
        StateChanged(command.id, "recovered")

    @Suppress("UNUSED_PARAMETER")
    private fun onCommand(command: CustomDeleteCommand): StateChanged =
        StateChanged(command.id, "deleted")

    @Suppress("UNUSED_PARAMETER")
    private fun onCommand(command: CustomApplyResourceTagsCommand): StateChanged =
        StateChanged(command.id, "tags")
}

data class CustomRecoverCommand(@AggregateId val id: String) : RecoverAggregate

data class CustomDeleteCommand(@AggregateId val id: String) : DeleteAggregate

data class CustomApplyResourceTagsCommand(
    @AggregateId val id: String,
    override val tags: AbacTags = emptyMap()
) : ApplyResourceTags

data class UndefinedCommand(@AggregateId val id: String)

@AggregateRoot
class ErrorHandlingCommandAggregate(private val id: String) : VersionAware {
    override var version: Int = Version.UNINITIALIZED_VERSION

    fun id(): String = id

    @Suppress("UNUSED_PARAMETER")
    private fun onCommand(command: FailingCommand): StateChanged {
        error("boom")
    }

    @Suppress("UNUSED_PARAMETER")
    @OnError
    private fun onError(command: FailingCommand, throwable: Throwable) {
        handledErrorMessage = throwable.message
    }

    companion object {
        var handledErrorMessage: String? = null
            private set

        fun reset() {
            handledErrorMessage = null
        }
    }
}

@CreateAggregate
data class FailingCommand(@AggregateId val id: String)
