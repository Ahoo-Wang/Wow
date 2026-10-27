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

package me.ahoo.wow.spi.json

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/** The shape of a state's JSON and its fingerprint (finding R5.A-19: the kernel and the Manifest's generator share it). */
class StateJsonTest {
    data class Account(val owner: String, val balance: Long, val tags: Set<Tag>)

    enum class Tag { VIP, FROZEN }

    data class Renamed(val holder: String, val balance: Long, val tags: Set<Tag>)

    @Test
    fun `a state's shape is its serialized properties, sorted, each described`() {
        StateJson.shape(Account::class.java).assert()
            .isEqualTo("{balance:long,owner:java.lang.String,tags:java.util.Set<enum(FROZEN,VIP)>}")
        StateJson.fingerprint(Account::class.java).assert().hasSize(64)
            .isEqualTo(StateJson.fingerprint(Account::class.java, StateJson.mapper))
        StateJson.fingerprint(Renamed::class.java).assert().isNotEqualTo(StateJson.fingerprint(Account::class.java))
    }

    @Test
    fun `a state's sets and maps read back in their written order`() {
        val tags = StateJson.mapper.readValue(
            """{"owner":"a","balance":1,"tags":["VIP","FROZEN"]}""",
            Account::class.java
        )
            .tags
        tags.toList().assert().containsExactly(Tag.VIP, Tag.FROZEN)
    }
}
