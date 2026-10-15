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

package me.ahoo.wow.api.annotation

import me.ahoo.wow.api.aggregate.Ownership
import me.ahoo.wow.api.lifecycle.ApplyTags
import me.ahoo.wow.api.lifecycle.DeleteAggregate
import me.ahoo.wow.api.lifecycle.RestoreAggregate
import me.ahoo.wow.api.lifecycle.TransferOwner
import me.ahoo.wow.api.lifecycle.TransferSpace
import java.lang.annotation.Inherited
import kotlin.reflect.KClass

/**
 * An aggregate root (V10, design 01 §3): the compiler turns it into an aggregate model, with its command functions
 * ([OnCommand] or named `onCommand`), its state's sourcing functions ([OnSourcing] or named `onSourcing`) and its
 * [AfterCommand] hooks. The aggregate is named by its [Name], or by its simple name in `snake_case`.
 *
 * [lifecycle] lists the lifecycle commands (`me.ahoo.wow.api.lifecycle`, design 01 §6) the aggregate handles, every
 * one by default; an empty list enables none (decision D91). The compiler rejects a class that is not a lifecycle
 * command, passes the list to the model's `lifecycle(...)` and writes it into the Manifest.
 *
 * The aggregate's state events are a stream its context publishes for other contexts (decision D156), as the
 * Manifest's `published` lists it: [published] false keeps the aggregate out of it; [publishState] false publishes
 * its events without the state after each commit, for a state too large to send (decision D140); [topic] names the
 * Kafka topic, `wow.<context>.<aggregate>.state` when empty. A topic is at most 249 characters of `a-z`, `A-Z`,
 * `0-9`, `.`, `_` and `-`, and neither `.` nor `..`.
 *
 * [ownership] says who the aggregate belongs to besides its tenant (decision D189): [Ownership.OWNER] by default, the
 * owner of the command that creates it; [Ownership.TENANT] for a resource every caller of the tenant reads, such as a
 * product, whose owner is not recorded and whose reads are not restricted by owner. The compiler passes it to the
 * model and writes it into the Manifest, where query admission reads it.
 *
 * [snapshot] false turns the aggregate's snapshots off (decisions D4, D195): no snapshot subscription writes them, the
 * Manifest marks them disabled and lists no snapshot query, and a wait has no snapshot stage for it: a caller that asks
 * for none waits until its command is committed, and a wait that asks for the snapshot is rejected at admission. Its
 * state is rebuilt from the log; keep its streams short.
 *
 * [stateRevision] is the revision of the state's structure as snapshots hold it (decision D172), 1 by default, as an
 * event's `@Revision`. Raise it when the snapshots already written no longer read as the state, or no longer hold
 * what the state now derives: an `evolve` or `@Upgrade` whose meaning changed, a field renamed or retyped, or a field
 * added that the history must compute. The service then rebuilds the stored snapshots from the log in place, in the
 * background, and the Manifest compatibility gate accepts the state schema change. An optional field added or a field
 * removed needs no new revision.
 *
 * [retiredEvents] lists the events the aggregate no longer has (decision D219, as 9.x's `DroppedEvent`): delete the
 * event's class and its sourcing function, and list its name here, so its history still loads. Each is the name the
 * log stores the event under, as the model named it (its `@Name`, or one of its `@Alias` names when the records hold
 * that one): a name without a dot is within the aggregate, `context.aggregate.Name` in the log; one with dots is the
 * full stored name. A record of a retired event is skipped, never read: rebuilding the state applies nothing for it,
 * though its stream still counts as a version, and a subscription passes over it. A record under a name that is
 * neither an event's nor retired still fails to read. The compiler rejects a blank name, one listed twice, and one
 * that is the name or a former name of one of the aggregate's events or commands.
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.ANNOTATION_CLASS)
@Inherited
@MustBeDocumented
annotation class AggregateRoot(
    val lifecycle: Array<KClass<*>> = [
        DeleteAggregate::class,
        RestoreAggregate::class,
        TransferOwner::class,
        TransferSpace::class,
        ApplyTags::class,
    ],
    val published: Boolean = true,
    val publishState: Boolean = true,
    val topic: String = "",
    val ownership: Ownership = Ownership.OWNER,
    val snapshot: Boolean = true,
    val stateRevision: Int = 1,
    val retiredEvents: Array<String> = [],
)
