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

package me.ahoo.wow.benchmark.scenario

import me.ahoo.wow.messaging.dispatcher.MessageParallelism
import reactor.core.scheduler.Schedulers

fun resolveSchedulerPoolSize(
    value: String,
    defaultPoolSize: Int = Schedulers.DEFAULT_POOL_SIZE,
): Int = resolvePositiveSchedulerValue(
    value = value,
    defaultToken = "cpu",
    defaultValue = defaultPoolSize,
    name = "schedulerPoolSize",
)

fun resolveStripeCount(
    value: String,
    defaultStripeCount: Int = MessageParallelism.DEFAULT_PARALLELISM,
): Int = resolvePositiveSchedulerValue(
    value = value,
    defaultToken = "default",
    defaultValue = defaultStripeCount,
    name = "stripeCount",
)

private fun resolvePositiveSchedulerValue(
    value: String,
    defaultToken: String,
    defaultValue: Int,
    name: String,
): Int {
    require(defaultValue > 0) {
        "Default $name must be greater than 0."
    }
    val resolved = if (value == defaultToken) {
        defaultValue
    } else {
        value.toIntOrNull()
            ?: throw IllegalArgumentException(
                "$name must be [$defaultToken] or a positive integer, but was [$value]."
            )
    }
    require(resolved > 0) {
        "$name must be greater than 0, but was [$resolved]."
    }
    return resolved
}
