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

/**
 * Marks the aggregate deleted ([AggregateDeleted]). A deleted aggregate refuses every command but [RestoreAggregate];
 * its events stay in the log.
 */
@Description("Marks the aggregate deleted; its events stay in the log.")
data object DeleteAggregate

/** Takes back the deletion of a deleted aggregate ([AggregateRestored]). */
@Description("Takes back the deletion of a deleted aggregate.")
data object RestoreAggregate

/** Gives the aggregate to the owner [ownerId], the owner part of its scope (decision D56); see [OwnerTransferred]. */
@Description("Gives the aggregate to the owner ownerId.")
data class TransferOwner(val ownerId: String) {
    init {
        require(ownerId.isNotBlank()) { "An owner id is not blank." }
    }
}

/** Moves the aggregate to the space [spaceId], the space part of its scope (decision D56); see [SpaceTransferred]. */
@Description("Moves the aggregate to the space spaceId.")
data class TransferSpace(val spaceId: String) {
    init {
        require(spaceId.isNotBlank()) { "A space id is not blank." }
    }
}

/**
 * Sets the aggregate's tags to [tags], replacing the ones it had: each tag name with its values, the attributes
 * query authorization (ABAC) reads; see [TagsApplied]. Empty [tags] clear them.
 *
 * The keys the aggregate's state derives (`@Tags`, decision D220) are not the command's to set: they override the
 * applied ones. The command is a no-op when the tags it would leave are the aggregate's already.
 */
@Description("Sets the aggregate's tags, replacing the ones it had; empty tags clear them.")
data class ApplyTags(val tags: Map<String, List<String>>) {
    init {
        require(tags.keys.none { it.isBlank() }) { "A tag needs a name." }
    }
}
