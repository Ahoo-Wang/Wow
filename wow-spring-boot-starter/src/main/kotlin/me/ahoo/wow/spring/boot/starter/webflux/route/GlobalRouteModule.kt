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

package me.ahoo.wow.spring.boot.starter.webflux.route

import me.ahoo.wow.bi.BiScriptOptions
import me.ahoo.wow.webflux.route.HttpRouteHandlerFunctionFactory
import me.ahoo.wow.webflux.route.global.GenerateBIScriptHandlerFunctionFactory
import me.ahoo.wow.webflux.route.global.GetWowMetadataHandlerFunctionFactory
import me.ahoo.wow.webflux.route.global.GlobalIdHandlerFunctionFactory

internal class GlobalRouteModule(options: BiScriptOptions, biScriptEnabled: Boolean) : WebFluxRouteModule {
    override val httpFactories: List<HttpRouteHandlerFunctionFactory> = buildList {
        add(GlobalIdHandlerFunctionFactory())
        if (biScriptEnabled) {
            requireNotNull(options.consumerGroupNamespace) {
                "${me.ahoo.wow.spring.boot.starter.bi.BiScriptProperties.PREFIX}.consumer-group-namespace " +
                    "must be configured when BI script generation is enabled"
            }
            add(GenerateBIScriptHandlerFunctionFactory(options))
        }
        add(GetWowMetadataHandlerFunctionFactory())
    }
}
