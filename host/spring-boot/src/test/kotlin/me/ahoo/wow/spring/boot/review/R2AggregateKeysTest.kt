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

package me.ahoo.wow.spring.boot.review

import me.ahoo.test.asserts.assert
import me.ahoo.wow.spi.config.WowConfigurationException
import me.ahoo.wow.spring.boot.startHost
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Review r2 (configuration keys): `operate/configuration.md` says the host "refuses to start on a name that matches
 * nothing it serves, rather than ignoring it: ... an aggregate type under `wow.aggregates` the node does not serve".
 * The `tenant`, `id-generator`, `routing`, `log.store`, `snapshot.store` and `query.events.store` of such an entry are
 * refused; its `query.events.unpinned` is looked up by the served types alone (`HostQueryBulkheads.unpinned`) and never
 * read, so a mistyped type keeps the events queries it meant to refuse running, without a word.
 */
class R2AggregateKeysTest {
    @Test
    fun `an events policy under an aggregate type the node does not serve should refuse the start`() {
        val failure = assertThrows<Exception> {
            startHost("wow.aggregates.[bank.acount].query.events.unpinned" to "refuse").close()
        }
        generateSequence<Throwable>(failure) { it.cause }.filterIsInstance<WowConfigurationException>().first()
            .key.assert().startsWith("wow.aggregates.[bank.acount]")
    }

    /** The control: the same entry's tenant is refused, as the page says. */
    @Test
    fun `a tenant under an aggregate type the node does not serve should refuse the start`() {
        val failure = assertThrows<Exception> { startHost("wow.aggregates.[bank.acount].tenant" to "t1").close() }
        generateSequence<Throwable>(failure) { it.cause }.filterIsInstance<WowConfigurationException>().first()
            .key.assert().startsWith("wow.aggregates.[bank.acount]")
    }
}
