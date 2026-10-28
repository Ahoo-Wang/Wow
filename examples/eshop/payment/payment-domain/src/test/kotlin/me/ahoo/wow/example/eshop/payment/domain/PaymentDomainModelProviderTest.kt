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

package me.ahoo.wow.example.eshop.payment.domain

import me.ahoo.test.asserts.assert
import me.ahoo.wow.kernel.Dependencies
import me.ahoo.wow.kernel.ModelProvider
import me.ahoo.wow.manifest.ManifestTree
import me.ahoo.wow.manifest.RouteManifest
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper

/** What the compiler generated for the module: its binding index, found through `ServiceLoader`, and its fragment. */
class PaymentDomainModelProviderTest {
    private val provider = ModelProvider.load().single()

    @Test
    fun `the fragment should mount the chargeback callback under the payment, with and without an id (D182)`() {
        val tree = checkNotNull(javaClass.classLoader.getResourceAsStream(provider.fragment))
            .use { JsonMapper.builder().build().readValue(it, Map::class.java) }
        val commands = ManifestTree.read(tree).contexts.single().aggregates.single().commands.associateBy { it.name }
        val chargeback = commands.getValue("ChargebackNotified")
        chargeback.mounted.assert().isTrue()
        chargeback.transformsTo.assert().containsExactly("eshop-payment.payment.RefundPayment")
        chargeback.routes.map(RouteManifest::path).assert().containsExactly(
            "/eshop-payment/payment/{id}/chargeback-notified",
            "/eshop-payment/payment/chargeback-notified",
        )
        commands.getValue("RefundPayment").mounted.assert().isFalse()
    }

    @Test
    fun `the binding index should name the context and the fragment and build the payment model`() {
        provider.context.assert().isEqualTo("eshop-payment")
        provider.fragment.assert().isEqualTo("META-INF/wow/manifest/eshop-payment-domain.json")
        javaClass.classLoader.getResource(provider.fragment).assert().isNotNull()
        provider.models(Dependencies.of()).map { it.type.toString() }.assert().containsExactly("eshop-payment.payment")
    }
}
