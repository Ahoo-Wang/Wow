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
import me.ahoo.wow.api.access.AccessPolicy
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
import me.ahoo.wow.http.ops.WebhookNotifier
import me.ahoo.wow.http.ops.parkedRoutes
import me.ahoo.wow.http.wowRoutes
import me.ahoo.wow.kernel.ContextQueries
import me.ahoo.wow.kernel.Dependencies
import me.ahoo.wow.kernel.ModelProvider
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.runtime.admission.CommandAuthorizer
import me.ahoo.wow.runtime.admission.CommandGateway
import me.ahoo.wow.runtime.admission.CommandTransformer
import me.ahoo.wow.runtime.admission.CommandValidator
import me.ahoo.wow.runtime.binding.SubscriptionBindings
import me.ahoo.wow.runtime.cluster.ClusterChannel
import me.ahoo.wow.runtime.contract.ContractDirectory
import me.ahoo.wow.runtime.manifest.ManifestLoader
import me.ahoo.wow.runtime.node.AdmissionPlugins
import me.ahoo.wow.runtime.node.BackendFactory
import me.ahoo.wow.runtime.node.ServiceNode
import me.ahoo.wow.runtime.node.SourceTopics
import me.ahoo.wow.runtime.node.SubscriptionFactory
import me.ahoo.wow.runtime.sink.PublishedSinks
import me.ahoo.wow.runtime.state.KernelStateReads
import me.ahoo.wow.runtime.state.ReplayGate
import me.ahoo.wow.runtime.state.StateReads
import me.ahoo.wow.runtime.store.BackendSelection
import me.ahoo.wow.runtime.store.StoreLayout
import me.ahoo.wow.runtime.subscription.ParkedNotifier
import me.ahoo.wow.runtime.wait.SignalSender
import me.ahoo.wow.spi.call.CommandCaller
import me.ahoo.wow.spi.config.configNotNull
import me.ahoo.wow.spi.id.AggregateIdGenerator
import me.ahoo.wow.spi.id.AggregateIdGeneratorSource
import me.ahoo.wow.spi.id.GlobalIdGenerator
import me.ahoo.wow.spi.id.IdGenerators
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.observation.Observer
import me.ahoo.wow.spi.peer.PeerDiscovery
import me.ahoo.wow.spi.sink.SinkProvider
import me.ahoo.wow.spi.store.StoreProvider
import me.ahoo.wow.spi.subscription.FailureClassifier
import me.ahoo.wow.spi.transport.TopicTransportOptions
import me.ahoo.wow.spi.transport.TransportProvider
import me.ahoo.wow.spring.boot.NodeAdmission.Companion.aggregateIdGenerators
import me.ahoo.wow.spring.boot.NodeAdmission.Companion.idGeneratorLookup
import me.ahoo.wow.spring.boot.NodeAdmission.Companion.tenants
import me.ahoo.wow.spring.boot.NodeAdmission.Companion.writePolicies
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.config.BeanFactoryPostProcessor
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.availability.ApplicationAvailability
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.ApplicationContext
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.Environment
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import kotlin.time.toKotlinDuration

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
 * - **Admission** ([NodeAdmission]): every [CommandAuthorizer] bean, all asked in their order, the first denial the answer
 *   ([CommandAuthorizer.allOf]); without one, the public port refuses the system commands of Wow's built-in
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

    @Bean
    @ConditionalOnMissingBean
    // One parameter per part of the node, each a bean the application may replace, and one line per option.
    @Suppress("LongParameterList", "LongMethod")
    @InternalWowApi
    fun wowServiceNode(
        properties: WowProperties,
        environment: Environment,
        identity: NodeIdentity,
        models: WowModels,
        manifest: Manifest,
        contracts: ContractDirectory,
        backends: HostBackends,
        dependencies: Dependencies,
        channel: ClusterChannel,
        signals: SignalSender,
        globalIds: GlobalIdGenerator,
        @Qualifier(WOW_AGGREGATE_ID_GENERATOR) aggregateIds: AggregateIdGenerator,
        idSources: ObjectProvider<AggregateIdGeneratorSource>,
        beanFactory: ConfigurableListableBeanFactory,
        authorizers: ObjectProvider<CommandAuthorizer>,
        transformers: ObjectProvider<CommandTransformer>,
        validators: ObjectProvider<CommandValidator>,
        subscriptions: ObjectProvider<SubscriptionFactory>,
        sinks: ObjectProvider<PublishedSinks>,
        providedSinks: ObjectProvider<ProvidedSinks>,
        listeners: ObjectProvider<ServiceNodeListener>,
        topics: ObjectProvider<SourceTopics>,
        transports: ObjectProvider<TransportProvider>,
        notifiers: ObjectProvider<ParkedNotifier>,
        observers: ObjectProvider<Observer>,
        callers: ObjectProvider<CommandCaller>,
        classifiers: ObjectProvider<FailureClassifier>,
        queries: ObjectProvider<ContextQueries>,
        access: ObjectProvider<AccessPolicy<*>>,
        clock: Clock,
        replayGates: ObjectProvider<ReplayGate>,
    ): ServiceNode {
        checkShutdownTimeout(environment)
        val sourceTopics = topics.ifAvailable
        if (sourceTopics == null) {
            val contexts = models.models.mapTo(HashSet()) { it.type.context }
            TransportNeeds.check(
                TransportNeeds.of(
                    CommandRoutings.topicRouted(properties, models.models),
                    properties.ops.publish.enabled == WowProperties.Switch.TRUE,
                    TransportNeeds.intake(environment, transportTypes(beanFactory, transports)),
                ),
                properties.transport,
                TransportNeeds.unpublished(manifest, contexts),
            )
        }
        val routing = CommandRoutings.routing(properties, backends.local)
        val stepListeners = listeners.orderedStream().toList()
        return ServiceNode(
            clock = clock,
            service = identity.service,
            node = identity.node,
            address = identity.address,
            models = models.models,
            manifest = manifest,
            backends = backends.factory,
            channel = channel,
            remoteSignals = signals,
            options = properties.serviceNodeOptions(
                routing,
                CommandRoutings.topicRouted(properties, models.models),
                transport = sourceTopics?.producer != null,
            ),
            admission = AdmissionPlugins(
                authorizer = authorizers.orderedStream().toList().takeIf { it.isNotEmpty() }
                    ?.let(CommandAuthorizer::allOf),
                transformers = transformers.orderedStream().toList(),
                validators = validators.orderedStream().toList(),
                ids = IdGenerators(
                    globalIds,
                    aggregateIdGenerators(
                        properties,
                        models.models,
                        aggregateIds,
                        idGeneratorLookup(beanFactory, idSources.orderedStream().toList()),
                    ),
                ),
                tenants = tenants(properties, models.models),
                policies = writePolicies(properties, models, access),
            ),
            subscriptions = bindings(
                models.providers,
                dependencies,
                contracts.local,
                subscriptions.orderedStream().toList() + sinks.orderedStream().map { it.factory }.toList() +
                    providedSinks.ifAvailable?.sinks.orEmpty().map { it.factory },
            ),
            listener = { step -> stepListeners.forEach { it.onStep(step) } },
            contracts = contracts,
            topics = sourceTopics,
            parkedNotifier = ParkedNotifier.of(notifiers.orderedStream().toList()),
            observer = Observer.of(observers.orderedStream().toList()),
            caller = callers.ifAvailable,
            failureClassifiers = classifiers.orderedStream().toList(),
            queries = lazyQueries(queries),
            // The gate over the query bulkheads, built before the node: its state rebuilds replay under it.
            replayGate = replayGates.getIfAvailable { ReplayGate.OPEN },
        )
    }

    /**
     * The handlers' queries, the query plane's [ContextQueries] bean resolved when a handler first reads:
     * the query plane is built over the node, so it cannot be one of the node's parameters.
     */
    private fun lazyQueries(queries: ObjectProvider<ContextQueries>): ContextQueries {
        val resolved by lazy { queries.getIfAvailable { ContextQueries.NONE } }
        return ContextQueries { tenantId -> resolved.of(tenantId) }
    }

    /** The scope the routes stream their answers in, cancelled once the node and the internal port have stopped. */
    @Bean
    @InternalWowApi
    fun wowRoutesScope(): RoutesScope = RoutesScope()

    @Bean
    @InternalWowApi
    fun wowServiceNodeLifecycle(
        node: ServiceNode,
        events: ApplicationEventPublisher,
        availability: ObjectProvider<ApplicationAvailability>,
        properties: WowProperties,
    ): ServiceNodeLifecycle = ServiceNodeLifecycle(node, events, properties.node.standby) { availability.ifAvailable }

    /**
     * The node's command gateway, through which the application sends commands in process. The node
     * closes it when it stops.
     */
    @Bean(destroyMethod = "")
    fun wowCommandGateway(node: ServiceNode): CommandGateway = node.gateway

    @Bean
    @ConditionalOnMissingBean
    @InternalWowApi
    fun wowStateReads(
        node: ServiceNode,
        backends: HostBackends,
        gate: ObjectProvider<ReplayGate>,
        queries: ObjectProvider<QueryProperties>,
    ): StateReads {
        val opened = backends.contexts
        val contexts = node.contexts.mapNotNull { (name, context) ->
            opened[name]?.primarySnapshots?.let { name to KernelStateReads.ContextReads(context.kernel, it) }
        }.toMap()
        // The query plane's gate runs a state read's replay under its log store's bulkhead,
        // and `wow.query.state.max-replay` bounds it.
        val maxReplay = queries.ifAvailable?.state?.maxReplay ?: KernelStateReads.DEFAULT_MAX_REPLAY
        // How long a read of a minimum version waits for it: `wow.query.state.min-version-wait`.
        val minVersionWait = queries.ifAvailable?.state?.minVersionWait?.toKotlinDuration()
            ?: KernelStateReads.DEFAULT_MIN_VERSION_WAIT
        return KernelStateReads(
            node.registry,
            contexts,
            gate.getIfAvailable { ReplayGate.OPEN },
            maxReplay,
            minVersionWait,
        )
    }

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

    /** Tells `wow.ops.webhook.url` when an entry parks or spends its retries. */
    @Bean
    @ConditionalOnProperty(prefix = "${WowProperties.PREFIX}.ops.webhook", name = ["url"])
    @InternalWowApi
    fun wowWebhookNotifier(properties: WowProperties, identity: NodeIdentity): WebhookNotifier {
        val webhook = properties.ops.webhook
        return WebhookNotifier(
            uri = configNotNull(webhook.url, "wow.ops.webhook.url", "Set it to the URL the webhook posts to.") {
                "wow.ops.webhook.url is not set."
            },
            format = webhook.format,
            kinds = webhook.events.toSet(),
            service = identity.service,
            headers = webhook.headers.toMap(),
            timeout = webhook.timeout.toKotlinDuration(),
        )
    }

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
        private val logger = KotlinLogging.logger {}

        /** The types of the topic transports the node can open: on the classpath, and the application's beans. */
        internal fun transportTypes(
            beanFactory: ConfigurableListableBeanFactory,
            transports: ObjectProvider<TransportProvider>,
        ): Set<String> =
            PortProviders.load(beanFactory.beanClassLoader, transports.orderedStream().toList()).transports.keys

        /**
         * The subscriptions of the annotated processors of the node's contexts (the generated
         * [ModelProvider.processors] of [providers], the selected ones on the class path), their processors built
         * from [dependencies], a handler of another context's events by type reading the aggregates the service's
         * [manifest] says, then those of every [SubscriptionFactory] bean in [factories], in their order.
         */
        internal fun bindings(
            providers: List<ModelProvider>,
            dependencies: Dependencies,
            manifest: Manifest,
            factories: List<SubscriptionFactory>,
        ): SubscriptionFactory {
            val bindings = SubscriptionBindings.of(dependencies, providers, manifest)
            return SubscriptionFactory.of(listOf(bindings) + factories)
        }

        private fun checkShutdownTimeout(environment: Environment) {
            val required = ShutdownTimeoutEnvironmentPostProcessor.required(environment)
            val configured = ShutdownTimeoutEnvironmentPostProcessor.configured(environment) ?: return
            if (configured < required) {
                logger.warn {
                    "${ShutdownTimeoutEnvironmentPostProcessor.TIMEOUT_PER_SHUTDOWN_PHASE} is $configured, less than " +
                        "wow.wait.max-timeout + wow.cluster.drain-timeout ($required): a stopping node may be cut off."
                }
            }
        }
    }
}
