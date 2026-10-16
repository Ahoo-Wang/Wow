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
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.clickhouse.sink.AggregateSinkOptions
import me.ahoo.wow.runtime.cluster.ClusterOptions
import me.ahoo.wow.runtime.state.StateViewOptions
import me.ahoo.wow.spi.StoreDdl
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource
import java.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** The configuration gaps of review A5-10: the stores' DDL, the state views, the forward timeout, the per-type keys. */
class HostOptionsTest {
    private fun <T : Any> bind(prefix: String, type: Class<T>, vararg properties: Pair<String, String>): T =
        Binder(MapConfigurationPropertySource(properties.toMap())).bind(prefix, type).orElse(null)
            ?: type.getDeclaredConstructor().newInstance()

    private fun wow(vararg properties: Pair<String, String>): WowProperties =
        bind(WowProperties.PREFIX, WowProperties::class.java, *properties)

    @Test
    fun `the stores' DDL should be auto by default, set for all and per store`() {
        WowProperties().ddl.assert().isEqualTo(StoreDdl.AUTO)
        val properties = wow(
            "wow.ddl" to "validate",
            "wow.stores.audit.type" to "postgres",
            "wow.stores.audit.ddl" to "none"
        )
        properties.ddl.assert().isEqualTo(StoreDdl.VALIDATE)
        properties.stores.getValue("audit").ddl.assert().isEqualTo(StoreDdl.NONE)
    }

    @Test
    fun `the state views should be configurable, by count, age and bytes`() {
        WowProperties().stateViews.options().assert().isEqualTo(StateViewOptions())
        val options = wow(
            "wow.state-views.capacity" to "128",
            "wow.state-views.max-age" to "5m",
            "wow.state-views.max-bytes" to "1048576",
        ).stateViews.options()
        options.assert().isEqualTo(StateViewOptions(128, 5.minutes, 1_048_576))
    }

    @Test
    fun `the forward timeout should follow the default wait unless it is set`() {
        WowProperties().cluster.options().assert().isEqualTo(ClusterOptions())
        val following = wow("wow.wait.default-timeout" to "30s", "wow.cluster.reconcile-timeout" to "2m")
        following.cluster.options(following.wait.defaultTimeout).forwardTimeout.assert().isEqualTo(30.seconds)
        following.cluster.options(following.wait.defaultTimeout).reconcileTimeout.assert().isEqualTo(2.minutes)
        val set = wow("wow.wait.default-timeout" to "30s", "wow.cluster.forward-timeout" to "5s")
        set.cluster.options(set.wait.defaultTimeout).forwardTimeout.assert().isEqualTo(5.seconds)
        set.cluster.options(Duration.ofSeconds(1)).forwardTimeout.assert().isEqualTo(5.seconds)
    }

    @Test
    fun `an aggregate's ClickHouse history should take the one per-type style over the older keys`() {
        val clickhouse = bind(
            ClickHouseProperties.PREFIX,
            ClickHouseProperties::class.java,
            "wow.clickhouse.sink.aggregates.bank.account.history.ttl" to "30d",
            "wow.clickhouse.sink.aggregates.bank.ledger.history.enabled" to "false",
        )
        val wow = wow(
            "wow.aggregates.[bank.account].clickhouse.history.ttl" to "7d",
            "wow.aggregates.[bank.audit].clickhouse.history.enabled" to "false",
            "wow.aggregates.[bank.card].tenant" to "t0",
        )
        ClickHouseAutoConfiguration.options(clickhouse, wow.aggregates).aggregates.assert().isEqualTo(
            mapOf(
                AggregateType("bank", "account") to AggregateSinkOptions(historyTtl = 7.days),
                AggregateType("bank", "ledger") to AggregateSinkOptions(history = false),
                AggregateType("bank", "audit") to AggregateSinkOptions(history = false),
            ),
        )
        val malformed = wow("wow.aggregates.[account].clickhouse.history.enabled" to "false")
        assertThrows<IllegalArgumentException> {
            ClickHouseAutoConfiguration.options(ClickHouseProperties(), malformed.aggregates)
        }
    }

    @Test
    fun `static service tokens and CoSec service principals should not both be configured`() {
        WowAutoConfiguration.serviceTokens(wow("wow.http.services.ordering.tokens[0]" to "t".repeat(32)))
        WowAutoConfiguration.serviceTokens(wow("wow.cosec.service.roles" to "service"))
        val both = wow("wow.http.services.ordering.tokens[0]" to "t".repeat(32), "wow.cosec.service.policies" to "svc")
        assertThrows<IllegalStateException> { WowAutoConfiguration.serviceTokens(both) }
            .message.assert().contains("not both")
    }
}
