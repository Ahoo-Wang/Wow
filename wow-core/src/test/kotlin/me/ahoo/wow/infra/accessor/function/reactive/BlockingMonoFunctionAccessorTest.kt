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

package me.ahoo.wow.infra.accessor.function.reactive

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.annotation.Blocking
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import reactor.kotlin.test.test

class BlockingMonoFunctionAccessorTest {

    @Test
    fun invoke() {
        val blockingMonoMethodAccessor = BlockingMethod::blocking.toMonoFunctionAccessor<BlockingMethod, String>()
        Schedulers.parallel().schedule {
            blockingMonoMethodAccessor.invoke(BlockingMethod())
                .test()
                .consumeNextWith {
                    it.assert().startsWith("boundedElastic")
                }.verifyComplete()
        }

        blockingMonoMethodAccessor.invoke(BlockingMethod())
            .test()
            .consumeNextWith {
                it.assert().doesNotStartWith("boundedElastic")
            }.verifyComplete()
    }
}

class BlockingMethod {
    @Blocking
    fun blocking(): String {
        return Mono.just(Thread.currentThread().name).block()!!
    }
}
