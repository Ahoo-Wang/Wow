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

package me.ahoo.wow.spring.boot.starter.openapi

import io.swagger.v3.oas.models.OpenAPI
import me.ahoo.wow.api.naming.NamedBoundedContext
import me.ahoo.wow.openapi.RouterSpecs
import me.ahoo.wow.spring.boot.starter.WowAutoConfiguration.Companion.WOW_CURRENT_BOUNDED_CONTEXT
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

@AutoConfiguration
@ConditionalOnOpenAPIEnabled
@EnableConfigurationProperties(OpenAPIProperties::class)
class OpenAPIAutoConfiguration {

    @Bean
    fun router(
        @Qualifier(WOW_CURRENT_BOUNDED_CONTEXT) boundedContext: NamedBoundedContext
    ): RouterSpecs {
        return RouterSpecs(boundedContext).build()
    }

    @Bean
    fun openAPI(routerSpecs: RouterSpecs): OpenAPI {
        return routerSpecs.openAPI()
    }
}
