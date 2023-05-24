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

package me.ahoo.wow

import me.ahoo.wow.api.Wow
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import reactor.core.scheduler.Schedulers
import reactor.kotlin.core.publisher.toFlux

internal class WowTest {
    companion object {
        private val log = LoggerFactory.getLogger(WowTest::class.java)
    }

    @Test
    fun test() {
        assertThat(Wow.WOW, equalTo("wow"))
        assertThat(Wow.WOW_PREFIX, equalTo("wow."))
        (1..1000).toFlux()
            .publishOn(Schedulers.boundedElastic())
            .doOnNext {
                log.info("doOnNext:$it")
            }.blockLast()
        Thread.sleep(1000)
    }
}
