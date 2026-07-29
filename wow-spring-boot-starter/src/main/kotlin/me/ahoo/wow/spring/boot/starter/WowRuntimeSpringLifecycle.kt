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

package me.ahoo.wow.spring.boot.starter

import me.ahoo.wow.spring.WOW_RUNTIME_PHASE
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.context.support.AbstractApplicationContext
import org.springframework.context.support.DefaultLifecycleProcessor
import org.springframework.core.Ordered
import org.springframework.core.PriorityOrdered
import java.time.Duration

internal fun DefaultLifecycleProcessor.configureWowRuntimePhaseTimeout(
    shutdownTimeout: Duration,
) {
    setTimeoutForShutdownPhase(
        WOW_RUNTIME_PHASE,
        shutdownTimeout.plus(SHUTDOWN_PHASE_TIMEOUT_MARGIN).toMillis(),
    )
}

/**
 * Aligns a user-provided Spring lifecycle processor with the runtime deadline.
 *
 * Custom lifecycle processor implementations retain responsibility for their
 * own phase timeout policy.
 */
internal class WowRuntimeLifecycleProcessorCustomizer(
    private val shutdownTimeout: Duration,
) : BeanPostProcessor,
    PriorityOrdered {
    override fun postProcessBeforeInitialization(bean: Any, beanName: String): Any {
        if (
            beanName == AbstractApplicationContext.LIFECYCLE_PROCESSOR_BEAN_NAME &&
            bean is DefaultLifecycleProcessor
        ) {
            bean.configureWowRuntimePhaseTimeout(shutdownTimeout)
        }
        return bean
    }

    override fun getOrder(): Int = Ordered.HIGHEST_PRECEDENCE
}

const val WOW_RUNTIME_BEAN_NAME = "wowRuntime"
const val WOW_RUNTIME_LIFECYCLE_BEAN_NAME = "wowRuntimeLifecycle"
internal const val WOW_RUNTIME_LIFECYCLE_PROCESSOR_CUSTOMIZER_BEAN_NAME =
    "wowRuntimeLifecycleProcessorCustomizer"
private val SHUTDOWN_PHASE_TIMEOUT_MARGIN: Duration = Duration.ofSeconds(1)
