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

package me.ahoo.wow.runtime.admission

import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.lifecycle.Lifecycle
import me.ahoo.wow.api.schedule.ScheduleJobs
import me.ahoo.wow.api.schedule.Schedules
import me.ahoo.wow.kernel.CommandType
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.CommandManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.runtime.cluster.ClusterCodec
import me.ahoo.wow.runtime.command.CommandSender
import me.ahoo.wow.runtime.contract.ContractDirectory
import me.ahoo.wow.runtime.wait.CommandRelay
import me.ahoo.wow.spi.snapshot.SnapshotRebuilds
import me.ahoo.wow.spi.wait.WaitContext
/**
 * The commands of peer contexts that the [CommandGateway] admits: the aggregates of a
 * context another service serves, known from the contract its api jar carries ([contracts]). Such a
 * command is written to the peer's command topic, `wow.<context>.commands`, through the node's command sender, the path a saga's command
 * to another context takes, and the peer's intake executes it. Without one, the node has
 * no command transport and refuses them.
 *
 * A command's class is found from its contract's schema reference (`#/$defs/<binary name>`) on [classLoader]: the
 * peer's api jar, which carries the contract, carries the class too.
 */
@InternalWowApi
class PeerCommands(
    private val contracts: ContractDirectory,
    private val sender: CommandSender? = null,
    private val classLoader: ClassLoader = PeerCommands::class.java.classLoader,
) {
    /** Whether a command of a peer context can be sent: the node has a command transport. */
    val transport: Boolean get() = sender != null

    /** The contract of the aggregate [type] of a peer context; null for one of this service or of no contract. */
    fun aggregateOf(type: AggregateType): AggregateManifest? =
        type.takeUnless { contracts.isLocal(it.context) }?.let(contracts::aggregateOf)

    /** The aggregate types of the peer contexts whose contracts list [command], a command class. */
    fun aggregatesOf(command: Class<*>): List<AggregateType> =
        contracts.aggregatesOf(Manifest.schemaRef(command.name)).filterNot { contracts.isLocal(it.context) }

    /**
     * The command type of [aggregate], of [type], that [request] names: its name (`AddItem`), its full name
     * (`ctx.agg.AddItem`), one of its former names, or, for a request built from a command object, its class's binary
     * name. A mounted command is an edge's only and is never sent. Null when none matches.
     *
     * A system command, such as a lifecycle one, is named by its system name (`wow.lifecycle.TransferOwner`) and sent
     * under it, as a saga's is ([CommandSender.typeOf]); its schema is keyed by that name, not a class, so its class is
     * the one the framework names it for ([systemClassOf]).
     */
    fun resolve(type: AggregateType, aggregate: AggregateManifest, request: String): PeerCommand? {
        // A system command built from its object is named by its class: the framework names it by its system name.
        val command = SYSTEM_COMMANDS.entries.firstOrNull { it.value.name == request }?.key ?: request
        val schema = Manifest.schemaRef(command)
        val manifest = aggregate.commands.filterNot { it.mounted }.firstOrNull { it.names(type, command) }
            ?: aggregate.commands.filterNot { it.mounted }.firstOrNull { it.schema == schema }
            ?: return null
        return PeerCommand(
            if (manifest.system) manifest.name else "$type.${manifest.name}",
            if (manifest.system) systemClassOf(manifest.name) else classOf(manifest.schema),
            when (manifest.creation) {
                Creation.NONE -> Creation.NONE
                Creation.REQUIRED -> Creation.REQUIRED
                Creation.ALLOWED -> Creation.ALLOWED
            },
            manifest.idProperty,
            builtIn = manifest.system && manifest.name in BUILT_IN_COMMANDS,
        )
    }

    /**
     * Where a command of the command type [name] (`ctx.agg.Command`, or a system command's system name) to an aggregate
     * of [context] is written: that context's command topic; null without a transport.
     */
    fun relayOf(context: String, name: String): CommandRelay? {
        val commands = sender ?: return null
        return object : CommandRelay {
            override val topic: String = commands.topicOf(context)

            override suspend fun send(command: Command<*>, wait: WaitContext?) =
                commands.send(ClusterCodec.wire(name, command), wait)
        }
    }

    private fun CommandManifest.names(type: AggregateType, command: String): Boolean =
        (aliases + name).any { it == command || "$type.$it" == command || it == "$type.$command" }

    /** The class of the system command [name], as [CommandSender.typeOf] names its class; null for one it names not. */
    private fun systemClassOf(name: String): Class<*>? = SYSTEM_COMMANDS[name]

    private fun classOf(schema: String): Class<*>? = try {
        Class.forName(Manifest.schemaKey(schema), false, classLoader)
    } catch (_: ClassNotFoundException) {
        null
    } catch (_: LinkageError) {
        null
    }
}

/** The system commands a peer's aggregate takes by their names, as [CommandSender.typeOf] names them. */
private val SYSTEM_COMMANDS: Map<String, Class<*>> = Lifecycle.COMMANDS + Schedules.COMMANDS

/**
 * The system commands a contract lists for a context's built-in aggregates, its snapshot rebuild and its jobs, which
 * this node does not relay to a peer: their addressing and authorization are the peer's own routes'.
 */
private val BUILT_IN_COMMANDS: Set<String> = SnapshotRebuilds.COMMANDS.keys + ScheduleJobs.COMMANDS.keys

/**
 * A command type of a peer context's contract: its full [name] (`ctx.agg.Command`, or a system command's system name,
 * `wow.lifecycle.TransferOwner`), its class, when on the class path
 * ([type]), whether it creates its aggregate ([creation]), and the property of its body that holds its aggregate's id
 * ([idProperty]; null for one whose request names it).
 */
@InternalWowApi
class PeerCommand(
    val name: String,
    val type: Class<*>?,
    val creation: Creation,
    val idProperty: String? = null,
    /** Whether it is a command of the peer's built-in aggregates (its rebuild, its jobs), which this node does not relay. */
    val builtIn: Boolean = false,
) {
    /** The command type, for authorization and validation; null when its class is not on the class path. */
    val commandType: CommandType? = type?.let { CommandType(name, it) }
}
