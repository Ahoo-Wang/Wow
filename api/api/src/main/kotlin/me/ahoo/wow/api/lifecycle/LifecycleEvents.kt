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

/** The aggregate was deleted ([DeleteAggregate]). */
data object AggregateDeleted

/** The aggregate's deletion was taken back ([RestoreAggregate]). */
data object AggregateRestored

/** The aggregate now belongs to the owner [ownerId] ([TransferOwner]). */
data class OwnerTransferred(val ownerId: String) {
    init {
        require(ownerId.isNotBlank()) { "An owner id is not blank." }
    }
}

/** The aggregate now sits in the space [spaceId] ([TransferSpace]). */
data class SpaceTransferred(val spaceId: String) {
    init {
        require(spaceId.isNotBlank()) { "A space id is not blank." }
    }
}

/** The aggregate's tags are now [tags] ([ApplyTags]). */
data class TagsApplied(val tags: Map<String, List<String>>) {
    init {
        require(tags.keys.none { it.isBlank() }) { "A tag needs a name." }
    }
}
