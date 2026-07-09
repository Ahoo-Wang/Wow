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

import me.ahoo.wow.spring.boot.starter.bi.BiScriptObjectMapStrategy
import me.ahoo.wow.spring.boot.starter.bi.BiScriptProperties
import me.ahoo.wow.spring.boot.starter.bi.BiScriptUnsupportedTypeStrategy
import me.ahoo.wow.spring.boot.starter.kafka.KafkaProperties
import me.ahoo.wow.webflux.route.HttpRouteHandlerFunctionFactory
import me.ahoo.wow.webflux.route.global.BiScriptRouteObjectMapStrategy
import me.ahoo.wow.webflux.route.global.BiScriptRouteOptions
import me.ahoo.wow.webflux.route.global.BiScriptRouteUnsupportedTypeStrategy
import me.ahoo.wow.webflux.route.global.GenerateBIScriptHandlerFunctionFactory
import me.ahoo.wow.webflux.route.global.GetWowMetadataHandlerFunctionFactory
import me.ahoo.wow.webflux.route.global.GlobalIdHandlerFunctionFactory

class GlobalRouteModule : WebFluxRouteModule {
    override val httpFactories: List<HttpRouteHandlerFunctionFactory>

    constructor(kafkaProperties: KafkaProperties?) {
        val biScriptRouteFactory = if (kafkaProperties == null) {
            GenerateBIScriptHandlerFunctionFactory()
        } else {
            GenerateBIScriptHandlerFunctionFactory(
                kafkaBootstrapServers = kafkaProperties.bootstrapServersToString(),
                topicPrefix = kafkaProperties.topicPrefix
            )
        }
        httpFactories = routeFactories(biScriptRouteFactory)
    }

    constructor(kafkaProperties: KafkaProperties?, biScriptProperties: BiScriptProperties) {
        val biScriptRouteOptions = biScriptProperties.toRouteOptions(kafkaProperties)
        httpFactories = routeFactories(GenerateBIScriptHandlerFunctionFactory(biScriptRouteOptions))
    }

    private fun routeFactories(
        biScriptRouteFactory: GenerateBIScriptHandlerFunctionFactory
    ): List<HttpRouteHandlerFunctionFactory> = listOf(
        GlobalIdHandlerFunctionFactory(),
        biScriptRouteFactory,
        GetWowMetadataHandlerFunctionFactory(),
    )

    private fun BiScriptProperties.toRouteOptions(kafkaProperties: KafkaProperties?): BiScriptRouteOptions {
        return BiScriptRouteOptions(
            database = database,
            consumerDatabase = consumerDatabase,
            cluster = cluster,
            installation = installation,
            shard = shard,
            replica = replica,
            timezone = timezone,
            kafkaBootstrapServers = kafkaBootstrapServers ?: kafkaProperties?.bootstrapServersToString(),
            topicPrefix = topicPrefix ?: kafkaProperties?.topicPrefix,
            maxExpansionDepth = maxExpansionDepth,
            unsupportedTypeStrategy = unsupportedTypeStrategy.toRouteStrategy(),
            objectMapStrategy = objectMapStrategy.toRouteStrategy(),
        )
    }

    private fun BiScriptUnsupportedTypeStrategy?.toRouteStrategy(): BiScriptRouteUnsupportedTypeStrategy? =
        when (this) {
            null -> null
            BiScriptUnsupportedTypeStrategy.FAIL -> BiScriptRouteUnsupportedTypeStrategy.FAIL
            BiScriptUnsupportedTypeStrategy.STRING_WITH_DIAGNOSTIC ->
                BiScriptRouteUnsupportedTypeStrategy.STRING_WITH_DIAGNOSTIC
        }

    private fun BiScriptObjectMapStrategy?.toRouteStrategy(): BiScriptRouteObjectMapStrategy? =
        when (this) {
            null -> null
            BiScriptObjectMapStrategy.STRING_VALUE_WITH_DIAGNOSTIC ->
                BiScriptRouteObjectMapStrategy.STRING_VALUE_WITH_DIAGNOSTIC
            BiScriptObjectMapStrategy.FAIL -> BiScriptRouteObjectMapStrategy.FAIL
        }
}
