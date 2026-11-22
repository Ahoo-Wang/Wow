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

package me.ahoo.wow.kernel

import com.fasterxml.jackson.annotation.JsonProperty
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.decide.RejectedException
import me.ahoo.wow.api.decide.Rejection
import me.ahoo.wow.api.problem.Retry
import me.ahoo.wow.api.problem.WowProblemException
import me.ahoo.wow.api.result.ErrorInfo
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.subscription.FailureDescriber
import me.ahoo.wow.spi.subscription.FailureDescription
import org.junit.jupiter.api.Test
import tools.jackson.core.exc.StreamReadException
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.exc.MismatchedInputException
import tools.jackson.databind.json.JsonMapper
import kotlin.time.Duration.Companion.seconds

/**
 * #6588: the reason a parked aggregate records is the failure's class and what is known of it without its message,
 * which may quote a value: a description built from the failure's structured fields, or a message written for a
 * caller.
 */
class FailureReasonsTest {
    data class Card(val holder: String, val digits: Int)

    data class Prices(val prices: Map<String, Int>)

    data class Wallet(val cards: List<Card>)

    data class Named(@param:JsonProperty("first name") val first: Int)

    private val reasons = FailureReasons.DEFAULT

    private fun failureOf(block: () -> Unit): Exception = runCatching(block).exceptionOrNull() as Exception

    @Test
    fun `a failure no describer knows should be its class name alone, its message left out`() {
        reasons.reasonOf(IllegalStateException("The card 4111 1111 1111 1111 is refused.")).assert()
            .isEqualTo("java.lang.IllegalStateException")
        FailureClassification.DEFAULT.reasonOf(IllegalArgumentException("alice@example.com")).assert()
            .isEqualTo("java.lang.IllegalArgumentException")
    }

    @Test
    fun `a message written for a caller should be kept as it is`() {
        reasons.reasonOf(WowProblemException("wow.command.busy", 429, Retry.SAME_REQUEST, 1.seconds, "Busy."))
            .assert().isEqualTo("me.ahoo.wow.api.problem.WowProblemException: Busy.")
        reasons.reasonOf(RejectedException(Rejection.of("out-of-stock", "Only 2 left."))).assert()
            .isEqualTo("me.ahoo.wow.api.decide.RejectedException: Only 2 left.")
        val handling = HandlingRejectedException(ErrorInfo("shop.order.closed", "Closed."), "shop.order.closed Closed.")
        reasons.reasonOf(handling).assert()
            .isEqualTo("me.ahoo.wow.kernel.HandlingRejectedException: shop.order.closed Closed.")
        // A blank one is no description.
        reasons.reasonOf(RejectedException(Rejection.of("out-of-stock", " "))).assert()
            .isEqualTo("me.ahoo.wow.api.decide.RejectedException")
    }

    @Test
    fun `a property Jackson cannot read should be named by its path and the type it read as, not its value`() {
        val digits = failureOf {
            WowJson.mapper.treeToValue(
                WowJson.mapper.readTree("""{"holder":"Alice Smith","digits":"4111 1111 1111 1111"}"""),
                Card::class.java,
            )
        }
        reasons.reasonOf(digits).assert()
            .isEqualTo("tools.jackson.databind.exc.MismatchedInputException: cannot read 'digits' as int")
        // A map's key is data, even one a name would take: written {key}.
        val price = failureOf { WowJson.mapper.readValue("""{"prices":{"alice":"many"}}""", Prices::class.java) }
        reasons.reasonOf(price).assert().contains("cannot read 'prices.{key}' as").doesNotContain("alice")
    }

    @Test
    fun `an array's item should be named by its index, and a property no name fits as a key`() {
        val item = failureOf {
            WowJson.mapper.readValue("""{"cards":[{"holder":"a","digits":"x"}]}""", Wallet::class.java)
        }
        reasons.reasonOf(item).assert().endsWith(": cannot read 'cards[0].digits' as int")
        val spaced = failureOf { WowJson.mapper.readValue("""{"first name":"alice"}""", Named::class.java) }
        reasons.reasonOf(spaced).assert().endsWith(": cannot read '{key}' as int")
        // Without the type it read as, or the place it failed at, what is known is said.
        reasons.reasonOf(MismatchedInputException.from(null, null as Class<*>?, "alice")).assert()
            .isEqualTo("tools.jackson.databind.exc.MismatchedInputException: cannot read")
        reasons.reasonOf(StreamReadException(null, "alice")).assert()
            .isEqualTo("tools.jackson.core.exc.StreamReadException: unreadable")
    }

