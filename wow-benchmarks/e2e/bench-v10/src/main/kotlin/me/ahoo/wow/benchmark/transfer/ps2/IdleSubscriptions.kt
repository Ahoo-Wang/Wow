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

package me.ahoo.wow.benchmark.transfer.ps2

import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.runtime.node.ContextNode
import me.ahoo.wow.runtime.node.SubscriptionFactory
import me.ahoo.wow.runtime.subscription.Subscription
import me.ahoo.wow.runtime.subscription.SubscriptionFilter
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Harness-only (PS-2, decision D240 ②): `--bench.extra-subscriptions=<n>` adds n projections that do nothing to the
 * context's log, beside the service's own subscriptions, so the harness can measure what each subscription costs a
 * partition when there is no traffic. None by default: E1 to E5 run without them, and the 9.x twin has no counterpart.
 * It is the one file `diff -r bench-9x/src bench-v10/src` shows beyond the domain (a sub-package the server scans).
 */
@Configuration(proxyBeanMethods = false)
class IdleSubscriptions {
    @OptIn(InternalWowApi::class)
    @Bean
    fun idleSubscriptionFactory(@Value("\${bench.extra-subscriptions:0}") count: Int): SubscriptionFactory =
        SubscriptionFactory { context: ContextNode ->
            (1..count).map { index ->
                Subscription("BenchIdle.p$index", SubscriptionFilter(context.context)) { }
            }
        }
}
