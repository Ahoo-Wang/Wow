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

import me.ahoo.wow.compensation.server.configuration.CompensationProperties
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=790e4032-6e92-4c72-b7d8-d007bf19f7a2
 */
@ConfigurationProperties(prefix = WeiXinWebHookProperties.PREFIX)
data class WeiXinWebHookProperties(
    val url: String,
    val events: Set<HookEvent> = setOf(
        HookEvent.EXECUTION_FAILED_CREATED,
        HookEvent.EXECUTION_FAILED_APPLIED,
        HookEvent.EXECUTION_SUCCESS_APPLIED,
        HookEvent.COMPENSATION_PREPARED,
    ),

) {
    companion object {
        const val PREFIX = CompensationProperties.PREFIX + ".webhook.weixin"
        const val URL_KEY = "$PREFIX.url"
    }
}

enum class HookEvent(val value: String) {
    EXECUTION_FAILED_CREATED("execution_failed_created"),
    EXECUTION_FAILED_APPLIED("execution_failed_applied"),
    EXECUTION_SUCCESS_APPLIED("execution_success_applied"),
    COMPENSATION_PREPARED("compensation_prepared"),
}
