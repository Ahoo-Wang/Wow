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

import me.ahoo.cosec.context.DefaultSecurityContextParser
import me.ahoo.cosec.context.SecurityContextParser
import me.ahoo.cosec.token.TokenVerifier
import me.ahoo.wow.webflux.route.dataset.DatasetQueryRequestScope
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * CoSec in the analytics service (analytics design §4): the request's token is verified with the service's JWT secret
 * (CoSec's `TokenVerifier`), not merely decoded as behind a gateway, and its principal — roles, tenant and attributes
 * — is what the access filter, the dataset routes' tenant and the ABAC tags read. CoSec's own policy authorization
 * stays available (`cosec.authorization.enabled`) for an enterprise that runs its policy repositories.
 */
@Configuration(proxyBeanMethods = false)
class AnalyticsSecurityConfiguration {
    /** Replaces the injecting parser of CoSec's inject mode, which trusts any well-formed token. */
    @Bean
    fun securityContextParser(tokenVerifier: TokenVerifier): SecurityContextParser =
        DefaultSecurityContextParser(tokenVerifier, false)

    @Bean
    fun analyticsAccessFilter(properties: AnalyticsProperties): AnalyticsAccessFilter =
        AnalyticsAccessFilter(properties)

    @Bean
    fun consolePagesFilter(properties: AnalyticsProperties): ConsolePagesFilter = ConsolePagesFilter(properties)

    @Bean
    fun datasetQueryRequestScope(): DatasetQueryRequestScope = CoSecDatasetQueryRequestScope()
}
