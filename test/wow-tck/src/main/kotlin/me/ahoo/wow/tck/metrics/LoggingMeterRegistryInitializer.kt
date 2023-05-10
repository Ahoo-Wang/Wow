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

package me.ahoo.wow.tck.metrics

import io.micrometer.core.instrument.Metrics
import io.micrometer.core.instrument.logging.LoggingMeterRegistry
import io.micrometer.core.instrument.logging.LoggingRegistryConfig
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.ExtensionContext
import java.time.Duration
import kotlin.reflect.jvm.isAccessible

object LoggingMeterRegistryInitializer : BeforeAllCallback {
    val loggingMeterRegistry: LoggingMeterRegistry
    private val publishCallable = LoggingMeterRegistry::class.members.first { it.name == "publish" }.apply {
        isAccessible = true
    }

    init {
        val loggingRegistryConfig = object : LoggingRegistryConfig {
            override fun get(key: String): String? = null
            override fun step(): Duration {
                return Duration.ofSeconds(1)
            }
        }
        loggingMeterRegistry = LoggingMeterRegistry.builder(loggingRegistryConfig).build()
        Metrics.addRegistry(loggingMeterRegistry)
    }

    fun publishMeters() {
        publishCallable.call(loggingMeterRegistry)
    }

    override fun beforeAll(context: ExtensionContext) {
        //
    }
}
