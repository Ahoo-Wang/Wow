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

package me.ahoo.wow.tck.container

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test

class ContainerTestIdsTest {

    @Test
    fun `should create safe container name`() {
        val name = ContainerTestIds.nextName("Wow_Test")

        name.assert().startsWith("wow_test_")
        name.assert().isEqualTo(name.lowercase())
        val suffix = name.removePrefix("wow_test_")
        suffix.length.assert().isEqualTo(32)
        suffix.all { it in '0'..'9' || it in 'a'..'f' }.assert().isTrue()
    }

    @Test
    fun `should reject blank prefix`() {
        assertThrownBy<IllegalArgumentException> {
            ContainerTestIds.nextName(" ")
        }.hasMessage("prefix must not be blank.")
    }

    @Test
    fun `should reject unsafe prefix`() {
        assertThrownBy<IllegalArgumentException> {
            ContainerTestIds.nextName("_wow")
        }.hasMessage(
            "prefix must normalize to 1-31 lowercase letters, digits, or underscores and start with a letter.",
        )
    }

    @Test
    fun `should reject too long prefix`() {
        assertThrownBy<IllegalArgumentException> {
            ContainerTestIds.nextName("a".repeat(32))
        }.hasMessage(
            "prefix must normalize to 1-31 lowercase letters, digits, or underscores and start with a letter.",
        )
    }
}
