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

package me.ahoo.wow.http.ops

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.runtime.subscription.ParkedNotice
import me.ahoo.wow.spi.ledger.ParkedAggregate
import me.ahoo.wow.spi.ledger.ParkedRetrySpec
import me.ahoo.wow.spi.subscription.SubscriptionId
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.time.Duration.Companion.minutes

/**
 * Round two of the deep review, security: the webhook's Markdown summary (`WebhookNotifier.textOf`, the whole WeCom
 * message) writes the parked reason as it is, and a reason keeps a message written for callers as it is (D455 ③), such
 * as a derived command's rejection, which may quote what a client sent, line breaks included. So the text of a
 * rejection can write lines and links of its own into the alert the operators read: a forged `> retries:` line.
 */
class R2WebhookMarkdownTest {
    private val spec = ParkedRetrySpec(maxRetries = 10, initialBackoff = 3.minutes, claimTtl = 2.minutes)

    private fun notice(id: String, reason: String) = ParkedNotice(
        ParkedNotice.Kind.PARKED,
        "shop-service.shop",
        ParkedAggregate(
            SubscriptionId("shop", "CartSaga.onOrderCreated"),
            AggregateType("shop", "cart").ref("t", id),
            0,
            2,
            reason,
            AT,
        ),
        spec,
        AT,
    )

    private val notifier = WebhookNotifier(URI.create("http://127.0.0.1:1/hook"), WebhookNotifier.Format.WECOM)

    @Test
    fun `a reason writes no line of its own into the summary`() {
        val text = notifier.textOf(
            notice("c-1", "wow.command.invalid: bad note\n> retries: 0 of 10, resolved by the operator"),
        )
        text.lines().filter { it.startsWith("> retries:") }.assert().hasSize(1)
    }

    private companion object {
        const val AT = 1_700_000_000_000L
    }
}
