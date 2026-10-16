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

import me.ahoo.wow.api.annotation.InternalWowApi

/** The aggregate was deleted ([DeleteAggregate]). */
data object AggregateDeleted

/** The aggregate's deletion was taken back ([RestoreAggregate]). */
data object AggregateRestored

/**
 * The aggregate now belongs to the owner [ownerId] ([TransferOwner]).
 *
 * Fields may be added (decisions D59, D227): construct one with [of]; the constructor is internal to Wow.
 */
class OwnerTransferred @InternalWowApi constructor(val ownerId: String) {
    init {
        require(ownerId.isNotBlank()) { "An owner id is not blank." }
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is OwnerTransferred && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "OwnerTransferred(ownerId=$ownerId)"

    private fun valueFields(): List<Any?> = listOf(ownerId)

    companion object {
        /** An OwnerTransferred of the given fields; parameters added later come last, with a default. */
        @JvmStatic
        fun of(ownerId: String): OwnerTransferred = OwnerTransferred(ownerId)
    }
}

/**
 * The aggregate now sits in the space [spaceId] ([TransferSpace]).
 *
 * Fields may be added (decisions D59, D227): construct one with [of]; the constructor is internal to Wow.
 */
class SpaceTransferred @InternalWowApi constructor(val spaceId: String) {
    init {
        require(spaceId.isNotBlank()) { "A space id is not blank." }
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is SpaceTransferred && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "SpaceTransferred(spaceId=$spaceId)"

    private fun valueFields(): List<Any?> = listOf(spaceId)

    companion object {
        /** A SpaceTransferred of the given fields; parameters added later come last, with a default. */
        @JvmStatic
        fun of(spaceId: String): SpaceTransferred = SpaceTransferred(spaceId)
    }
}

/**
 * The aggregate's tags are now [tags] ([ApplyTags]).
 *
 * Fields may be added (decisions D59, D227): construct one with [of]; the constructor is internal to Wow.
 */
class TagsApplied @InternalWowApi constructor(val tags: Map<String, List<String>>) {
    init {
        require(tags.keys.none { it.isBlank() }) { "A tag needs a name." }
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is TagsApplied && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "TagsApplied(tags=$tags)"

    private fun valueFields(): List<Any?> = listOf(tags)

    companion object {
        /** A TagsApplied of the given fields; parameters added later come last, with a default. */
        @JvmStatic
        fun of(tags: Map<String, List<String>>): TagsApplied = TagsApplied(tags)
    }
}
