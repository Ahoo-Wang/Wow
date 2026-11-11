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

import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.client.HttpCommandCaller
import me.ahoo.wow.http.callRoutes
import me.ahoo.wow.http.caller.CallPermits
import me.ahoo.wow.http.caller.CallerResolver
import me.ahoo.wow.http.caller.DeclaredServices
import me.ahoo.wow.http.internal.HttpClusterChannel
import me.ahoo.wow.http.internal.HttpSignalSender
import me.ahoo.wow.http.internal.InternalPort
import me.ahoo.wow.http.internal.internalRoutes
import me.ahoo.wow.http.ops.parkedRoutes
import me.ahoo.wow.http.wowRoutes
import me.ahoo.wow.kernel.ModelProvider
import me.ahoo.wow.runtime.admission.CommandTransformer
import me.ahoo.wow.runtime.admission.CommandValidator
import me.ahoo.wow.runtime.contract.ContractDirectory
import me.ahoo.wow.runtime.manifest.ManifestLoader
import me.ahoo.wow.runtime.node.BackendFactory
import me.ahoo.wow.runtime.node.ServiceNode
import me.ahoo.wow.runtime.node.SourceTopics
import me.ahoo.wow.runtime.node.SubscriptionFactory
import me.ahoo.wow.runtime.sink.PublishedSinks
import me.ahoo.wow.runtime.store.BackendSelection
import me.ahoo.wow.runtime.store.StoreLayout
import me.ahoo.wow.runtime.subscription.ParkedNotifier
import me.ahoo.wow.spi.call.CommandCaller
import me.ahoo.wow.spi.id.AggregateIdGenerator
import me.ahoo.wow.spi.id.AggregateIdGeneratorSource
import me.ahoo.wow.spi.id.GlobalIdGenerator
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.observation.Observer
import me.ahoo.wow.spi.peer.PeerDiscovery
import me.ahoo.wow.spi.sink.SinkProvider
import me.ahoo.wow.spi.store.StoreProvider
import me.ahoo.wow.spi.transport.TopicTransportOptions
import me.ahoo.wow.spi.transport.TransportProvider
import me.ahoo.wow.spring.boot.config.ConfigurationKeys
import me.ahoo.wow.spring.boot.config.DevelopmentMode
import me.ahoo.wow.spring.boot.config.SingleSeams
import me.ahoo.wow.spring.boot.http.NodeInternalPort
import me.ahoo.wow.spring.boot.http.NodePeers
import me.ahoo.wow.spring.boot.http.NodePublicPort
import me.ahoo.wow.spring.boot.http.PeerDiscoveries
import me.ahoo.wow.spring.boot.observability.ObservabilityAutoConfiguration
import me.ahoo.wow.spring.boot.query.HostDatasets
import me.ahoo.wow.spring.boot.query.RetiredQueryPolicies
import me.ahoo.wow.spring.boot.store.NodeStores
import me.ahoo.wow.spring.boot.store.ProviderOptionKeys
import me.ahoo.wow.spring.boot.store.StoreInstances
import me.ahoo.wow.spring.boot.store.StoreProviders
import me.ahoo.wow.spring.boot.transport.PortProviders
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.config.BeanFactoryPostProcessor
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.Environment
import tools.jackson.databind.json.JsonMapper

