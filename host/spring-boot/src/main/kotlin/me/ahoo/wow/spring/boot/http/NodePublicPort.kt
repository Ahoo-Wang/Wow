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

package me.ahoo.wow.spring.boot.http

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.client.CommandClient
import me.ahoo.wow.api.problem.ProblemMapper
import me.ahoo.wow.api.read.NodeQueryApi
import me.ahoo.wow.api.wire.DevelopmentHeaders
import me.ahoo.wow.http.WowHttpOptions
import me.ahoo.wow.http.caller.CallerResolver
import me.ahoo.wow.http.ops.parkedRoutes
import me.ahoo.wow.http.problem.GlobalErrors
import me.ahoo.wow.http.problem.ServerRefusals
import me.ahoo.wow.http.query.HttpQueries
import me.ahoo.wow.http.route.ServiceRoutes
import me.ahoo.wow.http.wowRoutes
import me.ahoo.wow.kernel.Dependencies
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.query.mask.PayloadMasks
import me.ahoo.wow.query.schema.MaskStrategyResolver
import me.ahoo.wow.runtime.admission.CommandAuthorizer
import me.ahoo.wow.runtime.admission.ServiceRouteAuthorizer
import me.ahoo.wow.runtime.node.ServiceNode
import me.ahoo.wow.runtime.state.StateReads
import me.ahoo.wow.spi.SystemCommandGroups
import me.ahoo.wow.spi.config.configCheck
import me.ahoo.wow.spring.boot.WowAutoConfiguration
import me.ahoo.wow.spring.boot.WowProperties
import me.ahoo.wow.spring.boot.config.DevelopmentMode
import me.ahoo.wow.spring.boot.node.RoutesScope
import me.ahoo.wow.spring.boot.node.WowModels
import me.ahoo.wow.spring.boot.query.QueryAutoConfiguration
import me.ahoo.wow.spring.boot.security.DevelopmentCallerResolver
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.reactor.netty.NettyServerCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.util.unit.DataSize
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.ServerResponse
import reactor.netty.http.HttpDecoderSpec
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * The node's public port, on the application's WebFlux server: its routes ([wowRoutes]) and the operator's routes of the
 * parked entries ([parkedRoutes]), the callers they serve ([CallerResolver]), the options they answer by
 * ([WowHttpOptions]), their global errors and the masks of what they return ([PayloadMasks]). Imported by
 * [WowAutoConfiguration], whose order and conditions it shares.
 */
@Configuration(proxyBeanMethods = false)
internal class NodePublicPort {
    /**
     * The public server's own refusals as problems ([ServerRefusals], #6956): a request line past
     * `server.netty.max-initial-line-length`, a header block past `server.max-http-request-header-size`, or malformed
     * HTTP, each a problem of Reactor Netty's status naming the limit and its key.
     */
    @Bean
    @InternalWowApi
    fun wowServerRefusals(environment: Environment): NettyServerCustomizer {
        val binder = Binder.get(environment)
        fun limit(key: String, default: DataSize): String =
            "${(binder.bind(key, DataSize::class.java).orElse(null) ?: default).toBytes()} bytes ($key)"
        val limits = ServerRefusals.Limits(
            requestLine = limit(MAX_INITIAL_LINE_LENGTH, DEFAULT_MAX_INITIAL_LINE_LENGTH),
            headers = limit(MAX_HEADER_SIZE, DEFAULT_MAX_HEADER_SIZE),
        )
        return NettyServerCustomizer { server -> ServerRefusals.of(server, limits) }
    }

