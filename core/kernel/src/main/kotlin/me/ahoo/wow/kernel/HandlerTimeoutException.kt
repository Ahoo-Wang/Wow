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

package me.ahoo.wow.kernel

import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.annotation.InternalWowApi
import kotlin.time.Duration

/**
 * An attempt of [subscription]'s handler at [ref]'s [version] that ran longer than its budget's [timeout]
 * ([HandlerRetry.timeout], `@Retry(timeout)`): it was cancelled and counts as a failed attempt, retried like any other
 * failure. The runtime's subscriptions and the test DSL throw it alike.
 */
@InternalWowApi
class HandlerTimeoutException(
    val subscription: String,
    val ref: AggregateRef,
    val version: Int,
    val timeout: Duration,
) : IllegalStateException("$subscription did not handle $ref@$version within $timeout.")
