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
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.context.support.DefaultLifecycleProcessor
import java.time.Duration

private val SHUTDOWN_PHASE_TIMEOUT_MARGIN: Duration = Duration.ofSeconds(1)

internal fun DefaultLifecycleProcessor.configureWowRuntimePhaseTimeout(
    shutdownTimeout: Duration,
) {
    setTimeoutForShutdownPhase(
        WOW_RUNTIME_PHASE,
        shutdownTimeout.plus(SHUTDOWN_PHASE_TIMEOUT_MARGIN).toMillis(),
    )
}

internal class WowRuntimeLifecycleProcessorConfigurer(
    private val lifecycleProcessorProvider: ObjectProvider<DefaultLifecycleProcessor>,
    private val shutdownTimeout: Duration,
) : SmartInitializingSingleton {
    override fun afterSingletonsInstantiated() {
        lifecycleProcessorProvider.getIfAvailable()
            ?.configureWowRuntimePhaseTimeout(shutdownTimeout)
    }
}
