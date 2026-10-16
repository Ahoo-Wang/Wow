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

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import me.ahoo.wow.api.annotation.InternalWowApi
import java.util.concurrent.Executors

/**
 * The dispatcher every `@Blocking` call runs on (design 01 §4, decisions D36, D227): a virtual thread per call (JDK 21,
 * D29). The kernel runs blocking decide functions and after hooks on it, the runtime its blocking subscription
 * handlers and receipts, so a node has one of it.
 */
@InternalWowApi
object BlockingDispatcher {
    /** A virtual thread per call. */
    val dispatcher: CoroutineDispatcher by lazy {
        Executors.newVirtualThreadPerTaskExecutor().asCoroutineDispatcher()
    }
}
