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

package me.ahoo.wow.api.event

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

class RevisionTest {

    @Test
    fun `should DEFAULT_REVISION be 0 dot 0 dot 1`() {
        DEFAULT_REVISION.assert().isEqualTo("0.0.1")
    }

    @Test
    fun `should default revision property return DEFAULT_REVISION`() {
        val revision = object : Revision {}
        revision.revision.assert().isEqualTo(DEFAULT_REVISION)
    }

    @Test
    fun `should custom revision property work`() {
        val revision = object : Revision {
            override val revision = "2.0.0"
        }
        revision.revision.assert().isEqualTo("2.0.0")
    }
}
