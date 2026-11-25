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
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * Review r2 (configuration keys): `wow.node.address` is "the `host:port` of this node's internal port that the other
 * nodes reach". The host checks it with `NodeAddress.isValid` (`WowNode.kt:174-181`), which asks only whether
 * `http://<address>` has a host: an address without a port, with port 0, or with a port past 65535 passes, the node
 * starts and advertises it in its member record, and the other nodes' forwards and wait signals go to
 * `http://<host>/…`, port 80, or to a port nothing can bind.
 */
class R2NodeAddressTest {
    @ParameterizedTest
    @ValueSource(strings = ["127.0.0.1", "127.0.0.1:0", "127.0.0.1:99999"])
    fun `an address the other nodes cannot reach the internal port at should refuse the start`(address: String) {
        val failure = assertThrows<Exception> { startHost("wow.node.address" to address).close() }
        generateSequence<Throwable>(failure) { it.cause }.filterIsInstance<WowConfigurationException>().first()
            .key.assert().isEqualTo("wow.node.address")
    }
}
