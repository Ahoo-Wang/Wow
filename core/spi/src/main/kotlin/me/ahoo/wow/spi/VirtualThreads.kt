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

package me.ahoo.wow.spi

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import me.ahoo.wow.api.annotation.WowSpi
import java.util.concurrent.Executors

/**
 * The dispatcher a backend runs its blocking driver calls on (JDBC, the MongoDB and Kafka clients; decisions D27, D29,
 * D31): a virtual thread per call, one executor for every backend of the node, so a backend need not start its own.
 */
@WowSpi
object VirtualThreads {
    /** A virtual thread per call. */
    val dispatcher: CoroutineDispatcher by lazy {
        Executors.newVirtualThreadPerTaskExecutor().asCoroutineDispatcher()
    }
}