    /**
     * The callers of a node without a `CallerResolver` bean, for development alone: a deployment maps the identity its
     * gateway asserts through one (CoSec's, with wow-cosec and `cosec.enabled`, or the application's), and an
     * intentionally anonymous service declares one that returns [CallerResolver.ANONYMOUS].
     *
     * - In a profile of `wow.dev-profiles`, the callers the request's identity headers name
     *   ([DevelopmentCallerResolver]): an explicit choice to run for development, so a client may name any caller.
     * - In development by a loopback internal port the operator named alone, the anonymous caller of
     *   `wow.tenant.default`: the internal port says nothing of who reaches the public one, so no header names a caller.
     * - Otherwise the node refuses to start.
     */
    @Bean
    @ConditionalOnMissingBean
    @InternalWowApi
    fun wowCallerResolver(
        properties: WowProperties,
        development: DevelopmentMode,
        environment: Environment,
    ): CallerResolver {
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
                "a loopback wow.internal.host): the public port would have no caller to serve."
        }
        val tenant = properties.tenant.default
        if (development.byProfile) {
            logger.warn {
                "No CallerResolver bean, in the development profile ${development.profiles.joinToString()}: the public port takes " +
                    "its callers from the ${DevelopmentHeaders.USER}, ${DevelopmentHeaders.OWNER}, " +
                    "${DevelopmentHeaders.ROLES}, ${DevelopmentHeaders.TENANT} and " +
                    "${DevelopmentHeaders.APP} headers any client sets, and serves a request without " +
                    "${DevelopmentHeaders.USER} as the anonymous caller of tenant $tenant." +
                    exposure(environment.getProperty(SERVER_ADDRESS))
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
        val strategy = ForwardHeadersEnvironmentPostProcessor.inEffect(environment)
        logger.info {
            "The public port reads the client's address with $strategy: " +
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
     * framework routes' problem+json, a failure of the application's own by its [ProblemMapper]s in their order, unless
     * `wow.http.global-errors.enabled` is off.
     */
    @Bean
    @ConditionalOnProperty(
        prefix = "${WowProperties.PREFIX}.http",
        name = ["global-errors.enabled"],
        havingValue = "true",
        matchIfMissing = true,
    )
    @InternalWowApi
    fun wowGlobalErrors(
        manifest: Manifest,
        options: WowHttpOptions,
        mappers: ObjectProvider<ProblemMapper>,
    ): GlobalErrors = GlobalErrors(manifest, options, mappers.orderedStream().toList())

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
     * the node's [CommandClient]; a failure of the application's own answered by its [ProblemMapper]s in their order.
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
        mappers: ObjectProvider<ProblemMapper>,
    ): RouterFunction<ServerResponse> {
        manifest.refusedWithoutAuthorizer(node.gateway.decidesOperational)?.let { notice -> logger.info { notice } }
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
            mappers.orderedStream().toList(),
        )
    }

    /**
     * The operator's routes over the parked entries, when `wow.http.ops.enabled`: authenticated by the
     * application's [CallerResolver], each caller kept in its tenant, their actions authorized by its
     * [CommandAuthorizer]s, and refused when none of them decides operational commands.
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
        if (authorizer?.decidesOperational != true) {
            logger.warn {
                "wow.http.ops.enabled, but no CommandAuthorizer decides Wow's operational commands: the operator's " +
                    "actions on parked entries are refused (403) until the application gives one " +
                    "(CommandAuthorizer.operational, or CoSec's with wow.cosec.authorizer.enabled)."
            }
        }
        return parkedRoutes(node.parked, manifest, authorizer, callers, options)
    }

    internal companion object {
        /** Spring Boot's key of the public server's request line limit, and its default: Reactor Netty's. */
        const val MAX_INITIAL_LINE_LENGTH: String = "server.netty.max-initial-line-length"
        private val DEFAULT_MAX_INITIAL_LINE_LENGTH: DataSize =
            DataSize.ofBytes(HttpDecoderSpec.DEFAULT_MAX_INITIAL_LINE_LENGTH.toLong())

        /** Spring Boot's key of the public server's header block limit, and its default. */
        const val MAX_HEADER_SIZE: String = "server.max-http-request-header-size"
        private val DEFAULT_MAX_HEADER_SIZE: DataSize = DataSize.ofKilobytes(8)

        /**
         * The operational commands ([SystemCommandGroups.OPERATIONAL]) the Manifest routes, by their names: a context's
         * jobs and an aggregate type's snapshot rebuild, which the public port refuses unless an authorizer decides them.
         */
        internal fun Manifest.operationalCommands(): Set<String> = contexts.flatMap { context ->
            context.commands + context.aggregates.flatMap { it.commands }
        }.map { it.name }.filter { it in SystemCommandGroups.OPERATIONAL }.toSortedSet()

        /**
         * The startup notice of a public port that refuses the operational commands the Manifest routes, for want of an
         * authorizer that decides them; null when one of the node's authorizers [decides] them or it routes none.
         */
        internal fun Manifest.refusedWithoutAuthorizer(decides: Boolean): String? {
            val operational = operationalCommands().takeUnless { decides || it.isEmpty() } ?: return null
            return "No CommandAuthorizer decides Wow's operational commands: the public port refuses " +
                "$operational with 403 until the application gives one (CommandAuthorizer.operational, or CoSec's " +
                "with wow.cosec.authorizer.enabled); the jobs, the sagas and the internal port's calls send them as " +
                "before."
        }

        /** Spring Boot's key of the address the public server binds. */
        private const val SERVER_ADDRESS = "server.address"

        /**
         * What a development node's startup line adds about where its public port listens, at [address]
         * (`server.address`, null when not set): which address other machines reach it at, so a developer on a shared
         * network reads that any of them names its caller; nothing when it listens on the loopback interface alone.
         */
        internal fun exposure(address: String?): String {
            val bound = address?.takeIf { it.isNotBlank() }
            if (bound != null && isLoopback(bound)) {
                return ""
            }
            val where = bound ?: "every interface ($SERVER_ADDRESS is not set)"
            return " The public port listens on $where: any machine that reaches it names its caller; set " +
                "$SERVER_ADDRESS=127.0.0.1 to keep it to this one."
        }

        private fun isLoopback(host: String): Boolean = try {
            InetAddress.getByName(host).isLoopbackAddress
        } catch (_: UnknownHostException) {
            false
        }

        /** The host's log category: the lines that moved here from [WowAutoConfiguration] keep it. */
        private val logger = KotlinLogging.logger(WowAutoConfiguration::class.java.name)
    }
}
