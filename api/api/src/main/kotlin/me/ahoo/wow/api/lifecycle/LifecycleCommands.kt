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

import me.ahoo.wow.api.annotation.Description
import me.ahoo.wow.api.annotation.InternalWowApi

/**
 * Marks the aggregate deleted ([AggregateDeleted]). A deleted aggregate refuses every command but [RestoreAggregate];
 * its events stay in the log.
 *
 * Fields may be added: construct one with [of]; the constructor is internal to Wow.
 */
@Description("Marks the aggregate deleted; its events stay in the log.")
class DeleteAggregate @InternalWowApi constructor() {
    override fun equals(other: Any?): Boolean = this === other || other is DeleteAggregate

    override fun hashCode(): Int = javaClass.hashCode()

    override fun toString(): String = "DeleteAggregate"

    companion object {
        /** A DeleteAggregate; parameters added later come last, with a default. */
        @JvmStatic
        fun of(): DeleteAggregate = DeleteAggregate()
    }
}

/**
 * Takes back the deletion of a deleted aggregate ([AggregateRestored]).
 *
 * Fields may be added: construct one with [of]; the constructor is internal to Wow.
 */
@Description("Takes back the deletion of a deleted aggregate.")
class RestoreAggregate @InternalWowApi constructor() {
    override fun equals(other: Any?): Boolean = this === other || other is RestoreAggregate

    override fun hashCode(): Int = javaClass.hashCode()

    override fun toString(): String = "RestoreAggregate"

    companion object {
        /** A RestoreAggregate; parameters added later come last, with a default. */
        @JvmStatic
        fun of(): RestoreAggregate = RestoreAggregate()
    }
}

/** Gives the aggregate to the owner [ownerId], the owner part of its scope; see [OwnerTransferred]. */
@Description("Gives the aggregate to the owner ownerId.")
class TransferOwner @InternalWowApi constructor(val ownerId: String) {
    init {
        require(ownerId.isNotBlank()) { "An owner id is not blank." }
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is TransferOwner && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "TransferOwner(ownerId=$ownerId)"

    private fun valueFields(): List<Any?> = listOf(ownerId)

    companion object {
        /** A TransferOwner of the given fields; parameters added later come last, with a default. */
        @JvmStatic
        fun of(ownerId: String): TransferOwner = TransferOwner(ownerId)
    }
}

/** Moves the aggregate to the space [spaceId], the space part of its scope; see [SpaceTransferred]. */
@Description("Moves the aggregate to the space spaceId.")
class TransferSpace @InternalWowApi constructor(val spaceId: String) {
    init {
        require(spaceId.isNotBlank()) { "A space id is not blank." }
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is TransferSpace && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "TransferSpace(spaceId=$spaceId)"

    private fun valueFields(): List<Any?> = listOf(spaceId)

    companion object {
        /** A TransferSpace of the given fields; parameters added later come last, with a default. */
        @JvmStatic
        fun of(spaceId: String): TransferSpace = TransferSpace(spaceId)
    }
}
