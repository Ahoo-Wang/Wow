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

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test

class LifecycleTest {
    @Test
    fun `every lifecycle command and event has a name in the system namespace`() {
        Lifecycle.COMMANDS.keys.assert().containsExactly(
            "wow.lifecycle.DeleteAggregate",
            "wow.lifecycle.RestoreAggregate",
            "wow.lifecycle.TransferOwner",
            "wow.lifecycle.TransferSpace",
            "wow.lifecycle.ApplyTags",
        )
        Lifecycle.EVENTS.keys.assert().containsExactly(
            "wow.lifecycle.AggregateDeleted",
            "wow.lifecycle.AggregateRestored",
            "wow.lifecycle.OwnerTransferred",
            "wow.lifecycle.SpaceTransferred",
            "wow.lifecycle.TagsApplied",
        )
        (Lifecycle.COMMANDS + Lifecycle.EVENTS).forEach { (name, type) ->
            Lifecycle.nameOf(type).assert().isEqualTo(name)
        }
        Lifecycle.nameOf(String::class.java).assert().isNull()
    }

    @Test
    fun `the lifecycle values refuse blank ids and tag names`() {
        TransferOwner("bob").ownerId.assert().isEqualTo("bob")
        SpaceTransferred("space").spaceId.assert().isEqualTo("space")
        ApplyTags(mapOf("region" to listOf("eu"))).tags.assert().containsKey("region")
        assertThrownBy<IllegalArgumentException> { TransferOwner(" ") }
        assertThrownBy<IllegalArgumentException> { TransferSpace("") }
        assertThrownBy<IllegalArgumentException> { ApplyTags(mapOf(" " to emptyList())) }
        assertThrownBy<IllegalArgumentException> { OwnerTransferred("") }
        assertThrownBy<IllegalArgumentException> { SpaceTransferred(" ") }
        assertThrownBy<IllegalArgumentException> { TagsApplied(mapOf("" to emptyList())) }
    }
}