/**
 * The Wow host (.3; –): by what the classpath and the
 * application's beans offer, it assembles one [ServiceNode] and serves it on two ports.
 *
 * - **Models and Manifest** ([NodeModels]): the binding index on the classpath ([ModelProvider.load]) of the contexts
 *   `wow.contexts.include` / `exclude` select ([ContextSelection]; all by default), the models' parameters from the
 *   application's beans ([BeanFactoryDependencies]), the Manifest merged from the modules' fragments
 *   ([ManifestLoader], read with [WowJson.mapper], never Spring's mapper), and with it the peer contracts on the
 *   classpath ([ContractDirectory]).
 * - **Backend** ([NodeStores]; `wow.backend`, [BackendSelection]; each store opened by the [StoreProvider] of its type,
 *   [StoreProviders]): PostgreSQL over the application's `DataSource`, MongoDB over its
 *   `MongoClient`, in memory (one node, with a warning) when `wow.backend=memory`, or when it has neither and runs in
 *   a development profile or on a loopback-only internal port (it refuses to start otherwise), or the
 *   application's own [BackendFactory] bean. With `wow.stores`, the named store instances instead ([StoreInstances]), each aggregate's
 *   log and snapshots on the store `wow.log.store`, `wow.snapshot.stores` and its `wow.aggregates.*` name
 *   ([StoreLayout]).
 * - **Admission** ([NodeAdmission]): every [CommandAuthorizer][me.ahoo.wow.runtime.admission.CommandAuthorizer]
 *   bean, all asked in their order, the first denial the answer
 *   ([CommandAuthorizer.allOf][me.ahoo.wow.runtime.admission.CommandAuthorizer.allOf]); without one, the public port
 *   refuses the system commands of Wow's built-in
 *   aggregates, the jobs' and the snapshot rebuild's (403 `wow.auth.forbidden`); the [CommandTransformer]s and [CommandValidator]s, the Jakarta
 *   validator when it has a Bean Validation provider, the id generators: the [GlobalIdGenerator] bean
 *   (UUIDv7 unless the application gives one; wow-cosid gives aggregate ids alone) and, per aggregate type,
 *   the [AggregateIdGenerator] bean
 *   `wow.aggregates.*.id-generator` names, or one an [AggregateIdGeneratorSource] bean finds by that name, else the
 *   `wowAggregateIdGenerator` bean (the global generator's ids unless replaced); the fixed tenants of
 *   `wow.aggregates.*.tenant`, and `wow.wait.default-timeout`, the node's default wait timeout for every entry.
 * - **Ports** ([NodePublicPort], [NodeInternalPort]): the public routes ([wowRoutes]) on the application's WebFlux server, authenticated by its
 *   [CallerResolver] (without one, the development callers in a development profile, the anonymous caller on a
 *   loopback-only internal port, and no start elsewhere); the cluster channel and the wait signals
 *   ([internalRoutes]) on an [InternalPort] of their own at `wow.internal.port`, never on the public server, the
 *   node's address learned once it is bound when the port is 0; [HttpClusterChannel] and [HttpSignalSender] share
 *   one JDK client. Each request
 *   between nodes names the node's service (`Wow-Service`): a forward is taken from a node of the same
 *   service alone, a signal from any service, the origin of a wait that crossed services. No token: the internal port
 *   is reachable by the platform's own pods alone, a NetworkPolicy's boundary.
 * - **Synchronous calls** ([NodePeers]): the sagas call other contexts' gateways through the [CommandCaller] bean,
 *   by default an [HttpCommandCaller] at `wow.peers.<context>.url`, the target's internal port, naming this
 *   node's service. The internal port serves the command routes of the calls ([callRoutes]) to the service each call
 *   names ([DeclaredServices]); the public port refuses `Wow-Call`. A service may call the commands
 *   `wow.callers.<service>.commands` lets it ([CallPermits]), an optional list that catches a
 *   misconfiguration, each authorized by the node's authorizers as every command is.
 * - **Subscriptions**: the annotated processors' handlers the binding indexes list ([ModelProvider.processors], built
 *   from the application's beans), then every [SubscriptionFactory] bean, the application's and the modules', in their order,
 *   and every [PublishedSinks] bean (such as the transport sink). Those that read other contexts' published streams read
 *   them from the [SourceTopics] bean (wow-kafka's).
 * - **Parked entries**: retried over the long horizon by `wow.subscription.parked.*`; every
 *   [me.ahoo.wow.runtime.subscription.ParkedNotifier] bean, the webhook of `wow.ops.webhook.*` among them, hears when
 *   one parks or spends its retries; `wow.http.ops.enabled` serves the operator's routes ([parkedRoutes]).
 * - **Observation**: every [Observer] bean (wow-observability's, [ObservabilityAutoConfiguration], and the
 *   application's), as one ([Observer.of]), in their order; nothing does without one.
 * - **Lifecycle**: [WowLifecycles].
 */
