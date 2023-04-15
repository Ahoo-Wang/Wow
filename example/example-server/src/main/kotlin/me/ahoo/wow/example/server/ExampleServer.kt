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

import me.ahoo.wow.api.annotation.BoundedContext
import me.ahoo.wow.example.api.order.OrderService
import me.ahoo.wow.example.domain.cart.CartBoundedContext
import me.ahoo.wow.example.domain.order.OrderBoundedContext
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@BoundedContext(name = OrderService.SERVICE_NAME)
@SpringBootApplication(
    scanBasePackageClasses = [CartBoundedContext::class,OrderBoundedContext::class, ExampleServer::class]
)
class ExampleServer

fun main(args: Array<String>) {
    runApplication<ExampleServer>(*args)
}
