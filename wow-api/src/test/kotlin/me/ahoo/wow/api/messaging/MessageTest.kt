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

package me.ahoo.wow.api.messaging

import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test

class MessageTest {
    @Test
    fun test() {
        val message = MockNamedMessage(
            id = "id",
            header = MockHeader(),
            body = "body",
            createTime = 0,
            name = "name",
            contextName = "contextName"
        )
        message.withHeader("key1", "value1")
        message.withHeader(mapOf("key2" to "value2"))
        message.withReadOnly()
        assertThat(message.header["key1"], equalTo("value1"))
        assertThat(message.header["key2"], equalTo("value2"))
        assertThat(message.isReadOnly, equalTo(true))
    }
}

class MockNamedMessage<T>(
    override val id: String,
    override val header: Header,
    override val body: T,
    override val createTime: Long,
    override val name: String,
    override val contextName: String
) : NamedMessage<MockNamedMessage<T>, T>
