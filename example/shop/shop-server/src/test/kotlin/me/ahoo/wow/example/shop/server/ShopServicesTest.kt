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

package me.ahoo.wow.example.shop.server

import kotlinx.coroutines.test.runTest
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/** The stub pricing service over the catalog. */
class ShopServicesTest {
    private val pricing = CatalogPricingService(Catalog.DEFAULT)

    @Test
    fun `a product of the catalog should have its price`(): Unit = runTest {
        pricing.getProductPrice("pear").assert().isEqualByComparingTo(BigDecimal("4.00"))
    }

    @Test
    fun `a product outside the catalog should have no price`(): Unit = runTest {
        val failure = runCatching { pricing.getProductPrice("kiwi") }.exceptionOrNull()
        failure.assert().isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("kiwi")
    }
}
