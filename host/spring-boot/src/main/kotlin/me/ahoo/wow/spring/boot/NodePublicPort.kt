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
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.client.CommandClient
import me.ahoo.wow.api.read.NodeQueryApi
import me.ahoo.wow.api.wire.DevelopmentHeaders
import me.ahoo.wow.http.WowHttpOptions
import me.ahoo.wow.http.caller.CallerResolver
import me.ahoo.wow.http.ops.parkedRoutes
import me.ahoo.wow.http.problem.GlobalErrors
import me.ahoo.wow.http.query.HttpQueries
import me.ahoo.wow.http.route.ServiceRoutes
import me.ahoo.wow.http.wowRoutes
import me.ahoo.wow.kernel.Dependencies
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.SystemCommands
import me.ahoo.wow.query.mask.PayloadMasks
import me.ahoo.wow.query.schema.MaskStrategyResolver
import me.ahoo.wow.runtime.admission.CommandAuthorizer
import me.ahoo.wow.runtime.admission.ServiceRouteAuthorizer
import me.ahoo.wow.runtime.node.ServiceNode
import me.ahoo.wow.runtime.state.StateReads
import me.ahoo.wow.spi.config.configCheck
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.ServerResponse

/**
 * The node's public port, on the application's WebFlux server: its routes ([wowRoutes]) and the operator's routes of the
 * parked entries ([parkedRoutes]), the callers they serve ([CallerResolver]), the options they answer by
 * ([WowHttpOptions]), their global errors and the masks of what they return ([PayloadMasks]). Imported by
 * [WowAutoConfiguration], whose order and conditions it shares.
 */
@Configuration(proxyBeanMethods = false)
internal class NodePublicPort {
    /**
     * The callers of a node without a `CallerResolver` bean, for development alone: a deployment maps the identity its
     * gateway asserts through one (CoSec's, with wow-cosec and `cosec.enabled`, or the application's), and an
     * intentionally anonymous service declares one that returns [CallerResolver.ANONYMOUS].
     *
     * - In a profile of `wow.dev-profiles`, the callers the request's identity headers name
     *   ([DevelopmentCallerResolver]): an explicit choice to run for development, so a client may name any caller.
     * - In development by a loopback internal port alone, the anonymous caller of `wow.tenant.default`: the internal
     *   port says nothing of who reaches the public one, so no header names a caller.
     * - Otherwise the node refuses to start.
     */
    @Bean
    @ConditionalOnMissingBean
    @InternalWowApi
    fun wowCallerResolver(properties: WowProperties, development: DevelopmentMode): CallerResolver {
        configCheck(
            development.active,
            "wow.dev-profiles",
            null,
            "Turn CoSec on (wow-cosec with cosec.enabled, such as a cosec profile) or declare a CallerResolver bean " +
                "that maps the identity your gateway asserts; for a local run, activate a profile of " +
                "wow.dev-profiles (dev). An intentionally anonymous service declares one returning " +
                "CallerResolver.ANONYMOUS.",
        ) {
            "No CallerResolver bean, and the node is not in development (neither a profile of wow.dev-profiles nor " +
                "a loopback internal port): the public port would have no caller to serve."
        }
        val tenant = properties.tenant.default
        if (development.byProfile) {
            logger.warn {
                "No CallerResolver bean, in the development profile ${development.profiles.joinToString()}: the public port takes " +
                    "its callers from the ${DevelopmentHeaders.USER}, ${DevelopmentHeaders.OWNER}, " +
                    "${DevelopmentHeaders.ROLES}, ${DevelopmentHeaders.TENANT} and " +
                    "${DevelopmentHeaders.APP} headers any client sets, and serves a request without " +
                    "${DevelopmentHeaders.USER} as the anonymous caller of tenant $tenant."
            }
            return DevelopmentCallerResolver(tenant)
        }
        logger.warn {
            "No CallerResolver bean, in development by its ${development.reason} alone: every request to the public " +
                "port is served as the anonymous caller (tenant $tenant, no scope). A profile of wow.dev-profiles " +
                "takes the callers from the ${DevelopmentHeaders.USER} headers instead."
        }
        if (tenant == Caller.DEFAULT_TENANT) {
            return CallerResolver.ANONYMOUS
        }
        val anonymous = Caller(tenantId = tenant)
        return CallerResolver { anonymous }
    }

    @Bean
    @ConditionalOnMissingBean
    @InternalWowApi
    fun wowHttpOptions(properties: WowProperties, environment: Environment): WowHttpOptions {
        val strategy = environment.getProperty(ForwardHeadersEnvironmentPostProcessor.FORWARD_HEADERS_STRATEGY)
        logger.info {
            "The public port reads the client's address with server.forward-headers-strategy=$strategy: " +
                "the gateway in front of it must overwrite X-Forwarded-For."
        }
        return WowHttpOptions(
            requestFacts = properties.http.requestFacts.toSet(),
            errorStatuses = properties.http.errorStatuses(),
            manifestEndpoint = properties.http.manifest.enabled,
            openApiEndpoint = properties.http.openapi.enabled,
            clientFacts = properties.http.clientFacts.enabled,
        )
    }

