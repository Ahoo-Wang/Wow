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

package me.ahoo.wow.example.e2e

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/** The skeleton of the end-to-end suite (work package 6.00): the shop server is on its class path. */
class ExampleE2eTest {
    @Test
    fun `the shop server should be on the class path`() {
        Class.forName(SHOP_SERVER).name.assert().isEqualTo(SHOP_SERVER)
    }

    private companion object {
        const val SHOP_SERVER = "me.ahoo.wow.example.shop.server.ShopServerKt"
    }
}
