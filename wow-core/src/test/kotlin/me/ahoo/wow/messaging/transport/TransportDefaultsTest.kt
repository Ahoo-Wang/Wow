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

package me.ahoo.wow.messaging.transport

import me.ahoo.test.asserts.assert
import me.ahoo.wow.runtime.RuntimeResource
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.kotlin.test.test

/**
 * What a transport that implements only the required members gets: a keyed record whose negative acknowledgement
 * does nothing, a receiver that is ready at once and has nothing to open or close, and no runtime resource.
 */
class TransportDefaultsTest {

    @Test
    fun `a minimal record is keyed and its nack does nothing`() {
        val record = object : TransportRecord {
            override val topic: String = "topic"
            override val key: String = "key"
            override val payload: String = "{}"
            override val id: String = "0-1"
            override fun ack(): Mono<Void> = Mono.empty()
        }

        record.keyed.assert().isTrue()
        record.nack().test().verifyComplete()
    }

    @Test
    fun `a minimal receiver is ready at once and opening or closing it does nothing`() {
        val receiver = object : TransportReceiver {
            override val records: Flux<TransportRecord> = Flux.empty()
        }

        receiver.readiness.test().verifyComplete()
        receiver.openProcessing()
        receiver.close()
        receiver.records.test().verifyComplete()
    }

    @Test
    fun `a minimal transport has no runtime resource and closing it does nothing`() {
        val transport = object : Transport {
            override fun send(message: TransportMessage): Mono<Void> = Mono.empty()
            override fun open(group: String, topics: Set<String>): TransportReceiver = error("not opened")
        }

        transport.runtimeResource.assert().isSameAs(RuntimeResource.NONE)
        transport.close()
        RuntimeResource.NONE.stopGracefully().test().verifyComplete()
        RuntimeResource.NONE.forceStop()
        RuntimeResource.NONE.toString().assert().isEqualTo("RuntimeResource.NONE")
    }
}
