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
import org.springframework.context.SmartLifecycle.DEFAULT_PHASE
import java.util.concurrent.atomic.AtomicBoolean

/**
 * must before Launcher
 * @see MessageDispatcherLauncher
 */
const val AUTO_REGISTRAR_PHASE = DEFAULT_PHASE - 100

abstract class AutoRegistrar<CM : Annotation>(
    private val componentType: Class<CM>,
    private val applicationContext: ApplicationContext
) : SmartLifecycle {
    companion object {
        private val log = KotlinLogging.logger {}
    }

    private val running = AtomicBoolean(false)

    override fun start() {
        log.info {
            "Start registering component:${componentType.simpleName}."
        }
        if (!running.compareAndSet(false, true)) {
            return
        }
        val components = applicationContext.getBeansWithAnnotation(componentType)
        components.forEach { entry ->
            val component = entry.value
            log.debug {
                "Registering Component [$component]."
            }
            register(component)
        }
    }

    abstract fun register(component: Any)

    override fun stop() {
        log.info {
            "Stop ${componentType.simpleName}."
        }
        running.compareAndSet(true, false)
    }

    override fun isRunning(): Boolean {
        return running.get()
    }

    override fun getPhase(): Int {
        return AUTO_REGISTRAR_PHASE
    }
}
