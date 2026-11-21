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

package me.ahoo.wow.compiler.model.aggregate

import me.ahoo.wow.api.aggregate.AggregateIdSource
import me.ahoo.wow.api.aggregate.Ownership
import me.ahoo.wow.api.lifecycle.AggregateRestored
import me.ahoo.wow.api.lifecycle.OwnerTransferred
import me.ahoo.wow.compiler.model.symbol.ModelNames.SYSTEM_EVENTS

/**
 * The checks of a command that changes its aggregate's lifecycle by producing the lifecycle event beside its own
 * events, on the events its function yields:
 *
 * - only a command decided on a deleted aggregate (`@OnCommand(onDeleted = true)`) produces `AggregateRestored`: no
 *   other reaches a deleted aggregate;
 * - such a command produces `AggregateRestored` and the aggregate's own events alone: a deleted aggregate's owner,
 *   space or schedules change once it is restored;
 * - an aggregate whose owner is fixed, of its tenant ([Ownership.TENANT]) or its id its owner's, transfers no owner:
 *   none of its decide functions or after hooks yields `OwnerTransferred`. One whose events are not known here is
 *   held to it by the kernel, which fails the command.
 *
 * `@AggregateRoot(lifecycle)` narrows the lifecycle commands the kernel decides, not the events a command composes: a
 * model that disables `DeleteAggregate` for its own `CloseCart` still deletes by it.
 */
internal object LifecycleCompositions {
    private val RESTORED: String = AggregateRestored::class.java.canonicalName
    private val OWNER_TRANSFERRED: String = OwnerTransferred::class.java.canonicalName

    fun problems(
        commands: List<CommandBinding>,
        afters: List<AfterBinding>,
        ownership: String,
        idSource: String,
    ): List<String> = commands.flatMap { command ->
        val yields = command.yields.orEmpty()
        val subject = "${command.function}(${command.command})"
        buildList {
            if (RESTORED in yields && !command.onDeleted) {
                add(
                    "$subject produces AggregateRestored, but is not decided on a deleted aggregate: mark it " +
                        "@OnCommand(onDeleted = true)",
                )
            }
            if (command.onDeleted) {
                yields.filter { it in SYSTEM_EVENTS && it != RESTORED }.forEach {
                    add(
                        "$subject is decided on a deleted aggregate (onDeleted) and produces " +
                            "${it.substringAfterLast('.')}: such a command produces AggregateRestored and the " +
                            "aggregate's own events alone",
                    )
                }
            }
            fixedOwner(subject, yields, ownership, idSource)?.let(::add)
        }
    } + afters.mapNotNull { after ->
        fixedOwner("@AfterCommand ${after.function}", after.yields.orEmpty(), ownership, idSource)
    }

    /** The problem of [subject], which yields [yields], when it transfers the owner of an aggregate whose owner is fixed. */
    private fun fixedOwner(subject: String, yields: List<String>, ownership: String, idSource: String): String? = when {
        OWNER_TRANSFERRED !in yields -> null
        ownership == Ownership.TENANT.name ->
            "$subject produces OwnerTransferred, but the aggregate belongs to its tenant: it has no owner"
        idSource == AggregateIdSource.OWNER.name ->
            "$subject produces OwnerTransferred, but the aggregate's id is its owner's: its owner is fixed"
        else -> null
    }
}
