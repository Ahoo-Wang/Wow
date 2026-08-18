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

package me.ahoo.wow.spring.boot.starter.cosec

import me.ahoo.wow.cosec.appender.CoSecCommandRequestHeaderAppender
import me.ahoo.wow.cosec.extractor.CoSecCommandBuilderExtractor
import me.ahoo.wow.cosec.query.CoSecQueryPolicy
import me.ahoo.wow.cosec.query.CoSecRewriteRequestCondition
import me.ahoo.wow.spring.boot.starter.ConditionalOnWowEnabled
import me.ahoo.wow.webflux.route.command.appender.CommandRequestHeaderAppender
import me.ahoo.wow.webflux.route.command.extractor.CommandBuilderExtractor
import me.ahoo.wow.webflux.route.query.RewriteRequestCondition
import me.ahoo.wow.webflux.route.query.WebFluxQueryAuthorityResolver
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.context.annotation.Bean

@AutoConfiguration
@ConditionalOnWowEnabled
@ConditionalOnClass(CoSecCommandRequestHeaderAppender::class)
class CoSecAutoConfiguration {

    @Deprecated("Use the Spring-managed CoSecQueryPolicy bean with a trusted authority resolver.")
    fun coSecQueryPolicy(): CoSecQueryPolicy = CoSecQueryPolicy()

    @Bean
    @ConditionalOnBean(WebFluxQueryAuthorityResolver::class)
    fun coSecQueryPolicy(authorityResolver: WebFluxQueryAuthorityResolver): CoSecQueryPolicy {
        require(authorityResolver !== WebFluxQueryAuthorityResolver.SUBJECT) {
            "CoSec queries require a trusted query authority resolver."
        }
        return CoSecQueryPolicy()
    }

    @Bean
    fun coSecCommandRequestHeaderAppender(): CommandRequestHeaderAppender {
        return CoSecCommandRequestHeaderAppender
    }

    @Bean
    fun coSecCommandBuilderExtractor(): CommandBuilderExtractor {
        return CoSecCommandBuilderExtractor
    }

    @Bean
    @Deprecated("Use CoSecQueryPolicy with a verified QueryAuthorityProvider.")
    fun coSecRewriteRequestCondition(): RewriteRequestCondition {
        return CoSecRewriteRequestCondition
    }
}
