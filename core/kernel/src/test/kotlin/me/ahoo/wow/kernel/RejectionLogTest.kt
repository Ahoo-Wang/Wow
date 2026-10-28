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

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

class RejectionLogTest {
    private val cart = AggregateType("shop", "cart").ref("t", "c-1")

    @Test
    fun `a rejection is logged at INFO and a refusal at DEBUG, each with its fields as key-values`() {
        val events = capture {
            RejectionLog.rejected(cart, "shop.cart.AddItem", "shop.cart.closed", "r-1", true, "The cart is closed.")
            RejectionLog.refused(cart, "shop.cart.AddItem", "wow.command.busy", "r-2")
        }
        val (rejected, refused) = events
        rejected.level.assert().isEqualTo(Level.INFO)
        rejected.loggerName.assert().isEqualTo(RejectionLog.CATEGORY)
        rejected.formattedMessage.assert().isEqualTo(
            "Rejected shop.cart.AddItem on $cart with shop.cart.closed (requestId=r-1, withEvents=true): " +
                "The cart is closed.",
        )
        rejected.fields().assert().isEqualTo(
            mapOf(
                RejectionLog.CONTEXT to "shop",
                RejectionLog.AGGREGATE to "cart",
                RejectionLog.COMMAND to "shop.cart.AddItem",
                RejectionLog.ERROR_TYPE to "shop.cart.closed",
                RejectionLog.REQUEST_ID to "r-1",
                RejectionLog.WITH_EVENTS to true,
            ),
        )
        refused.level.assert().isEqualTo(Level.DEBUG)
        refused.formattedMessage.assert().isEqualTo(
            "Refused shop.cart.AddItem on $cart with wow.command.busy (requestId=r-2)."
        )
        refused.fields()[RejectionLog.ERROR_TYPE].assert().isEqualTo("wow.command.busy")
        refused.fields().keys.assert().doesNotContain(RejectionLog.WITH_EVENTS)
    }

    private fun ILoggingEvent.fields(): Map<String, Any?> = keyValuePairs.orEmpty().associate { it.key to it.value }

    private fun capture(block: () -> Unit): List<ILoggingEvent> {
        val logger = LoggerFactory.getLogger(RejectionLog.CATEGORY) as Logger
        val level = logger.level
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.level = Level.DEBUG
        logger.addAppender(appender)
        try {
            block()
            return appender.list.toList()
        } finally {
            logger.detachAppender(appender)
            logger.level = level
        }
    }
}