@AutoConfiguration(
    afterName = [
        "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
        "org.springframework.boot.mongodb.autoconfigure.MongoAutoConfiguration",
        "org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration",
    ],
)
@EnableConfigurationProperties(WowProperties::class)
@Import(
    NodeClock::class,
    NodeModels::class,
    NodeStores::class,
    NodeAdmission::class,
    NodePeers::class,
    NodeInternalPort::class,
    NodePublicPort::class,
    NodeService::class,
)
class WowAutoConfiguration {
    /**
     * Checks the environment's `wow.*` keys against the configuration metadata and the options the node's providers
     * declare (`wow.<type>.<key>`, [ProviderOptionKeys]) at startup, refusing the unknown ones ([ConfigurationKeys]).
     */
    @Bean
    @InternalWowApi
    fun wowConfigurationKeys(
        environment: ConfigurableEnvironment,
        context: ApplicationContext,
        providers: StoreProviders,
        transports: ObjectProvider<TransportProvider>,
        sinks: ObjectProvider<SinkProvider>,
        discoveries: ObjectProvider<PeerDiscovery>,
    ): ConfigurationKeys {
        val prefixes = context.getBeanNamesForAnnotation(ConfigurationProperties::class.java).mapNotNull { name ->
            context.findAnnotationOnBean(
                name,
                ConfigurationProperties::class.java
            )?.let { it.prefix.ifEmpty { it.value } }
        }
        val ports = PortProviders.load(
            context.classLoader,
            transports.orderedStream().toList(),
            sinks.orderedStream().toList(),
        )
        val declared = ProviderOptionKeys.of(
            providers.stores.values.map { it.type to it.options } +
                HostDatasets.providers(context.classLoader).map { it.type to it.options } +
                ports.transports.values.map { it.type to TopicTransportOptions.of(it) } +
                ports.sinks.values.map { it.type to it.options } +
                PeerDiscoveries.load(context.classLoader, discoveries.orderedStream().toList()).declared,
        )
        val keys = ConfigurationKeys.of(context.classLoader, prefixes, declared, providers.options)
        return keys.also { it.check(environment) }
    }

    /** Wow's JSON mapper; not a default candidate, so Spring's own mapper stays Spring's. */
    @Bean(WOW_JSON_MAPPER, defaultCandidate = false)
    fun wowJsonMapper(): JsonMapper = WowJson.mapper

    @Bean
    @ConditionalOnMissingBean
    @InternalWowApi
    fun wowNodeIdentity(properties: WowProperties, environment: Environment): NodeIdentity =
        NodeIdentity.of(properties, environment)

    /** The scope the routes stream their answers in, cancelled once the node and the internal port have stopped. */
    @Bean
    @InternalWowApi
    fun wowRoutesScope(): RoutesScope = RoutesScope()

    /**
     * Whether the node runs for development (a profile of `wow.dev-profiles`, or a loopback internal port):
     * the one exemption of the host's safeguards ([DevelopmentMode]).
     */
    @Bean
    @InternalWowApi
    fun wowDevelopmentMode(
        properties: WowProperties,
        environment: Environment,
        identity: ObjectProvider<NodeIdentity>,
    ): DevelopmentMode = DevelopmentMode.of(properties, environment, identity.ifAvailable?.bindHost)

    /** Lets the public server's graceful shutdown end before the server stops ([GracefulShutdownSettled]). */
    @Bean
    @InternalWowApi
    fun wowGracefulShutdownSettled(): GracefulShutdownSettled = GracefulShutdownSettled()

    companion object {
        /** Refuses several beans of one single seam before any bean is built ([SingleSeams]). */
        @Bean
        @JvmStatic
        internal fun wowSingleSeams(): BeanFactoryPostProcessor = SingleSeams()

        /** Refuses an application's QueryPolicy bean, which the node no longer applies ([RetiredQueryPolicies]). */
        @Bean
        @JvmStatic
        internal fun wowRetiredQueryPolicies(): BeanFactoryPostProcessor = RetiredQueryPolicies()

        const val WOW_JSON_MAPPER: String = "wowJsonMapper"
        const val INTERNAL_HTTP_CLIENT: String = "wowInternalHttpClient"

        /** The name of the HTTP client the node calls its peers with. */
        const val PEER_HTTP_CLIENT: String = "wowPeerHttpClient"

        /** The default [AggregateIdGenerator] bean's name: the generator of the types no `id-generator` names. */
        const val WOW_AGGREGATE_ID_GENERATOR: String = "wowAggregateIdGenerator"
    }
}
