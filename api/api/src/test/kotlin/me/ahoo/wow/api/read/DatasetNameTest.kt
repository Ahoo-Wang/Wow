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

package me.ahoo.wow.api.read

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test

class DatasetNameTest {
    @Test
    fun `a dataset name should be its context and name, written context dot name`() {
        val sales = DatasetName.of("shop", "sales")
        sales.assert().isEqualTo(DatasetName.of("shop", "sales"))
        sales.hashCode().assert().isEqualTo(DatasetName.of("shop", "sales").hashCode())
        sales.assert().isNotEqualTo(DatasetName.of("shop", "returns"))
        sales.assert().isNotEqualTo(DatasetName.of("bank", "sales"))
        sales.equals("shop.sales").assert().isFalse()
        sales.toString().assert().isEqualTo("shop.sales")
        assertThrownBy<IllegalArgumentException> { DatasetName.of(" ", "sales") }
        assertThrownBy<IllegalArgumentException> { DatasetName.of("shop", "") }
    }
}
