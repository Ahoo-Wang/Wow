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

package me.ahoo.wow.spring

import me.ahoo.wow.messaging.dispatcher.MessageDispatcher
import org.springframework.context.SmartLifecycle
import java.time.Duration

open class MessageDispatcherLauncher(
    private val messageDispatcher: MessageDispatcher,
    private val shutdownTimeout: Duration
) : SmartLifecycle {
    private enum class LifecycleState {
        STOPPED,
        STARTING,
        RUNNING,
        STOP_REQUIRED
    }

    private val lifecycleMonitor = Any()

    @Volatile
    private var lifecycleState = LifecycleState.STOPPED

    // MessageDispatcher does not expose a narrower recoverable failure type.
    @Suppress("TooGenericExceptionCaught")
    override fun start() {
        synchronized(lifecycleMonitor) {
            if (lifecycleState != LifecycleState.STOPPED) {
                return
            }
            lifecycleState = LifecycleState.STARTING
            try {
                messageDispatcher.start()
                lifecycleState = LifecycleState.RUNNING
            } catch (startFailure: RuntimeException) {
                lifecycleState = LifecycleState.STOP_REQUIRED
                try {
                    stopDispatcher()
                } catch (cleanupFailure: RuntimeException) {
                    if (cleanupFailure !== startFailure) {
                        startFailure.addSuppressed(cleanupFailure)
                    }
                }
                throw startFailure
            }
        }
    }

    override fun stop() {
        synchronized(lifecycleMonitor) {
            if (lifecycleState == LifecycleState.STOPPED) {
                return
            }
            lifecycleState = LifecycleState.STOP_REQUIRED
            stopDispatcher()
        }
    }

    override fun isRunning(): Boolean {
        return lifecycleState != LifecycleState.STOPPED
    }

    private fun stopDispatcher() {
        messageDispatcher.stop(shutdownTimeout)
        lifecycleState = LifecycleState.STOPPED
    }
}
