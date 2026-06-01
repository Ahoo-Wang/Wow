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

package me.ahoo.wow.api.modeling

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.modeling.OwnerId.Companion.orDefaultOwnerId
import org.junit.jupiter.api.Test

class OwnerIdTest {

    @Test
    fun `should DEFAULT_OWNER_ID be empty string`() {
        OwnerId.DEFAULT_OWNER_ID.assert().isEmpty()
    }

    @Test
    fun `should orDefaultOwnerId return string when not null`() {
        val value: String? = "owner-123"
        value.orDefaultOwnerId().assert().isEqualTo("owner-123")
    }

    @Test
    fun `should orDefaultOwnerId return DEFAULT_OWNER_ID when null`() {
        val value: String? = null
        value.orDefaultOwnerId().assert().isEqualTo(OwnerId.DEFAULT_OWNER_ID)
    }
}
