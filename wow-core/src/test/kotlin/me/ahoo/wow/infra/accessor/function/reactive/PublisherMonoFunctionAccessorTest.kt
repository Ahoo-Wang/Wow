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
import org.junit.jupiter.api.Test
import org.reactivestreams.Publisher
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.kotlin.test.test

class PublisherMonoFunctionAccessorTest {
    @Test
    fun invokeList() {
        val methodAccessor =
            ::publisherListFunction.toMonoFunctionAccessor<PublisherMonoFunctionAccessorTest, List<String>>()
        methodAccessor.assert().isInstanceOf(PublisherMonoFunctionAccessor::class.java)
        methodAccessor.invoke(this).test()
            .expectNext(listOf("hi"))
            .verifyComplete()
    }

    @Test
    fun invokeSingle() {
        val methodAccessor =
            ::publisherSingleFunction.toMonoFunctionAccessor<PublisherMonoFunctionAccessorTest, List<String>>()
        methodAccessor.assert().isInstanceOf(PublisherMonoFunctionAccessor::class.java)
        methodAccessor.invoke(this).test()
            .expectNext(listOf("hi"))
            .verifyComplete()
    }

    fun publisherListFunction(): Publisher<String> {
        return Flux.just("hi")
    }

    fun publisherSingleFunction(): Publisher<String> {
        return Mono.just("hi")
    }
}
