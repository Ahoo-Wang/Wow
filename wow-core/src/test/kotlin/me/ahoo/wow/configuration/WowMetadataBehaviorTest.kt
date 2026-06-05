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

package me.ahoo.wow.configuration

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class WowMetadataBehaviorTest {

    @Test
    fun `constructor should reject duplicate bounded context aliases`() {
        val exception = assertThrows<IllegalStateException> {
            WowMetadata(
                contexts = mapOf(
                    "sales" to BoundedContext(alias = "commerce"),
                    "orders" to BoundedContext(alias = "commerce"),
                ),
            )
        }

        exception.message.assert().contains("alias[commerce] conflicts")
    }

    @Test
    fun `merge should combine contexts with the same key and add new contexts`() {
        val current = WowMetadata(
            mapOf("sales" to BoundedContext(scopes = linkedSetOf("me.ahoo.sales"))),
        )
        val other = WowMetadata(
            mapOf(
                "sales" to BoundedContext(scopes = linkedSetOf("me.ahoo.sales.order")),
                "billing" to BoundedContext(scopes = linkedSetOf("me.ahoo.billing")),
            ),
        )

        val merged = current.merge(other)

        merged.contexts.keys.assert().contains("sales", "billing")
        merged.contexts["sales"]!!.scopes.assert().contains("me.ahoo.sales", "me.ahoo.sales.order")
    }
}
