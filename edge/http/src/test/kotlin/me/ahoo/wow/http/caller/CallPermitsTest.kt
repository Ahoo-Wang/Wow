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

package me.ahoo.wow.http.caller

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.lifecycle.Lifecycle
import me.ahoo.wow.api.schedule.ScheduleJobs
import me.ahoo.wow.spi.snapshot.SnapshotRebuilds
import org.junit.jupiter.api.Test

/** The commands a service may call on the internal port (decision D226, review A3-2). */
class CallPermitsTest {
    private val basket = Caller(subject = "service:basket-service", service = true)
    private val principal = Caller(subject = "basket-principal", service = true)

    private fun CallPermits.allows(caller: Caller, command: String): Boolean =
        refusal(caller, "shop", "cart", command) == null

    @Test
    fun `a service without commands calls the domain commands alone`() {
        val permits = CallPermits(emptyMap())
        permits.allows(basket, "AddItem").assert().isTrue()
        listOf(
            Lifecycle.DELETE_AGGREGATE,
            Lifecycle.RESTORE_AGGREGATE,
            Lifecycle.TRANSFER_OWNER,
            Lifecycle.TRANSFER_SPACE,
            Lifecycle.APPLY_TAGS,
            SnapshotRebuilds.REBUILD,
            ScheduleJobs.ENABLE,
            ScheduleJobs.DISABLE,
        ).forEach { permits.allows(basket, it).assert().describedAs(it).isFalse() }
        permits.refusal(basket, "shop", "cart", Lifecycle.DELETE_AGGREGATE)!!.assert()
            .contains("shop.cart.wow.lifecycle.DeleteAggregate")
            .contains("wow.http.services.basket-service.commands")
    }

    @Test
    fun `a system command is opened by an entry naming the system namespace alone`() {
        CallPermits(mapOf("basket-service" to listOf("*", "shop.*"))).allows(basket, Lifecycle.DELETE_AGGREGATE)
            .assert().isFalse()
        val exact = CallPermits(mapOf("basket-service" to listOf("wow.lifecycle.DeleteAggregate")))
        exact.allows(basket, Lifecycle.DELETE_AGGREGATE).assert().isTrue()
        exact.allows(basket, Lifecycle.RESTORE_AGGREGATE).assert().isFalse()
        exact.allows(basket, "AddItem").assert().isTrue()
        val all = CallPermits(mapOf("basket-service" to listOf(" wow.lifecycle.* ")))
        all.allows(basket, Lifecycle.TRANSFER_OWNER).assert().isTrue()
        all.allows(basket, SnapshotRebuilds.REBUILD).assert().isFalse()
        val qualified = CallPermits(mapOf("basket-service" to listOf("shop.cart.wow.lifecycle.ApplyTags")))
        qualified.allows(basket, Lifecycle.APPLY_TAGS).assert().isTrue()
        qualified.refusal(basket, "shop", "order", Lifecycle.APPLY_TAGS).assert().isNotNull()
        CallPermits(mapOf("basket-service" to listOf("*.wow.*"))).allows(basket, ScheduleJobs.ENABLE).assert().isTrue()
    }

    @Test
    fun `a domain entry narrows the domain commands to those listed`() {
        val permits = CallPermits(mapOf("basket-service" to listOf("Checkout", "shop.cart.Open*")))
        permits.allows(basket, "Checkout").assert().isTrue()
        permits.allows(basket, "OpenCart").assert().isTrue()
        permits.allows(basket, "AddItem").assert().isFalse()
        permits.refusal(basket, "shop", "order", "OpenOrder").assert().isNotNull()
        permits.allows(basket, Lifecycle.DELETE_AGGREGATE).assert().isFalse()
        // Another service is not narrowed by this one's list.
        permits.allows(Caller(subject = "service:ordering-service", service = true), "AddItem").assert().isTrue()
    }

    @Test
    fun `a CoSec service principal is named by its id`() {
        val permits = CallPermits(mapOf("basket-principal" to listOf("wow.lifecycle.DeleteAggregate", "AddItem")))
        permits.allows(principal, Lifecycle.DELETE_AGGREGATE).assert().isTrue()
        permits.allows(principal, "Checkout").assert().isFalse()
        CallPermits.serviceOf(basket).assert().isEqualTo("basket-service")
        CallPermits.serviceOf(principal).assert().isEqualTo("basket-principal")
    }

    @Test
    fun `a blank entry is refused`() {
        assertThrownBy<IllegalArgumentException> { CallPermits(mapOf("basket-service" to listOf(" "))) }
    }
}
