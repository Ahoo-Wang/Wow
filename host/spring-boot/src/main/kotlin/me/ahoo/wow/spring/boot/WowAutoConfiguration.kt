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
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.client.CommandClient
import me.ahoo.wow.api.client.ServiceCredential
import me.ahoo.wow.client.ClientMode
import me.ahoo.wow.client.EndpointResolver
import me.ahoo.wow.client.HttpCommandCaller
import me.ahoo.wow.client.PeerClients
import me.ahoo.wow.client.RetryPolicy
import me.ahoo.wow.client.RoutedCommandClient
import me.ahoo.wow.client.UserCredential
import me.ahoo.wow.client.WowClient
import me.ahoo.wow.http.WowHttpOptions
import me.ahoo.wow.http.callRoutes
import me.ahoo.wow.http.caller.CallAuthorizer
import me.ahoo.wow.http.caller.CallPermits
import me.ahoo.wow.http.caller.CallerResolver
import me.ahoo.wow.http.caller.InternalCallerResolver
import me.ahoo.wow.http.caller.ServiceTokens
import me.ahoo.wow.http.command.JakartaCommandValidator
import me.ahoo.wow.http.internal.ClusterTokens
import me.ahoo.wow.http.internal.HttpClusterChannel
import me.ahoo.wow.http.internal.HttpSignalSender
import me.ahoo.wow.http.internal.InternalHttp
import me.ahoo.wow.http.internal.InternalPort
import me.ahoo.wow.http.internal.InternalPortSettings
import me.ahoo.wow.http.internal.internalRoutes
import me.ahoo.wow.http.ops.WebhookNotifier
import me.ahoo.wow.http.ops.parkedRoutes
import me.ahoo.wow.http.peerReadRoutes
import me.ahoo.wow.http.problem.GlobalErrors
import me.ahoo.wow.http.query.HttpQueries
import me.ahoo.wow.http.wowRoutes
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.ContextQueries
import me.ahoo.wow.kernel.Dependencies
import me.ahoo.wow.kernel.ModelProvider
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.ServiceInfo
import me.ahoo.wow.query.mask.PayloadMasks
import me.ahoo.wow.query.schema.MaskStrategyResolver
import me.ahoo.wow.query.store.StoreQueryProvider
import me.ahoo.wow.runtime.admission.CommandAuthorizer
import me.ahoo.wow.runtime.admission.CommandGateway
import me.ahoo.wow.runtime.admission.CommandTransformer
import me.ahoo.wow.runtime.admission.CommandValidator
import me.ahoo.wow.runtime.binding.SubscriptionBindings
import me.ahoo.wow.runtime.client.InProcessCommandClient
import me.ahoo.wow.runtime.cluster.ClusterChannel
import me.ahoo.wow.runtime.contract.ContractDirectory
import me.ahoo.wow.runtime.manifest.ManifestLoader
import me.ahoo.wow.runtime.node.AdmissionPlugins
import me.ahoo.wow.runtime.node.BackendFactory
import me.ahoo.wow.runtime.node.Routing
import me.ahoo.wow.runtime.node.ServiceNode
import me.ahoo.wow.runtime.node.ServiceNodeOptions
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
import me.ahoo.wow.spi.config.configCheck
import me.ahoo.wow.spi.config.configNotNull
import me.ahoo.wow.spi.id.AggregateIdGenerator
import me.ahoo.wow.spi.id.AggregateIdGeneratorSource
import me.ahoo.wow.spi.id.GlobalIdGenerator
import me.ahoo.wow.spi.id.IdGenerators
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.observation.Observer
import me.ahoo.wow.spi.store.StoreProvider
import me.ahoo.wow.spi.subscription.FailureClassifier
import me.ahoo.wow.spi.tenant.TenantPolicy
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.availability.ApplicationAvailability
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.context.ApplicationContext
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.Environment
import org.springframework.util.ClassUtils
import org.springframework.util.unit.DataSize
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.server.WebFilter
import tools.jackson.databind.json.JsonMapper
import java.net.http.HttpClient
import kotlin.time.toKotlinDuration
import jakarta.validation.Validator as JakartaValidator

