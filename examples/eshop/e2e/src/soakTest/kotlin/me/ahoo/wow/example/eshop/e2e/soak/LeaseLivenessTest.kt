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

package me.ahoo.wow.example.eshop.e2e.soak

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

@Suppress("FunctionNaming") // detekt names test functions so only under src/test; this is src/soakTest.
class LeaseLivenessTest {
    private val group = "eshop-inventory-service/eshop-inventory"
    private val schedule = "${walkGroup("schedule")}|0"
    private val stock = "${walkGroup("stock")}|0"

    private fun walkGroup(aggregate: String): String = "$group#access-walk/$aggregate"

    private fun required(leases: Map<String, Pair<String, Long>>, vararg due: String) =
        LeaseLiveness.requiringOwner(leases, due.toSet(), ::walkGroup)

    @Test
    fun `a completed walk's released lease needs no owner`() {
        required(mapOf(schedule to ("inventory-1" to 0L))).assert().isEmpty()
    }

    @Test
    fun `a due walk's lost lease needs an owner`() {
        required(mapOf(schedule to ("inventory-1" to 1234L)), "schedule")
            .assert().isEqualTo(mapOf(schedule to ("inventory-1" to 1234L)))
    }

    @Test
    fun `a due walk no node has leased needs an owner though it has no row`() {
        required(emptyMap(), "schedule").assert().isEqualTo(mapOf(schedule to (null to 0L)))
    }

    @Test
    fun `only the lease of the walk that is due needs an owner`() {
        required(mapOf(schedule to ("inventory-1" to 0L), stock to ("inventory-2" to 0L)), "stock")
            .assert().isEqualTo(mapOf(stock to ("inventory-2" to 0L)))
    }

    @Test
    fun `a partition of any other group needs an owner whatever the walks`() {
        val expired = mapOf(
            "$group|2" to ("inventory-1" to 0L),
            "$group#intake|0" to ("inventory-2" to 1234L),
        )
        required(expired).assert().isEqualTo(expired)
        required(expired + (schedule to ("inventory-1" to 0L))).assert().isEqualTo(expired)
    }
}
