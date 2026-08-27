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

package me.ahoo.wow.spring.boot.starter.metrics

import me.ahoo.wow.api.Wow
import me.ahoo.wow.spring.boot.starter.ENABLED_SUFFIX_KEY
import org.springframework.context.annotation.Condition
import org.springframework.context.annotation.ConditionContext
import org.springframework.context.annotation.Conditional
import org.springframework.core.env.Environment
import org.springframework.core.type.AnnotatedTypeMetadata

@Conditional(MetricsEnabledCondition::class)
annotation class ConditionalOnMetricsEnabled {
    companion object {
        const val ENABLED_KEY: String = Wow.WOW_PREFIX + "metrics" + ENABLED_SUFFIX_KEY
    }
}

internal class MetricsEnabledCondition : Condition {
    override fun matches(
        context: ConditionContext,
        metadata: AnnotatedTypeMetadata,
    ): Boolean = context.environment.isMetricsEnabled()
}

internal fun Environment.isMetricsEnabled(): Boolean = getProperty(
    ConditionalOnMetricsEnabled.ENABLED_KEY,
    Boolean::class.java,
    true,
)
