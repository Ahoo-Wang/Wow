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

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.client.CommandClient
import me.ahoo.wow.api.client.CommandRequest
import me.ahoo.wow.api.result.CommandResult
import org.junit.jupiter.api.Test

/**
 * An entry of `wow.peers` for a context the node serves routes no call (D484 ③): several services share one file of
 * peers, which lists each one's own context beside the others', at its own address.
 */
class HostOwnContextPeerTest {
    @Test
    fun `an entry for a context the node serves, at an address nothing listens on, should route no call`() {
        startHost("wow.peers.$BANK.url" to "http://127.0.0.1:1", "wow.peers.$BANK.timeout" to "1s").use { host ->
            val commands = host.context.getBean(CommandClient::class.java).of(Caller.ofService("host-test"))
            runBlocking { commands.wait(CommandRequest.of(OpenAccount("own"))) }.status.assert()
                .isEqualTo(CommandResult.Status.SUCCEEDED)
        }
    }
}
