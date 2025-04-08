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

import io.github.oshai.kotlinlogging.KotlinLogging
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers

class SchedulerTest {
    companion object {
        private val log = KotlinLogging.logger { }
    }

    @Test
    fun test() {
        val firstScheduler = Schedulers.newSingle("firstScheduler")
        val secondScheduler = Schedulers.newSingle("secondScheduler")
        val thirdScheduler = Schedulers.newSingle("thirdScheduler")
        val fourthScheduler = Schedulers.newSingle("fourthScheduler")
        Flux.fromIterable(listOf(1, 2, 3, 4, 5))
            .publishOn(firstScheduler)
            .doOnSubscribe {
                log.info { "subscribe" } // fourthScheduler
            }
            .doOnNext {
                log.info { "<1> $it" } // firstScheduler
            }
            .publishOn(secondScheduler)
            .doOnNext {
                log.info { "<2> $it" } // secondScheduler
            }
            .flatMap {
                Mono.fromCallable {
                    log.info { "<3> $it" } // thirdScheduler
                    it * 2
                }.publishOn(thirdScheduler)
            }
            .doOnNext {
                log.info { "<4> $it" } // thirdScheduler
            }
            .subscribeOn(fourthScheduler)
            .blockLast()
    }
}
