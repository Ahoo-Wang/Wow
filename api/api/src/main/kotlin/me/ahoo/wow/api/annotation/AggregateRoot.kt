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
)
