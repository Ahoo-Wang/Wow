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

package me.ahoo.wow.example.server

import me.ahoo.coapi.spring.EnableCoApi
import me.ahoo.wow.api.annotation.BoundedContext
import me.ahoo.wow.apiclient.RestCommandGateway
import me.ahoo.wow.example.api.ExampleService
import me.ahoo.wow.example.domain.ExampleBoundedContext
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@EnableCoApi(clients = [RestCommandGateway::class])
@BoundedContext(name = ExampleService.SERVICE_NAME)
@SpringBootApplication(
    scanBasePackageClasses = [ExampleBoundedContext::class, ExampleServer::class],
)
class ExampleServer

fun main(args: Array<String>) {
//    val loggingRegistryConfig = object : LoggingRegistryConfig {
//        override fun get(key: String): String? = null
//        override fun step(): Duration {
//            return Duration.ofSeconds(10)
//        }
//    }
//    val loggingMeterRegistry = LoggingMeterRegistry.builder(loggingRegistryConfig).build()
//    Metrics.addRegistry(loggingMeterRegistry)
    runApplication<ExampleServer>(*args)
}
