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

package me.ahoo.wow.cocache

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** The views the contract jars on a class path list ([me.ahoo.wow.manifest.PeerContracts.STATE_VIEWS_RESOURCE]). */
class ContractViewsTest {
    @Test
    fun `each listed view should be found by the aggregate its StateView names`() {
        val loader = ContractClassLoader(javaClass.classLoader, ORDER_VIEWS, CustomerView::class.java.name)
        ContractViews.onClassPath(loader)
            .assert().isEqualTo(mapOf(ORDER to OrderView::class.java, CUSTOMER to CustomerView::class.java))
    }

    @Test
    fun `a list concatenated by a shaded jar should be read with its blank lines and repeated names ignored`() {
        val shaded = "$ORDER_VIEWS\n  \n${CustomerView::class.java.name}\n$ORDER_VIEWS"
        ContractViews.onClassPath(ContractClassLoader(javaClass.classLoader, shaded, ORDER_VIEWS))
            .assert().isEqualTo(mapOf(ORDER to OrderView::class.java, CUSTOMER to CustomerView::class.java))
    }

    @Test
    fun `a class path with no list should have no views`() {
        ContractViews.onClassPath(ContractClassLoader(javaClass.classLoader)).assert().isEmpty()
    }

    @Test
    fun `a listed view on no class path entry should be refused, naming the list, the class and what to do`() {
        val missing = "shop.MissingStateView"
        assertThrows<IllegalStateException> {
            ContractViews.onClassPath(ContractClassLoader(javaClass.classLoader, missing))
        }.message.assert().contains("state-views", "file:", missing, "one version of the api module's contract")
    }

    @Test
    fun `a listed class with no StateView should be refused, naming the list, the class and what to do`() {
        val plain = ContractViewsTest::class.java.name
        assertThrows<IllegalStateException> {
            ContractViews.onClassPath(ContractClassLoader(javaClass.classLoader, plain))
        }.message.assert().contains("state-views", plain, "no @StateView", "Build the api module")
    }
}
