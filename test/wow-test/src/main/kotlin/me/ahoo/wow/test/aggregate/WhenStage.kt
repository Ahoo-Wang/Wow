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

package me.ahoo.wow.test.aggregate

import me.ahoo.wow.api.messaging.Header
import me.ahoo.wow.api.modeling.OwnerId
import me.ahoo.wow.api.modeling.SpaceId
import me.ahoo.wow.api.modeling.SpaceIdCapable
import me.ahoo.wow.command.SimpleServerCommandExchange
import me.ahoo.wow.command.toCommandMessage
import me.ahoo.wow.messaging.DefaultHeader
import me.ahoo.wow.test.validation.validate
import reactor.core.publisher.Mono

/**
 * Defines the stage for specifying commands to execute in aggregate testing.
 *
 * This interface provides methods to execute commands on aggregates that have been
 * set up in the Given stage, transitioning to the Expect stage for result validation.
 *
 * @param S the type of the aggregate state
 */
interface WhenStage<S : Any> {
    /**
     * Executes a command with full parameter control.
     *
     * @param command the command to execute
     * @param header optional command header (defaults to empty)
     * @param ownerId optional owner ID override (defaults to previously set owner)
     * @param spaceId optional space ID override
     * @return an ExpectStage for defining expectations on the results
     */
    fun whenCommand(
        command: Any,
        header: Header = DefaultHeader.empty(),
        ownerId: String = OwnerId.DEFAULT_OWNER_ID,
        spaceId: SpaceId = SpaceIdCapable.DEFAULT_SPACE_ID
    ): ExpectStage<S>

    fun whenCommand(
        command: Any,
    ): ExpectStage<S> {
        return whenCommand(command, DefaultHeader.empty(), OwnerId.DEFAULT_OWNER_ID, SpaceIdCapable.DEFAULT_SPACE_ID)
    }
}

/**
 * Runs a command on the aggregate of [runtime] once [prepare] has set up its history (given events or a given
 * state), through the production command pipeline (V6).
 *
 * The command body is validated first, as the command gateway does; a command that fails validation does not run.
 * Everything else (creation, existence, ownership, space, deletion, the command function and its result, the append
 * and the sourcing of the committed events) is the command kernel's.
 *
 * @param C the type of the command aggregate
 * @param S the type of the aggregate state
 * @param runtime where the aggregate's history lives and its commands run
 * @param ownerId the owner of the given history, used by a command that states none
 * @param spaceId the space of the given history, used by a command that states none
 * @param siblings keeps the commands that branch from the same given stage independent: each starts from the given
 *   history, not after its siblings
 * @param prepare sets up the history of the aggregate a command addresses (given events or state) before the command
 *   runs, on the runtime the command runs on
 */
internal class DefaultWhenStage<C : Any, S : Any>(
    private val runtime: AggregateTestRuntime<C, S>,
    private val ownerId: String,
    private val spaceId: SpaceId,
    private val siblings: SiblingHistory,
    private val prepare: (AggregateTestRuntime<C, S>) -> Mono<Void>
) : WhenStage<S> {

    override fun whenCommand(
        command: Any,
        header: Header,
        ownerId: String,
        spaceId: SpaceId
    ): ExpectStage<S> {
        val aggregateId = runtime.aggregateId
        val commandMessage = command.toCommandMessage(
            aggregateId = aggregateId.id,
            namedAggregate = aggregateId.namedAggregate,
            tenantId = aggregateId.tenantId,
            ownerId = ownerId.ifBlank { this.ownerId },
            spaceId = spaceId.ifBlank { this.spaceId },
            header = header,
        )
        // Sibling `whenCommand`s of one given stage are independent, see [SiblingHistory].
        val branch = siblings.branch(runtime.withAggregateId(commandMessage.aggregateId))
        val target = branch.runtime
        val preconditions = branch.start.then(Mono.defer { prepare(target) }).cache()
        val expectedResultMono = Mono.defer {
            preconditions.then(
                Mono.defer {
                    val exchange = SimpleServerCommandExchange(commandMessage)
                    try {
                        commandMessage.body.validate()
                    } catch (throwable: Throwable) {
                        return@defer target.rejected(exchange, throwable)
                    }
                    target.execute(commandMessage)
                },
            )
        }
        return DefaultExpectStage(runtime = target, expectedResultMono = expectedResultMono)
    }
}
