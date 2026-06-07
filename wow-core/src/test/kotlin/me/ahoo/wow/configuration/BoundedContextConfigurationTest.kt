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

internal class BoundedContextConfigurationTest {

    @Test
    fun `merge should preserve first alias and description and merge aggregate definitions`() {
        val current = BoundedContext(
            alias = "sales",
            description = "Sales context",
            scopes = linkedSetOf("me.ahoo.sales"),
            aggregates = mapOf("Order" to Aggregate(scopes = linkedSetOf("me.ahoo.sales.order"))),
        )
        val other = BoundedContext(
            alias = null,
            description = "Other description",
            scopes = linkedSetOf("me.ahoo.sales.extra"),
            aggregates = mapOf("Order" to Aggregate(commands = linkedSetOf("me.ahoo.sales.order.command"))),
        )

        val merged = current.merge(other)

        merged.alias.assert().isEqualTo("sales")
        merged.description.assert().isEqualTo("Sales context")
        merged.scopes.assert().contains("me.ahoo.sales", "me.ahoo.sales.extra")
        merged.aggregates["Order"]!!.scopes.assert().contains("me.ahoo.sales.order")
        merged.aggregates["Order"]!!.commands.assert().contains("me.ahoo.sales.order.command")
    }

    @Test
    fun `merge should reject conflicting non blank aliases`() {
        val exception = assertThrows<IllegalStateException> {
            BoundedContext(alias = "sales").merge(BoundedContext(alias = "billing"))
        }

        exception.message.assert().contains("conflicts")
    }
}
