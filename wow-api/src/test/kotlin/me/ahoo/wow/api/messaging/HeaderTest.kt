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

class HeaderTest {
    @Test
    fun test() {
        val header = MockHeader()
        header.with("key1", "value1")
        header.with(mapOf("key2" to "value2"))
        header.withReadOnly()
        assertThat(header["key1"], equalTo("value1"))
        assertThat(header["key2"], equalTo("value2"))
        assertThat(header.isReadOnly, equalTo(true))
    }
}

class MockHeader(
    private val delegate: MutableMap<String, String> = mutableMapOf(),
    @Volatile
    override var isReadOnly: Boolean = false
) : Header,
    MutableMap<String, String> by delegate {
    override fun copy(): Header {
        return this
    }

    override fun withReadOnly(): Header {
        isReadOnly = true
        return this
    }
}
