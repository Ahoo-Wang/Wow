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
import me.ahoo.wow.spi.config.WowConfigurationException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * An entry of `wow.aggregates` names an aggregate type the node serves, whichever of its settings it holds (#7033):
 * `operate/configuration.md` says such an entry refuses the start rather than being ignored. Its `tenant`,
 * `id-generator`, `routing` and stores were refused by the code that reads them; its `query.events.unpinned` and its
 * ClickHouse history were looked up by the served types alone, so a mistyped type kept the events queries it meant to
 * refuse running.
 */
class HostAggregateKeysTest {
    private fun refused(vararg settings: Pair<String, Any>): WowConfigurationException {
        val failure = assertThrows<Exception> { startHost(*settings).close() }
        return generateSequence<Throwable>(failure) { it.cause }.filterIsInstance<WowConfigurationException>().first()
    }

    @Test
    fun `an events policy under an aggregate type the node does not serve should refuse the start`() {
        val refusal = refused("wow.aggregates.[bank.acount].query.events.unpinned" to "refuse")
        refusal.key.assert().isEqualTo("wow.aggregates.[bank.acount]")
        refusal.reason.assert().contains("names no aggregate type this node serves")
        refusal.fix.assert().contains("bank.account")
    }

    @Test
    fun `a ClickHouse history under an aggregate type the node does not serve should refuse the start`() {
        refused("wow.aggregates.[bank.acount].clickhouse.history.enabled" to "false").key.assert()
            .isEqualTo("wow.aggregates.[bank.acount]")
    }

    @Test
    fun `an events policy of a type the node serves should start`() {
        startHost("wow.aggregates.[bank.account].query.events.unpinned" to "refuse").close()
    }
}
