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

package me.ahoo.wow.spring.boot.starter.query

import me.ahoo.wow.query.schema.BeanQuerySchemaSource
import me.ahoo.wow.query.schema.ClasspathQuerySchemaSource
import me.ahoo.wow.query.schema.QuerySchemaRegistration
import me.ahoo.wow.query.schema.QuerySchemaValidationMode
import me.ahoo.wow.query.schema.WorkingDirectoryQuerySchemaSource
import me.ahoo.wow.schema.query.JsonQuerySchemaSource
import me.ahoo.wow.spring.boot.starter.ConditionalOnWowEnabled
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

@AutoConfiguration
@ConditionalOnWowEnabled
@EnableConfigurationProperties(QueryProperties::class)
class QuerySchemaAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(QuerySchemaValidationMode::class)
    fun querySchemaValidationMode(properties: QueryProperties): QuerySchemaValidationMode =
        properties.schema.validationMode

    @Bean
    fun jsonQuerySchemaSource(): JsonQuerySchemaSource = JsonQuerySchemaSource()

    @Bean
    fun workingDirectoryQuerySchemaSource(): WorkingDirectoryQuerySchemaSource = WorkingDirectoryQuerySchemaSource()

    @Bean
    fun classpathQuerySchemaSource(): ClasspathQuerySchemaSource = ClasspathQuerySchemaSource()

    @Bean
    fun beanQuerySchemaSource(
        registrations: ObjectProvider<QuerySchemaRegistration>,
    ): BeanQuerySchemaSource = BeanQuerySchemaSource(registrations.toList())
}
