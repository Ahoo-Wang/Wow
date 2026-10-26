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

package me.ahoo.wow.client

import tools.jackson.databind.JsonNode

/**
 * A command named by its [type] rather than by its class (decision D257): a command to a peer described by its
 * Manifest alone (`wow.peers.<context>.contract: manifest`), which a console, a gateway or a tool calls
 * without the peer's api jar. [type] is its full type name, `context.aggregate.Command` (the aggregate's type and the
 * command's name in the peer's Manifest, or one of its aliases), and [body] its JSON, sent as is.
 *
 * A [CommandClient][me.ahoo.wow.api.client.CommandClient] sends it as any command, to the aggregate its ref names,
 * which must be of the type [type] names: the same caller, waits, retries and answers. The peer's Manifest
 * (`GET /wow/manifest`, read once a minute at most) must declare the command, or the send fails before any request.
 */
class NamedCommand(val type: String, val body: JsonNode) {
    init {
        require(type.split('.').size >= COMMAND_PARTS && type.split('.').none { it.isBlank() }) {
            "A named command's type is context.aggregate.Command, not $type."
        }
    }

    /** The command's name within its aggregate: the last part of [type]. */
    val name: String get() = type.substringAfterLast('.')

    /** The `context.aggregate` of the aggregate type [type] names. */
    val target: String get() = type.substringBeforeLast('.')

    override fun equals(other: Any?): Boolean =
        this === other || other is NamedCommand && type == other.type && body == other.body

    override fun hashCode(): Int = 31 * type.hashCode() + body.hashCode()

    override fun toString(): String = "NamedCommand(type=$type, body=$body)"

    private companion object {
        const val COMMAND_PARTS = 3
    }
}
