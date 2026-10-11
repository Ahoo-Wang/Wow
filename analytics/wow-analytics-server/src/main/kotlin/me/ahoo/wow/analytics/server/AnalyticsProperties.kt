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

package me.ahoo.wow.analytics.server

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.DefaultValue

/**
 * The analytics service's own settings.
 *
 * @property adminRole the CoSec role that may change the dataset registry: draft, revise, review, publish and retire.
 * @property sharedViewRole the CoSec role that may write the tenant's shared views, and claim or share one.
 * @property systemViewRole the CoSec role a principal of the platform tenant needs to write the system views.
 * @property appId the application the view store keeps this service's views under: the server sets `CoSec-App-Id` to
 * it on every view store request, whatever the request sends.
 * @property publicPaths the paths anyone may read without a token, beside the health probes: the console's static
 * files and the API documentation. Every other path needs an authenticated principal.
 */
@ConfigurationProperties(prefix = AnalyticsProperties.PREFIX)
class AnalyticsProperties(
    @DefaultValue(DEFAULT_ADMIN_ROLE)
    var adminRole: String = DEFAULT_ADMIN_ROLE,
    @DefaultValue(DEFAULT_ADMIN_ROLE)
    var sharedViewRole: String = DEFAULT_ADMIN_ROLE,
    @DefaultValue(DEFAULT_SYSTEM_VIEW_ROLE)
    var systemViewRole: String = DEFAULT_SYSTEM_VIEW_ROLE,
    @DefaultValue(DEFAULT_APP_ID)
    var appId: String = DEFAULT_APP_ID,
    var publicPaths: List<String> = DEFAULT_PUBLIC_PATHS,
) {
    companion object {
        const val PREFIX = "wow.analytics"
        const val DEFAULT_ADMIN_ROLE = "analytics-admin"
        const val DEFAULT_SYSTEM_VIEW_ROLE = "admin"
        const val DEFAULT_APP_ID = "analytics"
        val DEFAULT_PUBLIC_PATHS = listOf(
            "/",
            "/index.html",
            "/assets/**",
            "/favicon.ico",
            "/v3/api-docs/**",
            "/swagger-ui.html",
            "/swagger-ui/**",
            "/webjars/**",
        )
    }
}
