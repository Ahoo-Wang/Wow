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

package me.ahoo.wow.runtime.binding

import me.ahoo.test.asserts.assert
import me.ahoo.wow.kernel.ProcessorBinding
import org.junit.jupiter.api.Test

/**
 * Deep review r1 (compiler): the Manifest and the node read a subscription's source differently. A handler of the
 * whole stream whose `@OnEvent(aggregates)` names an aggregate of its own context is written with that aggregate alone
 * (`ManifestFragmentBuilder.source`), and the compiler lets a star state through (`ManifestFragmentBuilder.kt:176`).
 * The node picks the aggregates by state type only (`SubscriptionBindings.read`, `:268`), every one of them for a star:
 * the binding's `aggregates` count only for another context's. Fails on `ac58da98ac`.
 */
class R1SubscriptionSourcesTest {
    @Test
    fun `a star handler that names an aggregate of its own context should read that aggregate alone`() {
        val board = ProcessorBinding.project<Any>(
            "Board",
            "onEvent",
            null,
            aggregates = listOf("$SHOP.order"),
        ) { _, _ -> }
        SubscriptionBindings(mapOf(SHOP to listOf(board))).viewTypes(SHOP, listOf(orderModel(), cartModel())).assert()
            .containsExactly(ORDER)
    }
}
