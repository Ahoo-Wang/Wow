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

package me.ahoo.wow.spring.boot.starter

import org.springframework.boot.test.context.runner.ApplicationContextRunner

fun ApplicationContextRunner.enableWowProperties(): ApplicationContextRunner = withPropertyValues(
    "wow.enabled=true",
    "wow.context-name=wow-spring-boot-starter-test",
)

fun ApplicationContextRunner.enableWow(): ApplicationContextRunner = enableWowProperties()
    .withUserConfiguration(WowAutoConfiguration::class.java)
