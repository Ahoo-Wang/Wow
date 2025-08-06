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
import me.ahoo.wow.command.wait.stage.WaitingForStage.Companion.extractWaitingForStage

const val COMMAND_WAIT_PREFIX = "command_wait_"
const val WAIT_COMMAND_ID = "${COMMAND_WAIT_PREFIX}id"
const val COMMAND_WAIT_ENDPOINT = "${COMMAND_WAIT_PREFIX}endpoint"

data class ExtractedWaitStrategy(
    override val endpoint: String,
    override val waitCommandId: String,
    val waitStrategy: WaitStrategy.Materialized
) : CommandWaitEndpoint, WaitCommandIdCapable

fun Header.extractCommandWaitId(): String? {
    return this[WAIT_COMMAND_ID]
}

fun Header.requireExtractWaitCommandId(): String {
    return requireNotNull(extractCommandWaitId()) {
        "$WAIT_COMMAND_ID is required!"
    }
}

fun Header.propagateWaitCommandId(commandId: String): Header {
    return with(WAIT_COMMAND_ID, commandId)
}

fun Header.extractCommandWaitEndpoint(): String? {
    return this[COMMAND_WAIT_ENDPOINT]
}

fun Header.propagateCommandWaitEndpoint(endpoint: String): Header {
    return with(COMMAND_WAIT_ENDPOINT, endpoint)
}

fun Header.extractWaitStrategy(): ExtractedWaitStrategy? {
    val waitCommandId = this.extractCommandWaitId() ?: return null
    val endpoint = this.extractCommandWaitEndpoint() ?: return null
    val waitStrategy = this.extractWaitingForStage() ?: return null
    return ExtractedWaitStrategy(endpoint, waitCommandId, waitStrategy)
}
