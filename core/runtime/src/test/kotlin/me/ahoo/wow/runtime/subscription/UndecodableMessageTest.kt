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
package me.ahoo.wow.runtime.subscription

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import org.junit.jupiter.api.Test

/** Which aggregate an undecodable message's key names: the ref whose text the key is, or none. */
class UndecodableMessageTest {
    @Test
    fun `a key names the aggregate whose text it is, and no key or another text names none`() {
        val ref = AggregateType("sales", "order").ref("a/b", "o-1")
        UndecodableMessage.refOf(ref.toString()).assert().isEqualTo(ref)
        UndecodableMessage.refOf(null).assert().isNull()
        UndecodableMessage.refOf("o-1").assert().isNull()
    }
}
