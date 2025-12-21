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
import java.util.concurrent.atomic.AtomicBoolean

open class MessageDispatcherLauncher(
    private val messageDispatcher: MessageDispatcher,
    private val shutdownTimeout: Duration
) : SmartLifecycle {
    private val running = AtomicBoolean(false)
    override fun start() {
        if (!running.compareAndSet(false, true)) {
            return
        }
        messageDispatcher.start()
    }

    override fun stop() {
        if (!running.compareAndSet(true, false)) {
            return
        }
        messageDispatcher.stop(shutdownTimeout)
    }

    override fun isRunning(): Boolean {
        return running.get()
    }
}
