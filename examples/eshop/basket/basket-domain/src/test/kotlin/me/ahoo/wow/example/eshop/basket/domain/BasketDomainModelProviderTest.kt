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

package me.ahoo.wow.example.eshop.basket.domain

import me.ahoo.test.asserts.assert
import me.ahoo.wow.kernel.Dependencies
import me.ahoo.wow.kernel.ModelProvider
import org.junit.jupiter.api.Test

/** What the compiler generated for the module: its binding index, found through `ServiceLoader`, and its fragment. */
class BasketDomainModelProviderTest {
    @Test
    fun `the binding index should name the context and the fragment and build the basket model and its checkout saga`() {
        val provider = ModelProvider.load().single()
        provider.context.assert().isEqualTo("eshop-basket")
        provider.fragment.assert().isEqualTo("META-INF/wow/manifest/eshop-basket-domain.json")
        javaClass.classLoader.getResource(provider.fragment).assert().isNotNull()
        provider.models(Dependencies.of()).map { it.type.toString() }.assert().containsExactly("eshop-basket.basket")
        provider.processors(
            Dependencies.NONE
        ).map { it.name }.assert().containsExactly("BasketCheckoutSaga.onBasketCheckedOut")
    }
}
