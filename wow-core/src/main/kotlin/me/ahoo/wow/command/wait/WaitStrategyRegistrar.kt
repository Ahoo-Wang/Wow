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
    fun register(commandId: String, waitStrategy: WaitStrategy): WaitStrategy?

    /**
     * 取消注册.
     *
     * @see java.util.Map.remove
     */
    fun unregister(commandId: String): WaitStrategy?

    fun get(commandId: String): WaitStrategy?

    operator fun contains(commandId: String): Boolean

    fun next(signal: WaitSignal): Boolean {
        val waitStrategy = get(signal.commandId) ?: return false
        waitStrategy.next(signal)
        return true
    }
}

object SimpleWaitStrategyRegistrar : WaitStrategyRegistrar {
    private val log = KotlinLogging.logger {}
    private val waitStrategies: ConcurrentHashMap<String, WaitStrategy> = ConcurrentHashMap()

    override fun register(commandId: String, waitStrategy: WaitStrategy): WaitStrategy? {
        log.debug {
            "Register - command[$commandId] WaitStrategy."
        }
        return waitStrategies.putIfAbsent(commandId, waitStrategy)
    }

    override fun unregister(commandId: String): WaitStrategy? {
        val value = waitStrategies.remove(commandId)
        log.debug {
            "Unregister - remove command[$commandId] WaitStrategy - [${value != null}]."
        }
        return value
    }

    override fun get(commandId: String): WaitStrategy? {
        return waitStrategies[commandId]
    }

    override fun contains(commandId: String): Boolean {
        return waitStrategies.containsKey(commandId)
    }
}
