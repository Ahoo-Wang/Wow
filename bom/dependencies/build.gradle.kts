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

dependencies {
    api(platform(libs.spring.boot.dependencies))
    api(platform(libs.kotlinx.coroutines.bom))
    api(platform(libs.jsonschema.generator.bom))
    api(platform(libs.cosid.bom))
    api(platform(libs.opentelemetry.bom))
    api(platform(libs.opentelemetry.instrumentation.bom))
    api(platform(libs.springdoc.bom))
    api(platform(libs.fluent.assert.bom))
    api(platform(libs.testcontainers.bom))
    constraints {
        // Guava reaches :clickhouse's runtime through com.clickhouse:client-v2 (33.4.6); this lifts it.
        api(libs.guava)
        api(libs.kotlin.logging)
        api(libs.swagger.annotations)
        api(libs.swagger.core)
        api(libs.mockk)
        api(libs.detekt.formatting)
    }
}
