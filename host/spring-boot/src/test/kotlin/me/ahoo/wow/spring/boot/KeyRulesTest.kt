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

package me.ahoo.wow.spring.boot

import me.ahoo.test.asserts.assert
import me.ahoo.wow.runtime.wait.UnknownContexts
import me.ahoo.wow.spring.boot.config.ConfigurationKeys
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The keys' names and values of D484 ①: one spelling of a refusal (`refuse`), one rule for no bound (`0` turns a bound
 * off, unset is the default), and three keys renamed to their siblings' pattern; the names they had are unknown keys.
 */
class KeyRulesTest {
    private fun wow(vararg properties: Pair<String, String>): WowProperties =
        Binder(MapConfigurationPropertySource(properties.toMap())).bind(WowProperties.PREFIX, WowProperties::class.java)
            .orElse(null) ?: WowProperties()

    @Test
    fun `an unknown context should be refused with refuse, as an unpinned events query is`() {
        wow("wow.wait.unknown-contexts" to "refuse").wait.options().unknownContexts.assert()
            .isEqualTo(UnknownContexts.REFUSE)
    }

    @Test
    fun `a bound that can be turned off should be turned off by 0`() {
        val unset = WowProperties()
        val zero = wow(
            "wow.timers.expires-after" to "0s",
            "wow.timers.max-fire-rate" to "0",
            "wow.subscription.retry.timeout" to "0s",
            "wow.subscriptions.[bank.wow.snapshot].retry.timeout" to "0s",
        )
        zero.timers.options().expiresAfter.assert().isEqualTo(unset.timers.options().expiresAfter).isNull()
        zero.timers.options().maxFireRate.assert().isNull()
        wow("wow.timers.expires-after" to "5m", "wow.timers.max-fire-rate" to "2.5").timers.options().also { bounded ->
            bounded.expiresAfter.assert().isEqualTo(5.minutes)
            bounded.maxFireRate.assert().isEqualTo(2.5)
        }
        zero.subscription.retry.policy().timeout.assert().isEqualTo(Duration.INFINITE)
        // A subscription's own 0 runs its attempts as long as they take, over a global timeout.
        zero.subscriptions.getValue("bank.wow.snapshot").retry.handlerRetry()!!.timeout.assert()
            .isEqualTo(Duration.INFINITE)
    }

    @Test
    fun `the renamed keys should be read, and their former names be unknown`() {
        val renamed = wow(
            "wow.timers.claim-ttl" to "7s",
            "wow.subscription.read-retry.error-after-attempts" to "3",
        )
        renamed.timers.options().claimTimeout.assert().isEqualTo(7.seconds)
        renamed.subscription.readRetry.policy().maxAttempts.assert().isEqualTo(3)
        val environment = StandardEnvironment().apply {
            propertySources.addFirst(
                MapPropertySource(
                    "former",
                    mapOf(
                        "wow.timers.claim-timeout" to "7s",
                        "wow.subscription.read-retry.error-after" to "3",
                        "wow.kafka.topics.partition-refresh" to "5s",
                    ),
                ),
            )
        }
        val keys = ConfigurationKeys.of(
            javaClass.classLoader,
            declared = listOf("wow.kafka.topics.partition-refresh-interval"),
        )
        keys.unknown(environment).assert().containsExactlyInAnyOrder(
            "wow.timers.claim-timeout",
            "wow.subscription.read-retry.error-after",
            "wow.kafka.topics.partition-refresh",
        )
    }
}
