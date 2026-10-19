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

package me.ahoo.wow.spring.boot

import io.github.oshai.kotlinlogging.KotlinLogging
import io.swagger.v3.core.util.Json31
import io.swagger.v3.oas.models.OpenAPI
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.http.WowHttpOptions
import me.ahoo.wow.http.caller.CallerResolver
import me.ahoo.wow.http.query.HttpQueries
import me.ahoo.wow.http.wowOpenApi
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.runtime.node.ServiceNode
import org.springdoc.api.AbstractOpenApiResource
import org.springdoc.core.customizers.OpenApiCustomizer
import org.springdoc.core.models.GroupedOpenApi
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.util.ReflectionUtils
import java.util.Locale

/**
 * The springdoc bridge (decision D233, gap G-14): with springdoc on the classpath, the OpenAPI document of the public
 * routes, the one `GET /wow/openapi` answers ([wowOpenApi]), joins springdoc's, so that `/v3/api-docs` and Swagger UI
 * list the application's own controllers and the Wow routes together.
 *
 * - [wowOpenApiCustomizer] merges the Wow document into springdoc's default document ([SpringdocMerge]): a route
 *   both define (path and method) or a component name both use for different definitions fails the startup, since
 *   [wowSpringdocStartupCheck] renders the default document once the beans are ready, as springdoc's pre-loading does.
 * - [wowGroupedOpenApi] is the group `wow.http.openapi.springdoc.group` (`wow`, `/v3/api-docs/wow`): the Wow routes
 *   alone, none of the application's.
 * - The document's security schemes and global `security` are the [WowOpenApiSecurity] bean's: the host's from its
 *   [CallerResolver] (CoSec's bearer token), unless the application gives its own.
 * - The document has the public port's routes alone: the internal port's call routes, cluster channel and signals are
 *   never in it (decision D226). `/v3/api-docs` and Swagger UI are springdoc's, guarded by the application's security
 *   configuration; `GET /wow/openapi` is unchanged.
 *
 * `wow.http.openapi.springdoc.enabled=false` turns the bridge off.
 */
@AutoConfiguration(after = [WowAutoConfiguration::class], beforeName = [SPRINGDOC_CONFIGURATION])
@ConditionalOnClass(name = [SPRINGDOC_CUSTOMIZER, SWAGGER_JSON31])
@ConditionalOnBean(ServiceNode::class)
@ConditionalOnProperty(
    prefix = "${WowProperties.PREFIX}.http.openapi.springdoc",
    name = ["enabled"],
    matchIfMissing = true,
)
class SpringdocAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun wowOpenApiSecurity(callers: CallerResolver): WowOpenApiSecurity = WowOpenApiSecurity.of(callers).also {
        if (it.schemes.isEmpty()) {
            logger.info {
                "The springdoc document declares no security scheme for the caller resolver " +
                    "${callers.javaClass.name}: give a WowOpenApiSecurity bean to have Swagger UI send an identity."
            }
        }
    }

    /** The Wow document, rendered once at startup; each springdoc document reads a copy of it. */
    @Bean
    @InternalWowApi
    fun wowSpringdocDocument(
        manifest: Manifest,
        node: ServiceNode,
        options: WowHttpOptions,
        queries: ObjectProvider<HttpQueries>,
        security: WowOpenApiSecurity,
    ): SpringdocDocument = SpringdocDocument(
        wowOpenApi(manifest, node.gateway, options, queries.getIfAvailable { HttpQueries.NONE }, security.schemes),
    )

    /** Merges the Wow routes into springdoc's default document (`/v3/api-docs`). */
    @Bean
    fun wowOpenApiCustomizer(document: SpringdocDocument): OpenApiCustomizer = document.customizer()

    /** The group of the Wow routes alone: none of the application's controllers or router functions. */
    @Bean
    fun wowGroupedOpenApi(document: SpringdocDocument, properties: WowProperties): GroupedOpenApi {
        val group = properties.http.openapi.springdoc.group
        require(group.isNotBlank()) { "wow.http.openapi.springdoc.group names the group of the Wow routes." }
        return GroupedOpenApi.builder()
            .group(group)
            .displayName("Wow routes")
            .pathsToExclude("/**")
            .addOpenApiMethodFilter { false }
            .addOpenApiCustomizer(document.customizer())
            .build()
    }

    /**
     * Renders springdoc's default document once every bean is ready, so that a conflict with the Wow routes fails the
     * startup rather than the first request; springdoc keeps the document it rendered.
     */
    @Bean
    @InternalWowApi
    fun wowSpringdocStartupCheck(resources: ObjectProvider<AbstractOpenApiResource>): SmartInitializingSingleton =
        SmartInitializingSingleton {
            resources.orderedStream().forEach(SpringdocDocument::render)
        }

    companion object {
        /** The group of the Wow routes by default. */
        const val DEFAULT_GROUP: String = "wow"
        private val logger = KotlinLogging.logger {}
    }
}

/** The Wow document as plain data, read into swagger-core's model for each springdoc document. */
@InternalWowApi
class SpringdocDocument(private val document: Map<String, Any?>) {
    /** A copy of the Wow document in swagger-core's OpenAPI 3.1 model. */
    fun openApi(): OpenAPI = Json31.mapper().convertValue(document, OpenAPI::class.java)

    /** Merges a copy of the Wow document into each document springdoc builds. */
    fun customizer(): OpenApiCustomizer = OpenApiCustomizer { openApi -> SpringdocMerge.merge(openApi, openApi()) }

    internal companion object {
        private val GET_OPEN_API = ReflectionUtils.findMethod(
            AbstractOpenApiResource::class.java,
            "getOpenApi",
            String::class.java,
            Locale::class.java,
        )?.also(ReflectionUtils::makeAccessible)

        /**
         * Renders [resource]'s document as springdoc's pre-loading does (`getOpenApi(null, locale)`, protected), its
         * failure thrown; skipped, with a warning, by a springdoc that no longer has that method.
         */
        fun render(resource: AbstractOpenApiResource) {
            val method = GET_OPEN_API ?: run {
                logger.warn { "springdoc has no getOpenApi(String, Locale): the Wow routes are merged on request." }
                return
            }
            ReflectionUtils.invokeMethod(method, resource, null, Locale.getDefault())
        }

        private val logger = KotlinLogging.logger {}
    }
}

private const val SPRINGDOC_CONFIGURATION = "org.springdoc.core.configuration.SpringDocConfiguration"
private const val SPRINGDOC_CUSTOMIZER = "org.springdoc.core.customizers.OpenApiCustomizer"
private const val SWAGGER_JSON31 = "io.swagger.v3.core.util.Json31"
