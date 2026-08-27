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

package me.ahoo.wow.eventsourcing.snapshot

import me.ahoo.test.asserts.assert
import me.ahoo.wow.serialization.JsonSerializer
import me.ahoo.wow.serialization.MessageRecords
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SnapshotJsonVersionTest {

    @Test
    fun `should return an integer snapshot version`() {
        val snapshotNode = JsonSerializer.createObjectNode()
            .put(MessageRecords.VERSION, 2)

        snapshotNode.requiredSnapshotVersion().assert().isEqualTo(2)
    }

    @Test
    fun `should reject a missing snapshot version`() {
        val snapshotNode = JsonSerializer.createObjectNode()

        val error = assertThrows<IllegalStateException> {
            snapshotNode.requiredSnapshotVersion()
        }

        error.message.assert().isEqualTo("Serialized Wow snapshot has no version.")
    }

    @Test
    fun `should reject a non-integer snapshot version`() {
        val snapshotNode = JsonSerializer.createObjectNode()
            .put(MessageRecords.VERSION, "2")

        val error = assertThrows<IllegalStateException> {
            snapshotNode.requiredSnapshotVersion()
        }

        error.message.assert().isEqualTo("Serialized Wow snapshot version must be an integer.")
    }
}