/**
 * The Wow host (roadmap 5b.3; decisions D30, D44, D89, D105–D110, D114): by what the classpath and the
 * application's beans offer, it assembles one [ServiceNode] and serves it on two ports.
 *
 * - **Models and Manifest**: the binding index on the classpath ([ModelProvider.load]) of the contexts
 *   `wow.contexts.include` / `exclude` select ([ContextSelection], decision D169; all by default), the models' parameters from the
 *   application's beans ([BeanFactoryDependencies]), the Manifest merged from the modules' fragments
 *   ([ManifestLoader], read with [WowJson.mapper], never Spring's mapper), and with it the peer contracts on the
 *   classpath ([ContractDirectory], decisions D139, D234).
 * - **Backend** (`wow.backend`, [BackendSelection]; each store opened by the [StoreProvider] of its type,
 *   [StoreProviders]): PostgreSQL over the application's `DataSource`, MongoDB over its
 *   `MongoClient`, in memory (one node, with a warning) when `wow.backend=memory`, or when it has neither and runs in
 *   a development profile or on a loopback-only internal port (it refuses to start otherwise, review R3.5-1), or the
 *   application's own [BackendFactory] bean. With `wow.stores`, the named store instances instead ([StoreInstances]), each aggregate's
 *   log and snapshots on the store `wow.log.store`, `wow.snapshot.store` and its `wow.aggregates.*` name
 *   ([StoreLayout], decision D177).
 * - **Admission**: the application's [CommandAuthorizer], [CommandTransformer]s (D182) and [CommandValidator]s, the Jakarta
 *   validator when it has a Bean Validation provider (D106), the id generators (D171): the [GlobalIdGenerator] bean
 *   (UUIDv7 unless the application or wow-cosid gives one) and, per aggregate type, the [AggregateIdGenerator] bean
 *   `wow.aggregates.*.id-generator` names, or one an [AggregateIdGeneratorSource] bean finds by that name, else the
 *   `wowAggregateIdGenerator` bean (the global generator's ids unless replaced); the fixed tenants of
 *   `wow.aggregates.*.tenant` (D116), and `wow.wait.default-timeout`, the node's default wait timeout for every entry
 *   (D64).
 * - **Ports**: the public routes ([wowRoutes]) on the application's WebFlux server, authenticated by its
 *   [CallerResolver] (anonymous otherwise, D97); the cluster channel and the wait signals ([internalRoutes]) on an
 *   [InternalPort] of their own at `wow.internal.port`, never on the public server, the node's address learned once
 *   it is bound when the port is 0; [HttpClusterChannel] and [HttpSignalSender] share one JDK client. The cluster
 *   routes take the service's cluster token (`wow.internal.token`, decision D226), and the signals
 *   also a service identity, the origin of a wait that crossed services; the internal side is a trusted environment
 *   (decision D298), so a node starts without a cluster token too.
 * - **Synchronous calls** (decision D193): the sagas call other contexts' gateways through the [CommandCaller] bean,
 *   by default an [HttpCommandCaller] at `wow.calls.contexts.<context>.url`, the target's internal port, presenting
 *   the [ServiceCredential] bean (`wow.calls.token` as a bearer). The internal port serves the command routes of the
 *   calls ([callRoutes]) to the service identities of `wow.http.services`, by their tokens ([ServiceTokens]), or of
 *   the [InternalCallerResolver] bean (CoSec's service principals); the public port refuses `Wow-Call`. A service may
 *   call the commands `wow.http.services.<service>.commands` lets it ([CallPermits], decision D226), each authorized
 *   by the [CallAuthorizer] bean when there is one (CoSec's, always with CoSec), else by the [CommandAuthorizer].
 * - **Subscriptions**: the annotated processors' handlers the binding indexes list ([ModelProvider.processors], built
 *   from the application's beans), then every [SubscriptionFactory] bean, the application's and the modules', in their order,
 *   and every [PublishedSinks] bean (such as the Kafka Sink). Those that read other contexts' published streams read
 *   them from the [SourceTopics] bean (wow-kafka's, decision D134).
 * - **Parked entries** (roadmap P.1): retried over the long horizon by `wow.subscription.parked.*`; every
 *   [me.ahoo.wow.runtime.subscription.ParkedNotifier] bean, the webhook of `wow.ops.webhook.*` among them, hears when
 *   one parks or spends its retries; `wow.ops.enabled` serves the operator's routes ([parkedRoutes]).
 * - **Observation** (decision D174): the [Observer] bean, when there is one (wow-opentelemetry's,
 *   [ObservabilityAutoConfiguration]), sees the node's work; nothing does otherwise.
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
class WowAutoConfiguration {
    /**
     * Checks the environment's `wow.*` keys against the configuration metadata at startup (review A5-9): warns of the
     * unknown ones, and refuses an unknown `wow.cosec.*` key ([ConfigurationKeys]).
     */
    @Bean
    @InternalWowApi
    fun wowConfigurationKeys(environment: ConfigurableEnvironment, context: ApplicationContext): ConfigurationKeys {
        val prefixes = context.getBeanNamesForAnnotation(ConfigurationProperties::class.java).mapNotNull { name ->
            context.findAnnotationOnBean(
                name,
                ConfigurationProperties::class.java
            )?.let { it.prefix.ifEmpty { it.value } }
        }
        return ConfigurationKeys.of(context.classLoader, prefixes).also { it.check(environment) }
    }

    /** Wow's JSON mapper (decision D105); not a default candidate, so Spring's own mapper stays Spring's. */
    @Bean(WOW_JSON_MAPPER, defaultCandidate = false)
    fun wowJsonMapper(): JsonMapper = WowJson.mapper

    @Bean
    @ConditionalOnMissingBean
    @InternalWowApi
    fun wowNodeIdentity(properties: WowProperties, environment: Environment): NodeIdentity =
        NodeIdentity.of(properties, environment)

    @Bean
    @ConditionalOnMissingBean
    fun wowDependencies(beanFactory: ConfigurableListableBeanFactory): Dependencies =
        BeanFactoryDependencies(beanFactory)

    @Bean
    @ConditionalOnMissingBean
    @InternalWowApi
    fun wowModels(
        properties: WowProperties,
        beanFactory: ConfigurableListableBeanFactory,
        dependencies: Dependencies,
    ): WowModels {
        val providers = properties.contexts.selection().select(ModelProvider.load(beanFactory.beanClassLoader))
        return WowModels(providers, ServiceNode.models(dependencies, providers))
    }

    @Bean
    @ConditionalOnMissingBean
    @InternalWowApi
    fun wowManifestLoader(beanFactory: ConfigurableListableBeanFactory): ManifestLoader =
        ManifestLoader(beanFactory.beanClassLoader ?: ClassUtils.getDefaultClassLoader()!!, WowJson.mapper)

    @Bean
    @ConditionalOnMissingBean
    @InternalWowApi
    fun wowManifest(loader: ManifestLoader, models: WowModels, identity: NodeIdentity): Manifest =
        loader.load(ServiceInfo(identity.service), models.providers)

    /** The contracts of the service's Manifest and the peer contracts on the class path (decisions D139, D234). */
    @Bean
    @ConditionalOnMissingBean
    fun wowContractDirectory(manifest: Manifest, beanFactory: ConfigurableListableBeanFactory): ContractDirectory =
        ContractDirectory.load(
            manifest,
            beanFactory.beanClassLoader ?: ClassUtils.getDefaultClassLoader()!!,
            WowJson.mapper
        )

    /**
     * The store providers (review R3.3-1): memory, built in (its snapshots queryable under `wow.query.memory.enabled`),
     * those on the classpath (`ServiceLoader`: PostgreSQL's, MongoDB's, a community backend's) and the application's
     * [StoreProvider] and [StoreQueryProvider] beans, each over the one of its type before it.
     */
    @Bean
    @ConditionalOnMissingBean
    @InternalWowApi
    fun wowStoreProviders(
        beanFactory: ConfigurableListableBeanFactory,
        queries: ObjectProvider<QueryProperties>,
        providers: ObjectProvider<StoreProvider>,
        queryProviders: ObjectProvider<StoreQueryProvider>,
    ): StoreProviders {
        val memoryQueries = queries.ifAvailable?.memory?.enabled == true
        return StoreProviders.load(
            beanFactory.beanClassLoader,
            memory = MemoryStoreProvider(memoryQueries),
            memoryQueries = MemoryQueryProvider(memoryQueries),
            beans = providers.orderedStream().toList(),
            queryBeans = queryProviders.orderedStream().toList(),
        )
    }

    @Bean
    @ConditionalOnMissingBean
    @InternalWowApi
    @Suppress("LongParameterList") // One parameter per input of the stores, each a bean the application may give.
    fun wowBackends(
        properties: WowProperties,
        beanFactory: ConfigurableListableBeanFactory,
        factories: ObjectProvider<BackendFactory>,
        providers: StoreProviders,
        models: WowModels,
        identity: ObjectProvider<NodeIdentity>,
        environment: Environment,
    ): HostBackends {
        factories.ifAvailable?.let { return HostBackends.of(it) }
        val node = identity.ifAvailable
        val stores = StoreInstances.open(
            properties,
            providers,
            BeanStoreResources(beanFactory),
            node?.service.orEmpty(),
            BackendSelection.memoryOptIn(
                environment.activeProfiles.toList(),
                properties.internal.devProfiles,
                node?.bindHost
            ),
        )
        val layout = StoreLayout.of(properties.storePlacements(), stores, models.models.map { it.type })
        val memory = (layout.logStores + layout.snapshotStores).filterNot { stores.getValue(it).durable }
        if (memory.isNotEmpty()) {
            logger.warn {
                "Wow keeps data in memory on the stores $memory (wow.backend=memory, a store of type memory, or no " +
                    "DataSource or MongoClient backs it in a development profile or on a loopback internal port): " +
                    "it does not outlive the node, and serves one node only."
            }
        }
        return HostBackends.of(stores, layout, properties.log.partitions)
    }

    /** The node's global ids (decision D171): command, request and wait ids, and the ids domain code takes. */
    @Bean
    @ConditionalOnMissingBean
    fun wowGlobalIdGenerator(): GlobalIdGenerator = GlobalIdGenerator.UUID_V7

    /** The ids of new aggregates of every type no `wow.aggregates.*.id-generator` names (decision D171). */
    @Bean(WOW_AGGREGATE_ID_GENERATOR)
    @ConditionalOnMissingBean(name = [WOW_AGGREGATE_ID_GENERATOR])
    fun wowAggregateIdGenerator(global: GlobalIdGenerator): AggregateIdGenerator = AggregateIdGenerator.of(global)

    @Bean(defaultCandidate = false)
    @ConditionalOnMissingBean(name = [INTERNAL_HTTP_CLIENT])
    fun wowInternalHttpClient(properties: WowProperties): HttpClient =
        InternalHttp.client(properties.internal.connectTimeout.toKotlinDuration())

    @Bean
    @ConditionalOnMissingBean
    @InternalWowApi
    fun wowClusterChannel(
        @Qualifier(INTERNAL_HTTP_CLIENT) client: HttpClient,
        properties: WowProperties,
    ): ClusterChannel = HttpClusterChannel(client, token = clusterTokenOf(properties))

    @Bean
    @ConditionalOnMissingBean
    @InternalWowApi
    fun wowSignalSender(
        @Qualifier(INTERNAL_HTTP_CLIENT) client: HttpClient,
        properties: WowProperties,
        identity: NodeIdentity,
        credential: ServiceCredential,
        observers: ObjectProvider<Observer>,
        node: ObjectProvider<ServiceNode>,
    ): SignalSender = HttpSignalSender(
        client,
        queueCapacity = properties.internal.signalQueueCapacity,
        service = identity.service,
        clusterToken = clusterTokenOf(properties),
        credential = credential,
        observer = observers.getIfUnique { Observer.NONE },
        members = NodeMembers(node::getIfAvailable),
    )

    /** The credential of the node's synchronous calls (decision D193): `wow.calls.token` as a bearer, else none. */
    @Bean
    @ConditionalOnMissingBean
    @InternalWowApi
    fun wowServiceCredential(properties: WowProperties): ServiceCredential =
        properties.calls.token?.takeIf { it.isNotBlank() }?.let(ServiceCredential::bearer) ?: ServiceCredential.NONE

    /**
     * Where the peers' services are (decision D259): each context's public routes at `wow.clients.contexts.<context>.url`,
     * its internal port at `wow.clients.contexts.<context>.internal-url`, else `wow.calls.contexts.<context>.url`. An
     * application's own bean, a resolver that discovers them, replaces it for the sagas' calls and the clients alike.
     */
    @Bean
    @ConditionalOnMissingBean
    fun wowEndpointResolver(properties: WowProperties): EndpointResolver {
        val calls = properties.calls.contexts.mapValues { (context, target) ->
            configNotNull(
                target.url,
                "wow.calls.contexts.$context.url",
                "Set it to the base URL of the context's service."
            ) {
                "wow.calls.contexts.$context.url is not set."
            }
        }
        val clients = properties.clients.contexts
        return EndpointResolver.of(
            clients.mapNotNull { (context, target) -> target.url?.let { context to it } }.toMap(),
            calls + clients.mapNotNull { (context, target) -> target.internalUrl?.let { context to it } }.toMap(),
        )
    }

    /**
     * The client of the other contexts' command gateways the sagas call (decision D193): the service mode of
     * `wow-client` at the internal ports [endpoints] resolves, each call bounded by `wow.calls.timeout`.
     */
    @Bean
    @ConditionalOnMissingBean
    @InternalWowApi
    fun wowCommandCaller(
        properties: WowProperties,
        credential: ServiceCredential,
        endpoints: EndpointResolver,
    ): CommandCaller {
        val calls = properties.calls
        return HttpCommandCaller(
            endpoints,
            credential,
            HttpCommandCaller.client(calls.connectTimeout.toKotlinDuration()),
            calls.timeout.toKotlinDuration(),
        )
    }

    /**
     * The peer contexts reached over HTTP, `wow.clients.contexts` (decisions D257, D259), and their clients in either
     * mode, each built when first used: the service mode presenting the [ServiceCredential] bean, the user mode
     * forwarding the request's `wow.clients.forwarded-headers`.
     */
    @Bean
    @InternalWowApi
    fun wowPeerClients(
        properties: WowProperties,
        endpoints: EndpointResolver,
        credential: ServiceCredential,
        contracts: ContractDirectory,
        globalIds: GlobalIdGenerator,
    ): PeerClients {
        val clients = properties.clients
        val described = clients.contexts
            .filterValues { it.contract == WowProperties.Clients.Contract.MANIFEST }.keys
        fun client(mode: ClientMode) = lazy {
            val builder = WowClient.builder()
                .endpoints(endpoints)
                .connectTimeout(clients.connectTimeout.toKotlinDuration())
                .timeout(clients.timeout.toKotlinDuration())
                .retry(
                    RetryPolicy.of(
                        clients.retry.maxAttempts,
                        clients.retry.maxRetryAfter.toKotlinDuration(),
                        clients.retry.backoff.toKotlinDuration(),
                    ),
                )
                .contracts(contracts.fragments)
                .ids(globalIds)
                .manifestPeers(described)
            when (mode) {
                is ClientMode.Service -> builder.service(mode.credential)
                is ClientMode.User -> builder.user(mode.credential)
            }
            builder.build()
        }
        return PeerClients(
            clients.contexts.mapValues { (_, target) -> target.mode ?: clients.mode },
            contracts.localContexts,
            contracts.peerContexts,
            client(ClientMode.Service(credential)),
            client(ClientMode.User(UserCredential.FORWARDED)),
            clients.mode,
            described,
        )
    }

    /**
     * The typed clients of the peers over HTTP in `wow.clients.mode` (decision D257), for the application that calls a
     * peer explicitly; the [CommandClient] and [me.ahoo.wow.api.read.QueryApi] beans route to them on their own.
     */
    @Bean
    @ConditionalOnMissingBean
    fun wowClient(peers: PeerClients): WowClient = peers.client

    /**
     * Puts the `wow.clients.forwarded-headers` of each public request in its handling's coroutine context
     * ([me.ahoo.wow.client.ForwardedHeaders]), which a user-mode client forwards to a peer (decision D259).
     */
    @Bean
    @InternalWowApi
    fun wowForwardedHeadersFilter(properties: WowProperties): WebFilter =
        ForwardedHeadersFilter(properties.clients.forwardedHeaders.map(String::trim).filter(String::isNotEmpty))

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
        callAuthorizers: ObjectProvider<CallAuthorizer>,
        transformers: ObjectProvider<CommandTransformer>,
        validators: ObjectProvider<CommandValidator>,
        subscriptions: ObjectProvider<SubscriptionFactory>,
        sinks: ObjectProvider<PublishedSinks>,
        providedSinks: ObjectProvider<ProvidedSinks>,
        listeners: ObjectProvider<ServiceNodeListener>,
        topics: ObjectProvider<SourceTopics>,
        notifiers: ObjectProvider<ParkedNotifier>,
        observers: ObjectProvider<Observer>,
        callers: ObjectProvider<CommandCaller>,
        classifiers: ObjectProvider<FailureClassifier>,
        queries: ObjectProvider<ContextQueries>,
    ): ServiceNode {
        checkShutdownTimeout(environment)
        val routing = routing(properties, backends.local)
        val stepListeners = listeners.orderedStream().toList()
        return ServiceNode(
            service = identity.service,
            node = identity.node,
            address = identity.address,
            models = models.models,
            manifest = manifest,
            backends = backends.factory,
            channel = channel,
            remoteSignals = signals,
            options = ServiceNodeOptions(
                cluster = properties.cluster.options(properties.wait.defaultTimeout),
                routing = routing,
                maxWaitTimeout = properties.wait.maxTimeout.toKotlinDuration(),
                maxWaitHops = properties.wait.maxHops,
                unknownWaitContexts = properties.wait.unknownContexts,
                maxRegisteredWaits = properties.wait.maxRegistered,
                subscription = properties.subscription.options(properties.subscriptions),
                appendQueue = properties.log.appendQueue.options(),
                handoff = properties.log.handoffOptions(),
                laneCapacity = properties.executor.laneCapacity,
                maxBatch = properties.executor.maxBatch,
                maxInFlight = properties.executor.maxInFlight,
                stateCacheCapacity = properties.stateCache.capacity,
                recentRequests = properties.stateCache.recentRequests,
                stateViews = properties.stateViews.options(),
                defaultWaitTimeout = properties.wait.defaultTimeout.toKotlinDuration(),
                timers = properties.timers.options(),
                schedules = properties.schedules.mapValues { it.value.options() },
                kafkaRouted = kafkaRouted(properties, models.models),
                ledger = properties.ops.publish.options(),
                snapshots = properties.snapshot.options(),
            ),
            admission = AdmissionPlugins(
                authorizer = authorizerOf(
                    authorizers.getIfAvailable { CommandAuthorizer.ALLOW_ALL },
                    callAuthorizers.ifAvailable
                ),
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
            ),
            subscriptions = bindings(
                models.providers,
                dependencies,
                subscriptions.orderedStream().toList() + sinks.orderedStream().map { it.factory }.toList() +
                    providedSinks.ifAvailable?.sinks.orEmpty().map { it.factory },
            ),
            listener = { step -> stepListeners.forEach { it.onStep(step) } },
            contracts = contracts,
            topics = topics.ifAvailable,
            parkedNotifier = ParkedNotifier.of(notifiers.orderedStream().toList()),
            observer = observers.getIfUnique { Observer.NONE },
            caller = callers.ifAvailable,
            failureClassifiers = classifiers.orderedStream().toList(),
            queries = lazyQueries(queries),
        )
    }

    /**
     * The handlers' queries (decision D259), the query plane's [ContextQueries] bean resolved when a handler first reads:
     * the query plane is built over the node, so it cannot be one of the node's parameters.
     */
    private fun lazyQueries(queries: ObjectProvider<ContextQueries>): ContextQueries {
        val resolved by lazy { queries.getIfAvailable { ContextQueries.NONE } }
        return ContextQueries { tenantId -> resolved.of(tenantId) }
    }

    @Bean
    @InternalWowApi
    fun wowBackendsLifecycle(backends: HostBackends): BackendsLifecycle = BackendsLifecycle(backends)

    /** The scope the routes stream their answers in, cancelled once the node and the internal port have stopped. */
    @Bean
    @InternalWowApi
    fun wowRoutesScope(): RoutesScope = RoutesScope()

    @Bean
    @ConditionalOnMissingBean
    @InternalWowApi
    fun wowInternalServer(
        properties: WowProperties,
        node: ServiceNode,
        identity: NodeIdentity,
        manifest: Manifest,
        callers: CallerResolver,
        internalCallers: ObjectProvider<InternalCallerResolver>,
        options: WowHttpOptions,
        environment: Environment,
        reads: StateReads,
        queries: ObjectProvider<HttpQueries>,
        masks: PayloadMasks,
        routes: RoutesScope,
    ): InternalPort {
        val internal = internalCallers.ifAvailable?.let { resolver -> CallerResolver { resolver.resolve(it) } }
        val services = serviceTokens(properties).resolver(internal ?: callers)
        val permits = callPermits(properties)
        val settings = InternalPortSettings(
            port = properties.internal.port,
            bindHost = identity.bindHost,
            maxMessageSize = InternalHttp.messageLimit(publicCodecLimit(environment)),
        )
        return InternalPort.of(
            settings,
            internalRoutes(
                node.forwardHandler,
                node.waits,
                scope = routes.scope,
                tokens = ClusterTokens(clusterTokenList(properties)),
                services = services,
                standby = NodeStandby(node),
            )
                // The services' peer reads (decision D259), before the call routes: their catch-all routes of an
                // unknown command would take a query's POST.
                .and(
                    peerReadRoutes(
                        manifest,
                        node.gateway,
                        services,
                        queries.getIfAvailable { HttpQueries.NONE },
                        reads,
                        options,
                        permits,
                        masks,
                        routes.scope,
                    ),
                )
                .and(callRoutes(manifest, node.gateway, services, options, permits, routes.scope)),
        )
    }

    /** Binds the internal port, then gives a node without a configured address the one it is bound at. */
    @Bean
    @InternalWowApi
    fun wowInternalServerLifecycle(
        server: InternalPort,
        node: ServiceNode,
        identity: NodeIdentity,
    ): InternalServerLifecycle = InternalServerLifecycle(server) { port ->
        if (!node.hasAddress) {
            node.bind(identity.addressAt(port))
        }
    }

    @Bean
    @InternalWowApi
    fun wowServiceNodeLifecycle(
        node: ServiceNode,
        events: ApplicationEventPublisher,
        availability: ObjectProvider<ApplicationAvailability>,
        properties: WowProperties,
    ): ServiceNodeLifecycle = ServiceNodeLifecycle(node, events, properties.standby) { availability.ifAvailable }

    /**
     * The node's command gateway (decision D92), through which the application sends commands in process. The node
     * closes it when it stops.
     */
    @Bean(destroyMethod = "")
    fun wowCommandGateway(node: ServiceNode): CommandGateway = node.gateway

    /**
     * The service's [CommandClient] (decisions D257, D258, D259): command objects sent through the node's gateway, in
     * process to the aggregates it serves, to its peers' command topics for the contexts their api jars declare, and
     * over HTTP to the peer contexts `wow.clients.contexts` lists ([PeerClients]). It sends as an anonymous caller;
     * [CommandClient.of] gives it another.
     */
    @Bean
    @ConditionalOnMissingBean
    fun wowCommandClient(gateway: CommandGateway, peers: PeerClients): CommandClient {
        val local = InProcessCommandClient(gateway)
        return if (peers.contexts.isEmpty()) local else RoutedCommandClient(local, peers)
    }

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
            opened[name]?.snapshots?.let { name to KernelStateReads.ContextReads(context.kernel, it) }
        }.toMap()
        // The query plane's gate runs a state read's replay under its log store's bulkhead (decision D246 ⑥, review
        // R4-Q Q-1), and `wow.query.state.max-replay` bounds it.
        val maxReplay = queries.ifAvailable?.state?.maxReplay ?: KernelStateReads.DEFAULT_MAX_REPLAY
        // How long a read of a minimum version waits for it (review R4-Q, Q-13): `wow.query.state.min-version-wait`.
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

    @Bean
    @ConditionalOnMissingBean
    fun wowCallerResolver(properties: WowProperties): CallerResolver {
        val tenant = properties.tenant.default
        logger.warn {
            "No CallerResolver bean: every request to the public port is served as the anonymous caller " +
                "(tenant $tenant, no scope)."
        }
        if (tenant == Caller.DEFAULT_TENANT) {
            return CallerResolver.ANONYMOUS
        }
        val anonymous = Caller(tenantId = tenant)
        return CallerResolver { anonymous }
    }

    @Bean
    @ConditionalOnMissingBean
    fun wowHttpOptions(properties: WowProperties): WowHttpOptions = WowHttpOptions(
        requestFacts = properties.http.requestFacts.toSet(),
        errorStatuses = properties.http.errors.mapValues { it.value.status },
        manifestEndpoint = properties.http.manifest.enabled,
        openApiEndpoint = properties.http.openapi.enabled,
        clientFacts = properties.http.clientFacts,
    )

    /**
     * The error handling of the application's own routes (review M-3, decision D259): their failures answered as the
     * framework routes' problem+json, unless `wow.http.global-errors` is off.
     */
    @Bean
    @ConditionalOnProperty(
        prefix = "${WowProperties.PREFIX}.http",
        name = ["global-errors"],
        havingValue = "true",
        matchIfMissing = true,
    )
    @InternalWowApi
    fun wowGlobalErrors(manifest: Manifest, options: WowHttpOptions): GlobalErrors = GlobalErrors(manifest, options)

    /** Lets the public server's graceful shutdown end before the server stops (review X-4, [GracefulShutdownSettled]). */
    @Bean
    @InternalWowApi
    fun wowGracefulShutdownSettled(): GracefulShutdownSettled = GracefulShutdownSettled()

    /**
     * The response masks of the state reads and the `events` model (09 §17 G-2): the Manifest's sensitive fields, with
     * the application's [MaskStrategyResolver] for its custom strategies, as the snapshot queries mask them.
     */
    @Bean
    @ConditionalOnMissingBean
    @InternalWowApi
    fun wowPayloadMasks(manifest: Manifest, strategies: ObjectProvider<MaskStrategyResolver>): PayloadMasks =
        PayloadMasks(manifest, strategies.getIfAvailable { MaskStrategyResolver.NONE })

    /** The public routes, served by the application's WebFlux server; the query routes when [QueryAutoConfiguration] gives them. */
    @Bean
    @InternalWowApi
    @Suppress("LongParameterList") // One parameter per part of the routes, each a bean.
    fun wowRouterFunction(
        manifest: Manifest,
        node: ServiceNode,
        reads: StateReads,
        callers: CallerResolver,
        options: WowHttpOptions,
        queries: ObjectProvider<HttpQueries>,
        masks: PayloadMasks,
        routes: RoutesScope,
    ): RouterFunction<ServerResponse> = wowRoutes(
        manifest,
        node.gateway,
        reads,
        callers,
        options,
        queries.getIfAvailable { HttpQueries.NONE },
        masks,
        routes.scope,
    )

    /**
     * The operator's routes over the parked entries (roadmap P.1), when `wow.ops.enabled`: authenticated by the
     * application's [CallerResolver], each caller kept in its tenant, their actions authorized by its
     * [CommandAuthorizer] (decision D141), and refused when it has none.
     */
    @Bean
    @ConditionalOnProperty(prefix = "${WowProperties.PREFIX}.ops", name = ["enabled"], havingValue = "true")
    fun wowParkedRouterFunction(
        manifest: Manifest,
        node: ServiceNode,
        callers: CallerResolver,
        options: WowHttpOptions,
        authorizers: ObjectProvider<CommandAuthorizer>,
    ): RouterFunction<ServerResponse> {
        val authorizer = authorizers.ifAvailable
        if (authorizer == null) {
            logger.warn {
                "wow.ops.enabled without a CommandAuthorizer bean: the operator's actions on parked entries are " +
                    "refused (403) until the application gives one."
            }
        }
        return parkedRoutes(node.parked, manifest, authorizer, callers, options)
    }

    /** Tells `wow.ops.webhook.url` when an entry parks or spends its retries (roadmap P.1). */
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

    /** Activated when the application has a Bean Validation provider (decision D106). */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = [JAKARTA_VALIDATOR])
    class JakartaValidation {
        @Bean
        @ConditionalOnBean(type = [JAKARTA_VALIDATOR])
        @ConditionalOnMissingBean
        @InternalWowApi
        fun wowJakartaCommandValidator(validator: ObjectProvider<JakartaValidator>): JakartaCommandValidator =
            JakartaCommandValidator(validator.getObject())
    }

    companion object {
        const val WOW_JSON_MAPPER: String = "wowJsonMapper"
        const val INTERNAL_HTTP_CLIENT: String = "wowInternalHttpClient"

        /** The default [AggregateIdGenerator] bean's name: the generator of the types no `id-generator` names. */
        const val WOW_AGGREGATE_ID_GENERATOR: String = "wowAggregateIdGenerator"
        private const val JAKARTA_VALIDATOR = "jakarta.validation.Validator"
        private val logger = KotlinLogging.logger {}

        /**
         * The in-memory limit of the public port's codecs, in bytes (negative: none): Spring Boot's
         * `spring.http.codecs.max-in-memory-size`, or the older `spring.codec.max-in-memory-size`, else WebFlux's
         * default. The internal port takes a forward of a body that large (review A3-9).
         */
        internal fun publicCodecLimit(environment: Environment): Int {
            val binder = Binder.get(environment)
            val size = CODEC_LIMITS.firstNotNullOfOrNull { binder.bind(it, DataSize::class.java).orElse(null) }
                ?: return InternalHttp.MAX_UPGRADE_BODY
            return size.toBytes().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }

        private val CODEC_LIMITS = listOf("spring.http.codecs.max-in-memory-size", "spring.codec.max-in-memory-size")

        /**
         * The node's authorizer (decision D226, review A3-2): [base] for every caller but the service identities, whose
         * commands (their synchronous calls) [calls] authorizes when the application or a module (CoSec) gives one.
         */
        internal fun authorizerOf(base: CommandAuthorizer, calls: CallAuthorizer?): CommandAuthorizer =
            calls?.over(base) ?: base

        /** The commands each service may call on the internal port (`wow.http.services.*.commands`, decision D226). */
        internal fun callPermits(properties: WowProperties): CallPermits =
            CallPermits(
                properties.http.services.mapValues { it.value.commands.toList() },
                properties.http.services.mapValues { it.value.reads.toList() },
            )

        /** The cluster token this node sends (`wow.internal.token`, the first), or null without one (decision D226). */
        internal fun clusterTokenOf(properties: WowProperties): String? = clusterTokenList(properties).firstOrNull()

        private fun clusterTokenList(properties: WowProperties): List<String> =
            properties.internal.token.map(String::trim).filter(String::isNotEmpty)

        private fun servedFix(served: Set<String>): String = "Name one of the aggregate types this node serves: $served."

        /**
         * The static service tokens of `wow.http.services` (decision D193). A node is configured with them or with
         * CoSec's service principals (`wow.cosec.service.roles` and `.policies`), not both (decisions D208, D226): two
         * sources of service identity would let a token outlive the CoSec policy meant to replace it, so a node with
         * both refuses to start (review A5-14).
         */
        internal fun serviceTokens(properties: WowProperties): ServiceTokens {
            val tokens = properties.http.services.filterValues { it.tokens.any(String::isNotBlank) }.keys
            val principals = properties.cosec.service.let { it.roles + it.policies }.filter(String::isNotBlank)
            configCheck(tokens.isEmpty() || principals.isEmpty(), "wow.http.services.*.tokens", null, "Remove one.") {
                "Service identities come from static tokens (wow.http.services.*.tokens of $tokens) or from CoSec's " +
                    "service principals (wow.cosec.service.*: $principals), not both."
            }
            return ServiceTokens(
                properties.http.services.mapValues { it.value.tokens.toList() },
                properties.tenant.default
            )
        }

        /**
         * Where commands run: `wow.cluster.router`, by default local when a log is on a store no other node shares
         * ([local]: the memory backend's), and partitioned on the others. Such a store refuses partitioned routing.
         */
        internal fun routing(properties: WowProperties, local: Boolean): Routing {
            val configured = properties.cluster.router
            if (local) {
                configCheck(
                    configured != Routing.PARTITIONED,
                    "wow.cluster.router",
                    configured,
                    "Back Wow with PostgreSQL or MongoDB, or route locally.",
                ) { "wow.cluster.router=partitioned routes across nodes, which the memory backend cannot serve." }
                return Routing.LOCAL
            }
            return configured ?: Routing.PARTITIONED
        }

        /**
         * The fixed tenants of `wow.aggregates.[<context>.<aggregate>].tenant` (decision D116), each of an aggregate
         * type of [models]: a key that names no such type, or a blank tenant, is refused.
         */
        internal fun tenants(properties: WowProperties, models: List<AggregateModel<*>>): TenantPolicy {
            val served = models.associateBy { "${it.type.context}.${it.type.name}" }
            val fixed = properties.aggregates.mapNotNull { (key, aggregate) ->
                aggregate.tenant?.let { tenant ->
                    val model = configNotNull(served[key], "wow.aggregates.[$key]", servedFix(served.keys)) {
                        "wow.aggregates.[$key].tenant names no aggregate type this node serves."
                    }
                    configCheck(tenant.isNotBlank(), "wow.aggregates.[$key].tenant", tenant, "Name the tenant.") {
                        "wow.aggregates.[$key].tenant is blank."
                    }
                    model.type to tenant
                }
            }.toMap()
            return TenantPolicy.fixed(fixed)
        }

        /**
         * The aggregate types of [models] whose `wow.aggregates.[<context>.<aggregate>].routing` is `kafka` (decision
         * D143): a key that names no such type is refused.
         */
        internal fun kafkaRouted(properties: WowProperties, models: List<AggregateModel<*>>): Set<AggregateType> {
            val served = models.associateBy { "${it.type.context}.${it.type.name}" }
            return properties.aggregates.filterValues { it.routing == WowProperties.AggregateRouting.KAFKA }
                .keys.mapTo(LinkedHashSet()) { key ->
                    configNotNull(served[key], "wow.aggregates.[$key]", servedFix(served.keys)) {
                        "wow.aggregates.[$key].routing names no aggregate type this node serves."
                    }.type
                }
        }

        /**
         * The generator of each aggregate type's new ids (decision D171): for each type of [models] that
         * `wow.aggregates.[<context>.<aggregate>].id-generator` names one for, the one [lookup] finds by that name;
         * [default] for the others. A key that names no such type, a blank name, or a name [lookup] finds nothing by
         * is refused.
         */
        internal fun aggregateIdGenerators(
            properties: WowProperties,
            models: List<AggregateModel<*>>,
            default: AggregateIdGenerator,
            lookup: (String) -> AggregateIdGenerator?,
        ): AggregateIdGenerator {
            val served = models.associateBy { "${it.type.context}.${it.type.name}" }
            val selected = properties.aggregates.mapNotNull { (key, aggregate) ->
                aggregate.idGenerator?.let { name ->
                    val model = configNotNull(served[key], "wow.aggregates.[$key]", servedFix(served.keys)) {
                        "wow.aggregates.[$key].id-generator names no aggregate type this node serves."
                    }
                    val idGenerator = "wow.aggregates.[$key].id-generator"
                    val generatorFix =
                        "Name an AggregateIdGenerator bean, or a generator an AggregateIdGeneratorSource bean finds."
                    configCheck(name.isNotBlank(), idGenerator, name, generatorFix) { "$idGenerator is blank." }
                    val generator = configNotNull(lookup(name), idGenerator, generatorFix, name) {
                        "$idGenerator names $name, which is no AggregateIdGenerator bean and no generator an " +
                            "AggregateIdGeneratorSource bean finds."
                    }
                    model.type to generator
                }
            }.toMap()
            return AggregateIdGenerator.of(default, selected)
        }

        /** The [AggregateIdGenerator] bean of a name, else the generator the first of [sources] finds by it. */
        internal fun idGeneratorLookup(
            beanFactory: ConfigurableListableBeanFactory,
            sources: List<AggregateIdGeneratorSource>,
        ): (String) -> AggregateIdGenerator? = { name ->
            if (name in beanFactory.getBeanNamesForType(AggregateIdGenerator::class.java)) {
                beanFactory.getBean(name, AggregateIdGenerator::class.java)
            } else {
                sources.firstNotNullOfOrNull { it.find(name) }
            }
        }

        /**
         * The subscriptions of the annotated processors of the node's contexts (the generated
         * [ModelProvider.processors] of [providers], the selected ones on the class path), their processors built
         * from [dependencies], then those of every [SubscriptionFactory] bean in [factories], in their order.
         */
        internal fun bindings(
            providers: List<ModelProvider>,
            dependencies: Dependencies,
            factories: List<SubscriptionFactory>,
        ): SubscriptionFactory {
            val bindings = SubscriptionBindings.of(dependencies, providers)
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
