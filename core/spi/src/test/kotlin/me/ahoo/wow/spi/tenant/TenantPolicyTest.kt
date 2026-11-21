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

package me.ahoo.wow.spi.tenant

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.aggregate.AggregateType
import org.junit.jupiter.api.Test

/** The one rule of a command's tenant ([TenantPolicy.tenantOf], #6556): every path decides it alike. */
class TenantPolicyTest {
    private val cart = AggregateType("shop", "cart")
    private val order = AggregateType("shop", "order")
    private val fixed = TenantPolicy.fixed(mapOf(cart to "shared"))

    @Test
    fun `a type of no fixed tenant is in the tenant named, else the acting one`() {
        fixed.tenantOf(order, null, "acme").assert().isEqualTo("acme")
        fixed.tenantOf(order, "globex", "acme").assert().isEqualTo("globex")
        fixed.tenantOf(order, "globex", null).assert().isEqualTo("globex")
        assertThrownBy<IllegalArgumentException> { fixed.tenantOf(order, null, null) }
            .hasMessage("A command of shop.order names no tenant and acts for none.")
    }

    @Test
    fun `a type of a fixed tenant is in it when the command names none, the acting one or the fixed one`() {
        fixed.tenantOf(cart, null, "acme").assert().isEqualTo("shared")
        // The acting tenant is no tenant named: a handler's request to the event's ref, in its own tenant.
        fixed.tenantOf(cart, "acme", "acme").assert().isEqualTo("shared")
        fixed.tenantOf(cart, "shared", "acme").assert().isEqualTo("shared")
        fixed.tenantOf(cart, "shared", null).assert().isEqualTo("shared")
    }

    @Test
    fun `a command naming another tenant than the type's fixed one is refused, not ignored nor rewritten`() {
        assertThrownBy<TenantConflictException> { fixed.tenantOf(cart, "globex", "acme") }
            .hasMessage("The tenant of shop.cart is fixed to shared; the request names globex.")
        // With no acting tenant known, the tenant it names is named, whatever it is.
        assertThrownBy<TenantConflictException> { fixed.tenantOf(cart, "acme", null) }
            .hasMessage("The tenant of shop.cart is fixed to shared; the request names acme.")
    }
}
