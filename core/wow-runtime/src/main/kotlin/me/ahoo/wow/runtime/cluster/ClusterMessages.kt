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

package me.ahoo.wow.runtime.cluster

import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.spi.wait.Origin
import me.ahoo.wow.spi.wait.WaitContext
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/**
 * A command in its wire form (design 08 §3): `{ type, id, requestId, ref, body, expectedVersion, scope, headers,
 * causation }`. [type] is the command type's model name, which the receiver reads [body] by; the wait plan does not
 * travel, only a wait context does.
 */
data class CommandWire(
    val type: String,
    val id: String,
    val requestId: String,
    val ref: AggregateRef,
    val body: JsonNode,
    val expectedVersion: Int? = null,
    val scope: Scope = Scope.NONE,
    val headers: Map<String, String> = emptyMap(),
    val causation: Causation? = null,
)

/** A wait context (design 04 §6.2) in its wire form. */
data class WaitWire(val waitId: String, val origin: Origin, val remaining: List<Hop>, val deadline: Long) {
    fun toContext(): WaitContext = WaitContext(waitId, origin, remaining, deadline)

    companion object {
        fun of(wait: WaitContext): WaitWire = WaitWire(wait.waitId, wait.origin, wait.remaining, wait.deadline)
    }
}

/**
 * The cluster channel's one message kind for now (design 02 §3): a node forwards [command] to the owner of its
 * partition and waits for the reply until [deadline] (epoch milliseconds). [wait] is the command's wait context; its
 * origin stays the node the command arrived at, so later hops signal that node directly (decision D65), never
 * through the channel.
 *
 * Every cluster message carries its format version [v] (design 08 §9). A reader ignores the fields it does not know,
 * so a node reads the messages of the next minor version (decision D48).
 */
data class ForwardCommand(
    val v: Int = VERSION,
    val command: CommandWire,
    val wait: WaitWire? = null,
    val deadline: Long,
) {
    companion object {
        const val VERSION: Int = 1
    }
}

/**
 * The owner's reply to a [ForwardCommand]: the [result] its execution reached at COMMITTED (a commit, a duplicate,
 * no change, a rejection or a failure; design 08 §7), followed by the later hops it [reached] at once (the next
 * HANDLED hop of a command that changed nothing, or of a duplicate whose subscription is past it, decision D63).
 * Hops reached later are signalled to the wait's origin.
 *
 * A node that does not take the command answers [refused] instead, with one of the [Refusal] codes; the sender then
 * executes the command itself (decision D71).
 */
data class ForwardReply(
    val v: Int = VERSION,
    val result: CommandResult? = null,
    val reached: List<CommandResult> = emptyList(),
    val refused: String? = null,
) {
    init {
        require((result == null) != (refused == null)) { "A forward reply has either a result or a refusal." }
    }

    companion object {
        const val VERSION: Int = 1

        fun refused(code: String): ForwardReply = ForwardReply(refused = code)
    }
}

/** Why a node refused a forwarded command. */
object Refusal {
    /** The node neither owns the command's partition nor is about to. */
    const val NOT_OWNER = "not-owner"

    /** The forward's deadline passed before the node took it. */
    const val EXPIRED = "expired"

    /** The node does not know the command's aggregate or command type, such as a node of an older version. */
    const val UNKNOWN_TYPE = "unknown-type"
}

/**
 * Reads and writes cluster messages as JSON (decision D26), with the Jackson modules on the class path (the Kotlin
 * module the kernel brings), reading leniently: unknown fields are ignored at every level (decision D48).
 */
object ClusterCodec {
    private val mapper: JsonMapper = JsonMapper.builder()
        .findAndAddModules()
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build()

    fun encode(message: ForwardCommand): ByteArray = mapper.writeValueAsBytes(message)

    fun encode(message: ForwardReply): ByteArray = mapper.writeValueAsBytes(message)

    fun decodeCommand(bytes: ByteArray): ForwardCommand = mapper.readValue(bytes, ForwardCommand::class.java)

    fun decodeReply(bytes: ByteArray): ForwardReply = mapper.readValue(bytes, ForwardReply::class.java)

    /** [command], a command of [model], in its wire form. */
    fun wire(model: AggregateModel<*>, command: Command<*>): CommandWire = CommandWire(
        type = model.commandType(command.body.javaClass).name,
        id = command.id,
        requestId = command.requestId,
        ref = command.ref,
        body = mapper.valueToTree(command.body),
        expectedVersion = command.expectedVersion,
        scope = command.scope,
        headers = command.headers,
        causation = command.causation,
    )

    /** The command [wire] holds, its body read as [model]'s command type of that name; null when it has none. */
    fun command(model: AggregateModel<*>, wire: CommandWire): Command<*>? {
        val type = model.findCommandType(wire.type) ?: return null
        return Command(
            id = wire.id,
            requestId = wire.requestId,
            ref = wire.ref,
            body = mapper.treeToValue(wire.body, type.type),
            expectedVersion = wire.expectedVersion,
            scope = wire.scope,
            headers = wire.headers,
            causation = wire.causation,
        )
    }
}
