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

package me.ahoo.wow.example.eshop.ordering.domain.order

import kotlin.time.Duration

/**
 * How long an order whose stock is confirmed waits for its payment before it cancels itself: its [gracePeriod]. A
 * domain service of the order's command functions, so the server binds it once from its configuration and a test
 * gives it its own.
 */
data class PaymentPolicy(val gracePeriod: Duration) {
    init {
        require(gracePeriod.isPositive()) { "A payment grace period is positive, not $gracePeriod." }
    }
}
