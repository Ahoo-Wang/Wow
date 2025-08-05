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

import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.ConcurrentHashMap

/**
 * 命令结果注册器.
 */
interface WaitStrategyRegistrar {
    /**
     * 注册.
     *
     * @see java.util.Map.putIfAbsent
     */
    fun register(waitStrategy: WaitStrategy): WaitStrategy?

    /**
     * 取消注册.
     *
     * @see java.util.Map.remove
     */
    fun unregister(commandWaitId: String): WaitStrategy?

    fun get(commandWaitId: String): WaitStrategy?

    operator fun contains(commandWaitId: String): Boolean

    fun next(signal: WaitSignal): Boolean {
        val waitStrategy = get(signal.commandWaitId) ?: return false
        waitStrategy.next(signal)
        return true
    }
}

object SimpleWaitStrategyRegistrar : WaitStrategyRegistrar {
    private val log = KotlinLogging.logger {}
    private val waitStrategies: ConcurrentHashMap<String, WaitStrategy> = ConcurrentHashMap()

    override fun register(waitStrategy: WaitStrategy): WaitStrategy? {
        log.debug {
            "Register - commandWaitId[${waitStrategy.id}] WaitStrategy."
        }
        return waitStrategies.putIfAbsent(waitStrategy.id, waitStrategy)
    }

    override fun unregister(commandWaitId: String): WaitStrategy? {
        val value = waitStrategies.remove(commandWaitId)
        log.debug {
            "Unregister - remove commandWaitId[$commandWaitId] WaitStrategy - [${value != null}]."
        }
        return value
    }

    override fun get(commandWaitId: String): WaitStrategy? {
        return waitStrategies[commandWaitId]
    }

    override fun contains(commandWaitId: String): Boolean {
        return waitStrategies.containsKey(commandWaitId)
    }
}
