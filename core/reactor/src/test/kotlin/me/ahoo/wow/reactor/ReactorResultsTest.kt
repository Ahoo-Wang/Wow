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

package me.ahoo.wow.reactor

import kotlinx.coroutines.test.runTest
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

class ReactorResultsTest {
    @Test
    fun `a mono yields its value or nothing`() = runTest {
        awaitResult(Mono.just("event")).assert().isEqualTo("event")
        awaitResult(Mono.empty<String>()).assert().isNull()
    }

    @Test
    fun `a flux yields every element`() = runTest {
        awaitResult(Flux.just("a", "b")).assert().isEqualTo(listOf("a", "b"))
    }
}