    @Test
    fun `text that does not parse, or an unknown property, should be told where, never what`() {
        val malformed = failureOf { WowJson.mapper.readTree("""{"email": alice.smith@example.com}""") }
        reasons.reasonOf(malformed).assert().startsWith(malformed.javaClass.name + ": malformed json at line 1 column ")
            .doesNotContain("alice")
        val strict = JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build()
        val unknown = failureOf { strict.readValue("""{"holder":"a","digits":1,"pin":1234}""", Card::class.java) }
        reasons.reasonOf(unknown).assert()
            .isEqualTo("tools.jackson.databind.exc.UnrecognizedPropertyException: unknown property 'pin'")
    }

    @Test
    fun `a cause should follow as caused by, at most three, each with what is known of it`() {
        val digits = failureOf {
            WowJson.mapper.treeToValue(WowJson.mapper.readTree("""{"holder":"a","digits":"x"}"""), Card::class.java)
        } as RuntimeException
        val order = AggregateType("shop", "order").ref("t", "o-1")
        val unreadable = UnreadableEventException(order, 2, "shop.order.Placed", 1, digits)
        reasons.reasonOf(unreadable).assert().isEqualTo(
            "me.ahoo.wow.kernel.UnreadableEventException: event 'shop.order.Placed' revision 1 not readable; " +
                "caused by tools.jackson.databind.exc.MismatchedInputException: cannot read 'digits' as int",
        )
        // An event's name a description does not take, too long or holding a space, is left out; the rest stays.
        listOf("shop.order." + "Placed".repeat(25), "shop order.Placed").forEach { name ->
            reasons.reasonOf(UnreadableEventException(order, 2, name, 1, digits)).assert().startsWith(
                "me.ahoo.wow.kernel.UnreadableEventException: event revision 1 not readable; caused by ",
            )
        }
        // Undescribed, its causes follow its class name; a fifth link is not named.
        val fifth = Error("d", Error("e"))
        val deep = IllegalStateException("a", IllegalStateException("b", IllegalArgumentException("c", fifth)))
        reasons.reasonOf(deep).assert().isEqualTo(
            "java.lang.IllegalStateException: caused by java.lang.IllegalStateException; caused by " +
                "java.lang.IllegalArgumentException; caused by java.lang.Error",
        )
        val timeout = HandlerTimeoutException("Board.onOrder", order, 1, 2.seconds)
        reasons.reasonOf(timeout).assert()
            .isEqualTo("me.ahoo.wow.kernel.HandlerTimeoutException: timed out after 2000 ms")
    }

    @Test
    fun `the application's describers should come first, and one that throws should describe nothing`() {
        val first = FailureDescriber { FailureDescription.of(FailureDescription.code(1)) }
        val failing = FailureDescriber { error("broken") }
        FailureReasons.of(listOf(first)).reasonOf(IllegalStateException("x")).assert()
            .isEqualTo("java.lang.IllegalStateException: 1")
        FailureReasons.of(listOf(failing)).reasonOf(IllegalStateException("x")).assert()
            .isEqualTo("java.lang.IllegalStateException")
        FailureReasons.of(listOf(failing, first)).reasonOf(IllegalStateException("x")).assert()
            .isEqualTo("java.lang.IllegalStateException: 1")
        FailureClassification.of(reasons = FailureReasons.of(listOf(first))).reasonOf(IllegalStateException("x"))
            .assert().endsWith(": 1")
    }

    @Test
    fun `a reason should be at most a thousand characters`() {
        val long = WowProblemException("wow.command.failed", 500, Retry.NONE, null, "x".repeat(2_000))
        reasons.reasonOf(long).length.assert().isEqualTo(FailureReasons.MAX_LENGTH)
    }
}
