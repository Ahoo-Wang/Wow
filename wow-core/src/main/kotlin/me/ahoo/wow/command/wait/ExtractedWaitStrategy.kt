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

package me.ahoo.wow.command.wait

import me.ahoo.wow.api.messaging.Header
import me.ahoo.wow.api.messaging.Message
import me.ahoo.wow.api.messaging.function.NamedFunctionInfoData
import me.ahoo.wow.command.wait.chain.SimpleWaitingChain.Companion.extractSimpleWaitingChain
import me.ahoo.wow.command.wait.stage.WaitingForStage.Companion.extractWaitingForStage
import me.ahoo.wow.infra.ifNotBlank
import me.ahoo.wow.messaging.propagation.MessagePropagator

/**
 * Prefix for all command wait header keys.
 */
const val COMMAND_WAIT_PREFIX = "command_wait_"

/**
 * Header key for storing the wait command ID.
 */
const val WAIT_COMMAND_ID = "${COMMAND_WAIT_PREFIX}id"

/**
 * Header key for storing the command wait endpoint.
 */
const val COMMAND_WAIT_ENDPOINT = "${COMMAND_WAIT_PREFIX}endpoint"

/**
 * Header key for storing the command wait stage.
 */
const val COMMAND_WAIT_STAGE = "${COMMAND_WAIT_PREFIX}stage"

/**
 * Header key for storing the command wait context name.
 */
const val COMMAND_WAIT_CONTEXT = "${COMMAND_WAIT_PREFIX}context"

/**
 * Header key for storing the command wait processor name.
 */
const val COMMAND_WAIT_PROCESSOR = "${COMMAND_WAIT_PREFIX}processor"

/**
 * Header key for storing the command wait function name.
 */
const val COMMAND_WAIT_FUNCTION = "${COMMAND_WAIT_PREFIX}function"

/**
 * Data class representing an extracted wait strategy with its associated metadata.
 * Combines the wait strategy implementation with endpoint and command ID information
 * needed for message propagation and notification.
 *
 * @param endpoint The endpoint address for wait notifications.
 * @param waitCommandId The unique identifier for the wait command.
 * @param waitStrategy The materialized wait strategy implementation.
 */
data class ExtractedWaitStrategy(
    override val endpoint: String,
    override val waitCommandId: String,
    val waitStrategy: WaitStrategy.Materialized
) : CommandWaitEndpoint,
    WaitCommandIdCapable,
    MessagePropagator {
    override fun propagate(
        header: Header,
        upstream: Message<*, *>
    ) {
        if (waitStrategy.shouldPropagate(upstream)) {
            header
                .propagateWaitCommandId(waitCommandId)
                .propagateCommandWaitEndpoint(endpoint)
            waitStrategy.propagate(header, upstream)
        }
    }
}

/**
 * Extracts the command wait ID from the message header.
 *
 * @return The wait command ID if present, null otherwise.
 */
fun Header.extractCommandWaitId(): String? = this[WAIT_COMMAND_ID]

/**
 * Extracts the command wait ID from the message header, throwing an exception if not present.
 *
 * @return The wait command ID.
 * @throws IllegalArgumentException if the wait command ID is not found in the header.
 */
fun Header.requireExtractWaitCommandId(): String =
    requireNotNull(extractCommandWaitId()) {
        "$WAIT_COMMAND_ID is required!"
    }

/**
 * Adds the wait command ID to the message header for propagation.
 *
 * @param commandId The wait command ID to propagate.
 * @return A new Header instance with the wait command ID added.
 */
fun Header.propagateWaitCommandId(commandId: String): Header = with(WAIT_COMMAND_ID, commandId)

/**
 * Extracts the command wait endpoint from the message header.
 *
 * @return The wait endpoint if present, null otherwise.
 */
fun Header.extractCommandWaitEndpoint(): String? = this[COMMAND_WAIT_ENDPOINT]

/**
 * Extracts the command wait endpoint from the message header, throwing an exception if not present.
 *
 * @return The wait endpoint.
 * @throws IllegalArgumentException if the wait endpoint is not found in the header.
 */
fun Header.requireExtractCommandWaitEndpoint(): String =
    requireNotNull(this[COMMAND_WAIT_ENDPOINT]) {
        "$COMMAND_WAIT_ENDPOINT is required!"
    }

/**
 * Adds the command wait endpoint to the message header for propagation.
 *
 * @param endpoint The wait endpoint to propagate.
 * @return A new Header instance with the wait endpoint added.
 */
fun Header.propagateCommandWaitEndpoint(endpoint: String): Header = with(COMMAND_WAIT_ENDPOINT, endpoint)

/**
 * Extracts a complete wait strategy from the message header.
 * Attempts to extract both simple waiting chain and waiting for stage strategies.
 *
 * @return An ExtractedWaitStrategy if all required components are found, null otherwise.
 */
fun Header.extractWaitStrategy(): ExtractedWaitStrategy? {
    val waitCommandId = this.extractCommandWaitId() ?: return null
    val endpoint = this.extractCommandWaitEndpoint() ?: return null
    val waitStrategy = this.extractSimpleWaitingChain() ?: this.extractWaitingForStage() ?: return null
    return ExtractedWaitStrategy(endpoint, waitCommandId, waitStrategy)
}

/**
 * Extracts the waiting stage from the message header.
 *
 * @return The CommandStage if present and valid, null otherwise.
 */
fun Header.extractWaitingStage(): CommandStage? {
    val stage = this[COMMAND_WAIT_STAGE] ?: return null
    return CommandStage.valueOf(stage)
}

/**
 * Adds the waiting stage to the message header for propagation.
 *
 * @param stage The command stage to propagate.
 * @return A new Header instance with the waiting stage added.
 */
fun Header.propagateWaitingStage(stage: CommandStage): Header = with(COMMAND_WAIT_STAGE, stage.name)

/**
 * Extracts wait function information from the message header.
 *
 * @return A NamedFunctionInfoData containing the extracted function criteria.
 */
fun Header.extractWaitFunction(): NamedFunctionInfoData {
    val context = this[COMMAND_WAIT_CONTEXT].orEmpty()
    val processor = this[COMMAND_WAIT_PROCESSOR].orEmpty()
    val function = this[COMMAND_WAIT_FUNCTION].orEmpty()
    return NamedFunctionInfoData(contextName = context, processorName = processor, name = function)
}

/**
 * Adds wait function information to the message header for propagation.
 *
 * @param function The function information to propagate, or null to skip.
 * @return A new Header instance with function information added if provided.
 */
fun Header.propagateWaitFunction(function: NamedFunctionInfoData?): Header {
    val function = function ?: return this
    function.contextName.ifNotBlank {
        with(COMMAND_WAIT_CONTEXT, it)
    }
    function.processorName.ifNotBlank {
        with(COMMAND_WAIT_PROCESSOR, it)
    }
    function.name.ifNotBlank {
        with(COMMAND_WAIT_FUNCTION, it)
    }
    return this
}
