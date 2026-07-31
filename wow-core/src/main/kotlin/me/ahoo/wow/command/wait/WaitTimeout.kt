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

package me.ahoo.wow.command.wait

import java.time.Duration

/**
 * Default maximum duration for command wait operations.
 */
val DEFAULT_WAIT_TIMEOUT: Duration = Duration.ofSeconds(30)

/**
 * A local execution decorator that bounds how long a gateway may execute a [WaitPlan].
 *
 * The timeout is intentionally not propagated with command headers. It owns caller-side
 * resource lifetime, while the delegated plan continues to own distributed stage matching.
 */
private data class TimeoutWaitPlan(
    val delegate: WaitPlan,
    val timeout: Duration,
) : WaitPlan by delegate {
    init {
        require(!timeout.isZero && !timeout.isNegative) {
            "Wait timeout must be greater than zero."
        }
    }
}

/**
 * Returns the local execution timeout for this plan.
 */
val WaitPlan.timeout: Duration
    get() = (this as? TimeoutWaitPlan)?.timeout ?: DEFAULT_WAIT_TIMEOUT

/**
 * Returns a plan with a caller-side execution timeout.
 */
fun WaitPlan.withTimeout(timeout: Duration): WaitPlan =
    if (this is TimeoutWaitPlan) {
        copy(timeout = timeout)
    } else {
        TimeoutWaitPlan(delegate = this, timeout = timeout)
    }
