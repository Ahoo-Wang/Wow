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

package me.ahoo.wow.manifest

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.result.FunctionKind
import org.junit.jupiter.api.Test

class SubscriptionManifestTest {
    private val onEvent = SubscriptionManifest(
        "OrderSaga.onOrderPlaced",
        SubscriptionKind.SAGA,
        SubscriptionSource(aggregates = listOf("order")),
        FunctionManifest(FunctionKind.SAGA, "shop", "OrderSaga", "onOrderPlaced"),
    )

    @Test
    fun `a subscription should be a wait hop when it handles a commit, and not when it handles rejections alone`() {
        onEvent.isReceiptHandler.assert().isFalse()
        onEvent.isWaitHop.assert().isTrue()
        val rejectedOnly = onEvent.copyWith(source = SubscriptionSource(), rejected = emptyList())
        rejectedOnly.isReceiptHandler.assert().isTrue()
        rejectedOnly.isWaitHop.assert().isFalse()
        val committedOnly = onEvent.copyWith(source = SubscriptionSource(), committed = emptyList())
        committedOnly.isWaitHop.assert().isTrue()
        // A handler of both receipts handles a commit.
        rejectedOnly.copyWith(committed = listOf(Manifest.schemaRef("shop.Reserve"))).isWaitHop.assert().isTrue()
    }
}