    /**
     * The error handling of the application's own routes: their failures answered as the
     * framework routes' problem+json, unless `wow.http.global-errors.enabled` is off.
     */
    @Bean
    @ConditionalOnProperty(
        prefix = "${WowProperties.PREFIX}.http",
        name = ["global-errors.enabled"],
        havingValue = "true",
        matchIfMissing = true,
    )
    @InternalWowApi
    fun wowGlobalErrors(manifest: Manifest, options: WowHttpOptions): GlobalErrors = GlobalErrors(manifest, options)

    /**
     * The response masks of the state reads and the `events` model: the Manifest's sensitive fields, with
     * the application's [MaskStrategyResolver] for its custom strategies, as the snapshot queries mask them.
     */
    @Bean
    @ConditionalOnMissingBean
    @InternalWowApi
    fun wowPayloadMasks(manifest: Manifest, strategies: ObjectProvider<MaskStrategyResolver>): PayloadMasks =
        PayloadMasks(manifest, strategies.getIfAvailable { MaskStrategyResolver.NONE })

    /**
     * The public routes, served by the application's WebFlux server; the query routes when [QueryAutoConfiguration]
     * gives them; the service routes the modules' model providers bind, each handler's instance its
     * bean, every call asked of the application's [ServiceRouteAuthorizer]s, and the handlers' commands sent through
     * the node's [CommandClient].
     */
    @Bean
    @InternalWowApi
    fun wowRouterFunction(
        manifest: Manifest,
        node: ServiceNode,
        reads: StateReads,
        callers: CallerResolver,
        options: WowHttpOptions,
        queries: ObjectProvider<HttpQueries>,
        masks: PayloadMasks,
        routes: RoutesScope,
        models: WowModels,
        dependencies: Dependencies,
        routeAuthorizers: ObjectProvider<ServiceRouteAuthorizer>,
        commands: ObjectProvider<CommandClient>,
        queryApi: ObjectProvider<NodeQueryApi>,
    ): RouterFunction<ServerResponse> {
        if (!node.gateway.authorizes && manifest.hasBuiltInSystemCommands()) {
            logger.info {
                "No CommandAuthorizer bean: the public port refuses Wow's system commands of its built-in aggregates " +
                    "(wow.schedule.*, wow.snapshot.Rebuild) with 403 until the application gives one; the jobs, the " +
                    "sagas and the internal port's calls send them as before."
            }
        }
        return wowRoutes(
            manifest,
            node.gateway,
            reads,
            callers,
            options,
            queries.getIfAvailable { HttpQueries.NONE },
            masks,
            routes.scope,
            ServiceRoutes(
                handlers = models.providers.flatMap { it.routes(dependencies) },
                authorizers = routeAuthorizers.orderedStream().toList(),
                commands = commands.getIfAvailable(),
                queries = queryApi.getIfAvailable(),
            ),
        )
    }

    /**
     * The operator's routes over the parked entries, when `wow.http.ops.enabled`: authenticated by the
     * application's [CallerResolver], each caller kept in its tenant, their actions authorized by its
     * [CommandAuthorizer], and refused when it has none.
     */
    @Bean
    @ConditionalOnProperty(prefix = "${WowProperties.PREFIX}.http.ops", name = ["enabled"], havingValue = "true")
    @InternalWowApi
    fun wowParkedRouterFunction(
        manifest: Manifest,
        node: ServiceNode,
        callers: CallerResolver,
        options: WowHttpOptions,
        authorizers: ObjectProvider<CommandAuthorizer>,
    ): RouterFunction<ServerResponse> {
        val authorizer = authorizers.orderedStream().toList().takeIf { it.isNotEmpty() }?.let(CommandAuthorizer::allOf)
        if (authorizer == null) {
            logger.warn {
                "wow.http.ops.enabled without a CommandAuthorizer bean: the operator's actions on parked entries are " +
                    "refused (403) until the application gives one."
            }
        }
        return parkedRoutes(node.parked, manifest, authorizer, callers, options)
    }

    internal companion object {
        /** Whether the Manifest routes a system command of a built-in aggregate: a job's or a snapshot rebuild. */
        internal fun Manifest.hasBuiltInSystemCommands(): Boolean = contexts.any { context ->
            context.commands.isNotEmpty() ||
                context.aggregates.any { aggregate -> aggregate.commands.any { it.name == SystemCommands.REBUILD } }
        }

        /** The host's log category: the lines that moved here from [WowAutoConfiguration] keep it. */
        private val logger = KotlinLogging.logger(WowAutoConfiguration::class.java.name)
    }
}
