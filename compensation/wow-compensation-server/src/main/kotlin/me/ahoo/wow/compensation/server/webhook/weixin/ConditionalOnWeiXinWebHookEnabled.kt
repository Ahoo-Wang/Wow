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

package me.ahoo.wow.compensation.server.webhook.weixin

import me.ahoo.wow.compensation.server.webhook.weixin.WeiXinWebHookProperties.Companion.URL_KEY
import org.springframework.boot.autoconfigure.condition.ConditionOutcome
import org.springframework.boot.autoconfigure.condition.SpringBootCondition
import org.springframework.context.annotation.ConditionContext
import org.springframework.context.annotation.Conditional
import org.springframework.core.type.AnnotatedTypeMetadata

/**
 * Matches when [URL_KEY] is set to a non-blank value other than `false`.
 */
@Conditional(OnWeiXinWebHookEnabledCondition::class)
annotation class ConditionalOnWeiXinWebHookEnabled

class OnWeiXinWebHookEnabledCondition : SpringBootCondition() {
    override fun getMatchOutcome(context: ConditionContext, metadata: AnnotatedTypeMetadata): ConditionOutcome {
        val url = context.environment.getProperty(URL_KEY)
        if (url.isNullOrBlank() || url.equals("false", ignoreCase = true)) {
            return ConditionOutcome.noMatch("$URL_KEY is absent, blank or false.")
        }
        return ConditionOutcome.match("$URL_KEY is set.")
    }
}
