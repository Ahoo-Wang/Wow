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

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.context.ApplicationContext
import org.springframework.context.SmartLifecycle

/**
 * Must complete before the Wow runtime readiness barrier opens.
 * @see WowRuntimeLifecycle
 */
const val AUTO_REGISTRAR_PHASE = WOW_RUNTIME_PHASE - 1024

abstract class AutoRegistrar<CM : Annotation>(
    private val componentType: Class<CM>,
    private val applicationContext: ApplicationContext
) : SmartLifecycle {
    private enum class State {
        NEW,
        STARTING,
        RUNNING,
        TERMINATED,
    }

    companion object {
        private val log = KotlinLogging.logger {}
    }

    private val lifecycleMonitor = Any()

    @Volatile
    private var state = State.NEW

    @Suppress("TooGenericExceptionCaught")
    override fun start() {
        val shouldStart = synchronized(lifecycleMonitor) {
            when (state) {
                State.NEW -> {
                    state = State.STARTING
                    true
                }

                State.RUNNING -> false
                State.STARTING -> error("Lifecycle monitor must serialize component registration.")
                State.TERMINATED -> restartNotSupported()
            }
        }
        if (!shouldStart) {
            return
        }
        log.info {
            "Start registering component:${componentType.simpleName}."
        }
        try {
            val components = applicationContext.getBeansWithAnnotation(componentType)
            components.forEach { entry ->
                val component = entry.value
                log.debug {
                    "Registering Component [$component]."
                }
                register(component)
            }
            synchronized(lifecycleMonitor) {
                check(state == State.STARTING) {
                    "Lifecycle state changed while components were being registered: $state."
                }
                state = State.RUNNING
            }
        } catch (error: Throwable) {
            synchronized(lifecycleMonitor) {
                state = State.TERMINATED
            }
            throw error
        }
    }

    abstract fun register(component: Any)

    override fun stop() {
        val stopped = synchronized(lifecycleMonitor) {
            when (state) {
                State.NEW,
                State.RUNNING,
                -> {
                    state = State.TERMINATED
                    true
                }

                State.TERMINATED -> false
                State.STARTING -> error("Lifecycle monitor must serialize component registration and shutdown.")
            }
        }
        if (stopped) {
            log.info {
                "Stop ${componentType.simpleName}."
            }
        }
    }

    private fun restartNotSupported(): Nothing =
        error(
            "${componentType.simpleName} auto registrar is one-shot and cannot restart after shutdown. " +
                "Create a new ApplicationContext instead.",
        )

    override fun isRunning(): Boolean = state == State.RUNNING

    override fun isPauseable(): Boolean = false

    override fun getPhase(): Int {
        return AUTO_REGISTRAR_PHASE
    }
}
