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

package me.ahoo.wow.spring.boot.starter.opentelemetry

import me.ahoo.wow.api.Wow
import me.ahoo.wow.spring.boot.starter.ENABLED_SUFFIX_KEY
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty

@ConditionalOnClass(name = ["me.ahoo.wow.opentelemetry.WowInstrumenter"])
@ConditionalOnProperty(
    value = [ConditionalOnOpenTelemetryEnabled.ENABLED_KEY],
    matchIfMissing = true,
    havingValue = "true",
)
annotation class ConditionalOnOpenTelemetryEnabled {
    companion object {
        const val ENABLED_KEY: String = Wow.WOW_PREFIX + "opentelemetry" + ENABLED_SUFFIX_KEY
    }
}
