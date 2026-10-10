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
import me.ahoo.wow.openapi.RouterSpecs
import org.springdoc.core.customizers.OpenApiCustomizer
import org.springframework.core.annotation.Order

/**
 * Merges Wow's routes into the OpenAPI document. It runs at [ORDER], before the customizers that declare no order, so
 * a customizer that changes Wow's routes declares a later order (`ORDER + 1`, for example) or none.
 */
@Order(WowOpenApiCustomizer.ORDER)
class WowOpenApiCustomizer(private val routerSpecs: RouterSpecs) : OpenApiCustomizer {
    override fun customise(openApi: OpenAPI) {
        routerSpecs.mergeOpenAPI(openApi)
    }

    companion object {
        /** The order springdoc applies this customizer in, among the other `OpenApiCustomizer`s. */
        const val ORDER: Int = 0
    }
}
