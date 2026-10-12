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

package me.ahoo.wow.api.lifecycle

/**
 * The lifecycle of every aggregate (design 01 §6): system commands the kernel decides for any aggregate model without
 * user code, and the system events they produce. They change the aggregate's metadata, not its state: the deleted
 * mark, the scope (decision D56: the owner and the space change only through them) and the tags.
 *
 * Their names sit in the system namespace [NAMESPACE], so a model that gives one of its own types such a name fails
 * to build instead of colliding.
 */
object Lifecycle {
    /** The namespace of the lifecycle commands' and events' names. */
    const val NAMESPACE = "wow.lifecycle"

    const val DELETE_AGGREGATE = "$NAMESPACE.DeleteAggregate"
    const val RESTORE_AGGREGATE = "$NAMESPACE.RestoreAggregate"
    const val TRANSFER_OWNER = "$NAMESPACE.TransferOwner"
    const val TRANSFER_SPACE = "$NAMESPACE.TransferSpace"
    const val APPLY_TAGS = "$NAMESPACE.ApplyTags"

    const val AGGREGATE_DELETED = "$NAMESPACE.AggregateDeleted"
    const val AGGREGATE_RESTORED = "$NAMESPACE.AggregateRestored"
    const val OWNER_TRANSFERRED = "$NAMESPACE.OwnerTransferred"
    const val SPACE_TRANSFERRED = "$NAMESPACE.SpaceTransferred"
    const val TAGS_APPLIED = "$NAMESPACE.TagsApplied"

    /** The lifecycle commands, by their names. */
    @JvmField
    val COMMANDS: Map<String, Class<*>> = mapOf(
        DELETE_AGGREGATE to DeleteAggregate::class.java,
        RESTORE_AGGREGATE to RestoreAggregate::class.java,
        TRANSFER_OWNER to TransferOwner::class.java,
        TRANSFER_SPACE to TransferSpace::class.java,
        APPLY_TAGS to ApplyTags::class.java,
    )

    /** The lifecycle events, by their names. */
    @JvmField
    val EVENTS: Map<String, Class<*>> = mapOf(
        AGGREGATE_DELETED to AggregateDeleted::class.java,
        AGGREGATE_RESTORED to AggregateRestored::class.java,
        OWNER_TRANSFERRED to OwnerTransferred::class.java,
        SPACE_TRANSFERRED to SpaceTransferred::class.java,
        TAGS_APPLIED to TagsApplied::class.java,
    )

    private val NAMES: Map<Class<*>, String> = (COMMANDS + EVENTS).entries.associate { (name, type) -> type to name }

    /** The system name of [type], a lifecycle command or event, or null when it is neither. */
    @JvmStatic
    fun nameOf(type: Class<*>): String? = NAMES[type]
}
