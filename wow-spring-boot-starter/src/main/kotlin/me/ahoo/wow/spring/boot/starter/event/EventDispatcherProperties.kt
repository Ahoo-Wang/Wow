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

package me.ahoo.wow.spring.boot.starter.event

import me.ahoo.wow.api.Wow
import me.ahoo.wow.messaging.dispatcher.MessageParallelism
import me.ahoo.wow.spring.boot.starter.validateDispatcherProperties
import org.springframework.boot.context.properties.ConfigurationProperties
import reactor.core.scheduler.Schedulers

/**
 * Domain event dispatcher tuning.
 *
 * @param stripeCount number of ordering stripes
 * @param schedulerPoolSize worker count per named aggregate Scheduler
 */
@ConfigurationProperties(prefix = EventDispatcherProperties.PREFIX)
class EventDispatcherProperties(
    stripeCount: Int = MessageParallelism.DEFAULT_PARALLELISM,
    schedulerPoolSize: Int = Schedulers.DEFAULT_POOL_SIZE,
) {
    var stripeCount: Int = stripeCount
        set(value) {
            require(value > 0) {
                "stripeCount must be greater than 0."
            }
            field = value
        }

    var schedulerPoolSize: Int = schedulerPoolSize
        set(value) {
            require(value > 0) {
                "schedulerPoolSize must be greater than 0."
            }
            field = value
        }

    init {
        validateDispatcherProperties(stripeCount, schedulerPoolSize)
    }

    companion object {
        const val PREFIX = "${Wow.WOW_PREFIX}event.dispatcher"
    }
}
