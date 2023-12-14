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

package me.ahoo.wow.compensation.server

import me.ahoo.wow.api.annotation.BoundedContext
import me.ahoo.wow.compensation.api.CompensationService
import me.ahoo.wow.compensation.domain.CompensationBoundedContext
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@BoundedContext(name = CompensationService.SERVICE_NAME)
@SpringBootApplication(
    scanBasePackageClasses = [CompensationBoundedContext::class, CompensationServer::class],
)
class CompensationServer

fun main(args: Array<String>) {
    runApplication<CompensationServer>(*args)
}