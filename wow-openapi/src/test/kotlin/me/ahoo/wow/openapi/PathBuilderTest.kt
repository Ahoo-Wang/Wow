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

package me.ahoo.wow.openapi

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

internal class PathBuilderTest {

    @Test
    fun `should append non-blank segment with separator`() {
        val path = PathBuilder().append("orders").build()
        path.assert().isEqualTo("/orders")
    }

    @Test
    fun `should append segment starting with separator as-is`() {
        val path = PathBuilder().append("/orders").build()
        path.assert().isEqualTo("/orders")
    }

    @Test
    fun `should skip blank segment`() {
        val path = PathBuilder().append("").append("orders").build()
        path.assert().isEqualTo("/orders")
    }

    @Test
    fun `should build empty path when no segments appended`() {
        val path = PathBuilder().build()
        path.assert().isEqualTo("")
    }

    @Test
    fun `should append multiple segments`() {
        val path = PathBuilder()
            .append("orders")
            .append("{id}")
            .build()
        path.assert().isEqualTo("/orders/{id}")
    }
}
