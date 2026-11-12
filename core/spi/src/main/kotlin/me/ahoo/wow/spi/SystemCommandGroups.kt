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

package me.ahoo.wow.spi

import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.ledger.Ledger
import me.ahoo.wow.api.lifecycle.Lifecycle
import me.ahoo.wow.api.schedule.ScheduleJobs
import me.ahoo.wow.api.schedule.Schedules
import me.ahoo.wow.spi.snapshot.SnapshotRebuilds

/**
 * The framework's system commands, each named by its system name (`wow.lifecycle.TransferOwner`), grouped by the
 * registry that owns its name and class, and the sets the framework reads them by, each named for what it means.
 *
 * Every place that takes system commands of more than one group takes one of these sets, so no two places can know
 * different commands by the same meaning. A set is two-way: [SystemCommandSet.classOf] a name, [SystemCommandSet.nameOf]
 * a class.
 */
@InternalWowApi
object SystemCommandGroups {
    /** The registries of the system commands: each command is in exactly one of them. */
    @InternalWowApi
    enum class Group(
        /** The group's commands, by their system names, as its owner lists them. */
        val commands: Map<String, Class<*>>,
    ) {
        /** An aggregate's lifecycle commands ([Lifecycle.COMMANDS]). */
        LIFECYCLE(Lifecycle.COMMANDS),

        /** An aggregate's schedule commands ([Schedules.COMMANDS]). */
        SCHEDULE(Schedules.COMMANDS),

        /** A context's schedule job commands, to its job aggregate ([ScheduleJobs.COMMANDS]). */
        JOB(ScheduleJobs.COMMANDS),

        /** An aggregate type's snapshot rebuild, to its context's rebuild aggregate ([SnapshotRebuilds.COMMANDS]). */
        REBUILD(SnapshotRebuilds.COMMANDS),

        /** The failure ledger's commands, which an owning service's intake runs ([Ledger.COMMANDS]). */
        LEDGER(Ledger.COMMANDS),
    }

    /**
     * The commands every aggregate decides, which its kernel decides on its metadata rather than its state: its
     * lifecycle and its schedule. A Manifest need not list them, and every model takes them.
     */
    @JvmField
    val DECIDED_BY_EVERY_AGGREGATE: SystemCommandSet = of(Group.LIFECYCLE, Group.SCHEDULE)

    /**
     * The commands a model of the framework decides: those every aggregate decides, and the job and rebuild commands
     * that the built-in job and rebuild aggregates decide. The ledger's are not among them: an owning service's intake
     * runs them, and no model decides them. A user's model may take none of these names.
     */
    @JvmField
    val DECIDED_BY_A_SYSTEM_MODEL: SystemCommandSet = of(Group.LIFECYCLE, Group.SCHEDULE, Group.JOB, Group.REBUILD)

    /**
     * The system commands a contract lists (`me.ahoo.wow.manifest.SystemCommands`): an aggregate's lifecycle and its
     * snapshot rebuild, and a context's job commands. A schedule's commands are not listed, so a client or a peer sends
     * none of them by their contract.
     */
    @JvmField
    val LISTED_BY_A_CONTRACT: SystemCommandSet = of(Group.LIFECYCLE, Group.JOB, Group.REBUILD)

    /**
     * The system commands a saga sends to another context by their system names: an aggregate's lifecycle and schedule
     * commands, and the ledger's, which the ops context's sagas send to the services that own the parked entries. A
     * saga sends no job or rebuild command: their addressing and authorization are the built-in aggregates', which no
     * saga is designed to reach.
     */
    @JvmField
    val SENT_BY_A_SAGA: SystemCommandSet = of(Group.LIFECYCLE, Group.SCHEDULE, Group.LEDGER)

    /** Every system command: each is decided by the framework somewhere, so none needs a user's context to decide it. */
    @JvmField
    val ALL: SystemCommandSet = of(*Group.entries.toTypedArray())

    private fun of(vararg groups: Group): SystemCommandSet = SystemCommandSet(groups.toSet())
}

/** System commands of some [groups], by their names and by their classes. */
@InternalWowApi
class SystemCommandSet internal constructor(
    /** The groups whose commands the set holds. */
    val groups: Set<SystemCommandGroups.Group>,
) {
    /** The commands, by their system names. */
    val commands: Map<String, Class<*>> = groups.flatMap { it.commands.entries }.associate { it.key to it.value }

    /** The system names of the commands. */
    val names: Set<String> get() = commands.keys

    /** The classes of the commands. */
    val classes: Set<Class<*>> = commands.values.toSet()

    private val byClass: Map<Class<*>, String> = commands.entries.associate { (name, type) -> type to name }

    /** The class of the command [name], or null when it is none of the set's. */
    fun classOf(name: String): Class<*>? = commands[name]

    /** The system name of [type], a command class, or null when it is none of the set's. */
    fun nameOf(type: Class<*>): String? = byClass[type]

    /** Whether the command [name] is one of the set's. */
    operator fun contains(name: String): Boolean = name in commands

    /** Whether [type] is the class of one of the set's commands. */
    operator fun contains(type: Class<*>): Boolean = type in byClass
}
