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

package me.ahoo.wow.spring.boot.starter

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.scheduler.DefaultAggregateSchedulerSupplier

internal fun validateDispatcherProperties(
    stripeCount: Int,
    schedulerPoolSize: Int,
) {
    require(stripeCount > 0) {
        "stripeCount must be greater than 0."
    }
    require(schedulerPoolSize > 0) {
        "schedulerPoolSize must be greater than 0."
    }
}

private val log = KotlinLogging.logger {}

internal fun createSchedulerSupplier(
    name: String,
    stripeCount: Int,
    schedulerPoolSize: Int,
): DefaultAggregateSchedulerSupplier {
    log.info {
        "Configure [$name]: stripeCount=$stripeCount, " +
            "schedulerPoolSize=$schedulerPoolSize per named aggregate."
    }
    return DefaultAggregateSchedulerSupplier(
        name = name,
        parallelism = schedulerPoolSize,
    )
}
