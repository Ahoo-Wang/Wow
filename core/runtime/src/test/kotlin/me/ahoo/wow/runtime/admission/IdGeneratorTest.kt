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

package me.ahoo.wow.runtime.admission

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.util.UUID

class IdGeneratorTest {
    @Test
    fun `UUIDv7 ids carry the version, the variant and the millisecond first`() {
        val now = 0x0190_F2A3_4B5CL
        val ids = UuidV7Generator({ now }).let { generator -> (1..1_000).map { UUID.fromString(generator.next()) } }
        ids.forEach {
            it.version().assert().isEqualTo(7)
            it.variant().assert().isEqualTo(2)
            (it.mostSignificantBits ushr 16).assert().isEqualTo(now)
        }
        ids.toSet().size.assert().isEqualTo(ids.size)
    }

    @Test
    fun `UUIDv7 ids sort by the millisecond they were made in`() {
        var now = 1_700_000_000_000L
        val generator = UuidV7Generator({ now++ })
        val ids = (1..100).map { generator.next() }
        ids.sorted().assert().isEqualTo(ids)
        UUID.fromString(IdGenerator.UUID_V7.next()).version().assert().isEqualTo(7)
    }
}
