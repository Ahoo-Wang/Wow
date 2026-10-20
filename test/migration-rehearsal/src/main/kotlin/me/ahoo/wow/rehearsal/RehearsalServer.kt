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

package me.ahoo.wow.rehearsal

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/** The V10 side of the blue-green rehearsal (roadmap 8c.1): the 9.x example ported, on the Spring Boot host. */
@SpringBootApplication(proxyBeanMethods = false)
class RehearsalServer

fun main(args: Array<String>) {
    runApplication<RehearsalServer>(*args)
}
