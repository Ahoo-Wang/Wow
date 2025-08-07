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
package me.ahoo.wow.spring.boot.starter.prepare

import me.ahoo.wow.infra.prepare.PrepareKeyFactory
import me.ahoo.wow.infra.prepare.proxy.DefaultPrepareKeyProxyFactory
import me.ahoo.wow.infra.prepare.proxy.PrepareKeyProxyFactory
import me.ahoo.wow.spring.boot.starter.ConditionalOnWowEnabled
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import

@AutoConfiguration
@ConditionalOnWowEnabled
@ConditionalOnPrepareEnabled
@EnableConfigurationProperties(PrepareProperties::class)
@Import(PrepareKeyAutoRegistrar::class)
class PrepareAutoConfiguration {

    @Bean
    fun applicationBasePackageScanner(): ApplicationBasePackageScanner {
        return ApplicationBasePackageScanner()
    }

    @Bean
    @ConditionalOnBean(PrepareKeyFactory::class)
    fun prepareKeyProxyFactory(prepareKeyFactory: PrepareKeyFactory): PrepareKeyProxyFactory {
        return DefaultPrepareKeyProxyFactory(prepareKeyFactory)
    }

    @Bean
    fun prepareKeyInitializer(): PrepareKeyInitializer {
        return PrepareKeyInitializer()
    }
}
