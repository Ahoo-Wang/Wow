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
 * The webhook's Markdown summary (#7077), the whole of a WeCom message: what a client can reach is written as text. A
 * parked reason keeps a message written for callers as it is (D455 ③), such as a derived command's rejection, which may
 * quote what a client sent, line breaks and Markdown included; an aggregate's id is the client's too.
 */
class WebhookMarkdownTest {
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
    fun `a reason should write no line of its own into the summary, whatever breaks its lines`() {
        listOf("\n", "\r\n", "\r", " ", " ", "\u0085").forEach { lineBreak ->
            val text = notifier.textOf(
                notice("c-1", "wow.command.invalid: bad note$lineBreak> retries: 0 of 10, resolved by the operator"),
            )
            text.lines().filter { it.startsWith("> retries:") }.assert().describedAs(text)
                .containsExactly("> retries: 0 of 10, next retry at 2023-11-14T22:16:20Z")
            // Each character of the break a space: the forged line is the reason's text, on its line.
            val spaces = " ".repeat(lineBreak.length)
            text.assert().contains(
                "> reason: wow.command.invalid: bad note$spaces> retries: 0 of 10, resolved by the operator\n",
            )
        }
    }

    @Test
    fun `a reason's links, code, emphasis and HTML should be written as text`() {
        val text = notifier.textOf(
            notice(
                "c-1",
                """refused: [resolved](https://example.com) **now** `int` ~~x~~ _y_ <font color="info">ok</font> \""",
            ),
        )
        text.assert().contains(
            """> reason: refused: \[resolved\](https://example.com) \*\*now\*\* \`int\` \~\~x\~\~ \_y\_ """ +
                """\<font color="info">ok\</font> \\""" + "\n",
        )
    }

    @Test
    fun `an aggregate id should be written as text in the title`() {
        val text = notifier.textOf(notice("c-1**[resolved](mailto:ops)", "down"))
        text.lines().first().assert()
            .isEqualTo("""**shop.CartSaga.onOrderCreated parked: shop.cart/t/c-1\*\*\[resolved\](mailto:ops)**""")
    }

    @Test
    fun `a summary without markup should be written as it is`() {
        WebhookNotifier.markdownText("java.lang.IllegalStateException: down (shop.cart/t/c-1)").assert()
            .isEqualTo("java.lang.IllegalStateException: down (shop.cart/t/c-1)")
    }

    private companion object {
        const val AT = 1_700_000_000_000L
    }
}
