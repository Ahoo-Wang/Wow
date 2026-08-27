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
import me.ahoo.wow.api.messaging.function.NamedFunctionInfoData
import me.ahoo.wow.infra.ifNotBlank

const val COMMAND_WAIT_PREFIX = "command_wait_"
const val WAIT_COMMAND_ID = "${COMMAND_WAIT_PREFIX}id"
const val COMMAND_WAIT_ENDPOINT = "${COMMAND_WAIT_PREFIX}endpoint"
const val COMMAND_WAIT_STAGE = "${COMMAND_WAIT_PREFIX}stage"
const val COMMAND_WAIT_CONTEXT = "${COMMAND_WAIT_PREFIX}context"
const val COMMAND_WAIT_PROCESSOR = "${COMMAND_WAIT_PREFIX}processor"
const val COMMAND_WAIT_FUNCTION = "${COMMAND_WAIT_PREFIX}function"

fun Header.extractCommandWaitId(): String? = this[WAIT_COMMAND_ID]

fun Header.requireExtractWaitCommandId(): String =
    requireNotNull(extractCommandWaitId()) {
        "$WAIT_COMMAND_ID is required!"
    }

fun Header.propagateWaitCommandId(commandId: String): Header = with(WAIT_COMMAND_ID, commandId)

fun Header.extractCommandWaitEndpoint(): String? = this[COMMAND_WAIT_ENDPOINT]

fun Header.requireExtractCommandWaitEndpoint(): String =
    requireNotNull(this[COMMAND_WAIT_ENDPOINT]) {
        "$COMMAND_WAIT_ENDPOINT is required!"
    }

fun Header.propagateCommandWaitEndpoint(endpoint: String): Header = with(COMMAND_WAIT_ENDPOINT, endpoint)

fun Header.extractWaitingStage(): CommandStage? {
    val stage = this[COMMAND_WAIT_STAGE] ?: return null
    return CommandStage.valueOf(stage)
}

fun Header.propagateWaitingStage(stage: CommandStage): Header = with(COMMAND_WAIT_STAGE, stage.name)

fun Header.extractWaitFunction(): NamedFunctionInfoData {
    val context = this[COMMAND_WAIT_CONTEXT].orEmpty()
    val processor = this[COMMAND_WAIT_PROCESSOR].orEmpty()
    val function = this[COMMAND_WAIT_FUNCTION].orEmpty()
    return NamedFunctionInfoData(contextName = context, processorName = processor, name = function)
}

fun Header.propagateWaitFunction(function: NamedFunctionInfoData?): Header {
    val waitFunction = function ?: return this
    waitFunction.contextName.ifNotBlank {
        with(COMMAND_WAIT_CONTEXT, it)
    }
    waitFunction.processorName.ifNotBlank {
        with(COMMAND_WAIT_PROCESSOR, it)
    }
    waitFunction.name.ifNotBlank {
        with(COMMAND_WAIT_FUNCTION, it)
    }
    return this
}
