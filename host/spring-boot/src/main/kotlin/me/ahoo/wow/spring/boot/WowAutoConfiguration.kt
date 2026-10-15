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
import me.ahoo.wow.http.WowHttpOptions
import me.ahoo.wow.http.call.HttpCommandCaller
import me.ahoo.wow.http.call.ServiceCredential
import me.ahoo.wow.http.callRoutes
import me.ahoo.wow.http.caller.CallerResolver
import me.ahoo.wow.http.caller.InternalCallerResolver
import me.ahoo.wow.http.caller.ServiceTokens
import me.ahoo.wow.http.command.JakartaCommandValidator
import me.ahoo.wow.http.internal.HttpClusterChannel
import me.ahoo.wow.http.internal.HttpSignalSender
import me.ahoo.wow.http.internal.InternalHttp
import me.ahoo.wow.http.internal.internalRoutes
import me.ahoo.wow.http.ops.WebhookNotifier
import me.ahoo.wow.http.ops.parkedRoutes
import me.ahoo.wow.http.query.HttpQueries
import me.ahoo.wow.http.read.KernelStateReads
import me.ahoo.wow.http.read.StateReads
import me.ahoo.wow.http.wowRoutes
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.Dependencies
import me.ahoo.wow.kernel.ModelProvider
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.ServiceInfo
import me.ahoo.wow.runtime.admission.CommandAuthorizer
import me.ahoo.wow.runtime.admission.CommandGateway
import me.ahoo.wow.runtime.admission.CommandTransformer
import me.ahoo.wow.runtime.admission.CommandValidator
import me.ahoo.wow.runtime.admission.TenantPolicy
import me.ahoo.wow.runtime.binding.SubscriptionBindings
import me.ahoo.wow.runtime.call.CommandCaller
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
import me.ahoo.wow.runtime.subscription.ParkedNotifier
import me.ahoo.wow.runtime.wait.SignalSender
import me.ahoo.wow.spi.id.AggregateIdGenerator
import me.ahoo.wow.spi.id.AggregateIdGeneratorSource
import me.ahoo.wow.spi.id.GlobalIdGenerator
import me.ahoo.wow.spi.id.IdGenerators
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.observation.Observer
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.availability.ApplicationAvailability
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.reactor.netty.NettyServerCustomizer
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.util.ClassUtils
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.ServerResponse
import tools.jackson.databind.json.JsonMapper
import java.net.http.HttpClient
import kotlin.time.toKotlinDuration
import jakarta.validation.Validator as JakartaValidator

/**
 * The Wow V10 host (roadmap 5b.3; decisions D30, D44, D89, D105–D110, D114): by what the classpath and the
 * application's beans offer, it assembles one [ServiceNode] and serves it on two ports.
 *
 * - **Models and Manifest**: the binding index on the classpath ([ModelProvider.load]) of the contexts
 *   `wow.contexts.include` / `exclude` select ([ContextSelection], decision D169; all by default), the models' parameters from the
 *   application's beans ([BeanFactoryDependencies]), the Manifest merged from the modules' fragments
 *   ([ManifestLoader], read with [WowJson.mapper], never Spring's mapper), and with it the peers' Manifests on the
 *   classpath ([ContractDirectory], decision D139).
 * - **Backend** (`wow.backend`, [BackendSelection]): PostgreSQL over the application's `DataSource`, MongoDB over its
 *   `MongoClient`, in memory (one node, with a warning) when it has neither, or the application's own
 *   [BackendFactory] bean. With `wow.stores`, the named store instances instead ([StoreInstances]), each aggregate's
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
 *   [InternalServer] of their own at `wow.internal.port`, never on the public server, the node's address learned once
 *   it is bound when the port is 0; [HttpClusterChannel] and [HttpSignalSender] share one JDK client.
 * - **Synchronous calls** (decision D193): the sagas call other contexts' gateways through the [CommandCaller] bean,
 *   by default an [HttpCommandCaller] at `wow.calls.contexts.<context>.url`, the target's internal port, presenting
 *   the [ServiceCredential] bean (`wow.calls.token` as a bearer). The internal port serves the command routes of the
 *   calls ([callRoutes]) to the service identities of `wow.http.services`, by their tokens ([ServiceTokens]), or of
 *   the [InternalCallerResolver] bean (CoSec's service principals); the public port refuses `Wow-Call`.
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

    /** The contracts of the service's Manifest and of its peers on the class path (decision D139). */
    @Bean
    @ConditionalOnMissingBean
    fun wowContractDirectory(manifest: Manifest, beanFactory: ConfigurableListableBeanFactory): ContractDirectory =
        ContractDirectory.load(
            manifest,
            beanFactory.beanClassLoader ?: ClassUtils.getDefaultClassLoader()!!,
            WowJson.mapper
        )

    @Bean
    @ConditionalOnMissingBean
    @InternalWowApi
    fun wowBackends(
        properties: WowProperties,
        beanFactory: ConfigurableListableBeanFactory,
        factories: ObjectProvider<BackendFactory>,
        models: WowModels,
        queries: ObjectProvider<QueryProperties>,
        identity: ObjectProvider<NodeIdentity>,
    ): HostBackends {
        factories.ifAvailable?.let { return HostBackends.of(it) }
        val memoryQueries = queries.ifAvailable?.memory?.enabled == true
        val stores = StoreInstances.open(
            properties,
            beanFactory,
            beanFactory.beanClassLoader,
            memoryQueries,
            identity.ifAvailable?.service.orEmpty(),
        )
        val layout = StoreLayout.of(properties, stores, models.models)
        val memory = (layout.logStores + layout.snapshotStores).filter { stores.getValue(it).kind == BackendKind.MEMORY }
        if (memory.isNotEmpty()) {
            logger.warn {
                "Wow keeps data in memory on the stores $memory (wow.backend=memory, a store of type memory, or no " +
                    "DataSource or MongoClient backs it): it does not outlive the node, and serves one node only."
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
    fun wowClusterChannel(@Qualifier(INTERNAL_HTTP_CLIENT) client: HttpClient): ClusterChannel =
        HttpClusterChannel(client)

    @Bean
    @ConditionalOnMissingBean
    @InternalWowApi
    fun wowSignalSender(
        @Qualifier(INTERNAL_HTTP_CLIENT) client: HttpClient,
        properties: WowProperties,
    ): SignalSender = HttpSignalSender(client, queueCapacity = properties.internal.signalQueueCapacity)

    /** The credential of the node's synchronous calls (decision D193): `wow.calls.token` as a bearer, else none. */
    @Bean
    @ConditionalOnMissingBean
    @InternalWowApi
    fun wowServiceCredential(properties: WowProperties): ServiceCredential =
        properties.calls.token?.takeIf { it.isNotBlank() }?.let(ServiceCredential::bearer) ?: ServiceCredential.NONE

    /** The client of the other contexts' command gateways the sagas call (decision D193), at `wow.calls.contexts`. */
    @Bean
    @ConditionalOnMissingBean
    @InternalWowApi
    fun wowCommandCaller(properties: WowProperties, credential: ServiceCredential): CommandCaller {
        val calls = properties.calls
        return HttpCommandCaller(
            endpoints = calls.contexts.mapValues { (context, target) ->
                checkNotNull(target.url) { "wow.calls.contexts.$context.url is not set." }
            },
            credential = credential,
            client = HttpCommandCaller.client(calls.connectTimeout.toKotlinDuration()),
            timeout = calls.timeout.toKotlinDuration(),
        )
    }

    @Bean
    @ConditionalOnMissingBean
    @Suppress("LongParameterList") // One parameter per part of the node, each a bean the application may replace.
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
        listeners: ObjectProvider<ServiceNodeListener>,
        topics: ObjectProvider<SourceTopics>,
        notifiers: ObjectProvider<ParkedNotifier>,
        observers: ObjectProvider<Observer>,
        callers: ObjectProvider<CommandCaller>,
    ): ServiceNode {
        checkShutdownTimeout(environment)
        val routing = routing(properties, backends.kind)
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
                cluster = properties.cluster.options(),
                routing = routing,
                maxWaitTimeout = properties.wait.maxTimeout.toKotlinDuration(),
                maxWaitHops = properties.wait.maxHops,
                unknownWaitContexts = properties.wait.unknownContexts,
                subscription = properties.subscription.options(properties.subscriptions),
                appendQueue = properties.log.appendQueue.options(backends.appendQueue),
                laneCapacity = properties.executor.laneCapacity,
                stateCacheCapacity = properties.stateCache.capacity,
                defaultWaitTimeout = properties.wait.defaultTimeout.toKotlinDuration(),
                timers = properties.timers.options(),
                schedules = properties.schedules.mapValues { it.value.options() },
                kafkaRouted = kafkaRouted(properties, models.models),
                ledger = properties.ops.publish.options(),
                snapshots = properties.snapshot.options(),
            ),
            admission = AdmissionPlugins(
                authorizer = authorizers.getIfAvailable { CommandAuthorizer.ALLOW_ALL },
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
                subscriptions.orderedStream().toList() + sinks.orderedStream().map { it.factory }.toList(),
            ),
            listener = { step -> stepListeners.forEach { it.onStep(step) } },
            contracts = contracts,
            topics = topics.ifAvailable,
            parkedNotifier = ParkedNotifier.of(notifiers.orderedStream().toList()),
            observer = observers.getIfUnique { Observer.NONE },
            caller = callers.ifAvailable,
        )
    }

    @Bean
    @InternalWowApi
    fun wowBackendsLifecycle(backends: HostBackends): BackendsLifecycle = BackendsLifecycle(backends)

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
    ): InternalServer = InternalServer(
        properties.internal.port,
        identity.bindHost,
        internalRoutes(node.forwardHandler, node.waits).and(
            callRoutes(
                manifest,
                node.gateway,
                serviceTokens(properties).resolver(internalCallers.ifAvailable ?: callers),
                options,
            ),
        ),
    )

    /** Binds the internal port, then gives a node without a configured address the one it is bound at. */
    @Bean
    @InternalWowApi
    fun wowInternalServerLifecycle(
        server: InternalServer,
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
    ): ServiceNodeLifecycle = ServiceNodeLifecycle(node, events) { availability.ifAvailable }

    /**
     * The node's command gateway (decision D92), through which the application sends commands in process. The node
     * closes it when it stops.
     */
    @Bean(destroyMethod = "")
    fun wowCommandGateway(node: ServiceNode): CommandGateway = node.gateway

    @Bean
    @ConditionalOnMissingBean
    @InternalWowApi
    fun wowStateReads(node: ServiceNode, backends: HostBackends): StateReads {
        val opened = backends.contexts
        val contexts = node.contexts.mapNotNull { (name, context) ->
            opened[name]?.snapshots?.let { name to KernelStateReads.ContextReads(context.kernel, it) }
        }.toMap()
        return KernelStateReads(node.registry, contexts)
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
    )

    /** The public server keeps reading its HTTP/1.1 keep-alive connections ([KeepAliveReads]). */
    @Bean
    fun wowKeepAliveReads(): NettyServerCustomizer = NettyServerCustomizer { server ->
        server.doOnChannelInit { _, channel, _ -> KeepAliveReads.install(channel) }
    }

    /** The public routes, served by the application's WebFlux server; the query routes when [QueryAutoConfiguration] gives them. */
    @Bean
    @InternalWowApi
    fun wowRouterFunction(
        manifest: Manifest,
        node: ServiceNode,
        reads: StateReads,
        callers: CallerResolver,
        options: WowHttpOptions,
        queries: ObjectProvider<HttpQueries>,
    ): RouterFunction<ServerResponse> = wowRoutes(
        manifest,
        node.gateway,
        reads,
        callers,
        options,
        queries.getIfAvailable { HttpQueries.NONE },
    )

    /**
     * The operator's routes over the parked entries (roadmap P.1), when `wow.ops.enabled`: authenticated by the
     * application's [CallerResolver], their actions authorized by its [CommandAuthorizer] (decision D141).
     */
    @Bean
    @ConditionalOnProperty(prefix = "${WowProperties.PREFIX}.ops", name = ["enabled"], havingValue = "true")
    fun wowParkedRouterFunction(
        manifest: Manifest,
        node: ServiceNode,
        callers: CallerResolver,
        options: WowHttpOptions,
        authorizers: ObjectProvider<CommandAuthorizer>,
    ): RouterFunction<ServerResponse> = parkedRoutes(
        node.parked,
        manifest,
        authorizers.getIfAvailable { CommandAuthorizer.ALLOW_ALL },
        callers,
        options,
    )

    /** Tells `wow.ops.webhook.url` when an entry parks or spends its retries (roadmap P.1). */
    @Bean
    @ConditionalOnProperty(prefix = "${WowProperties.PREFIX}.ops.webhook", name = ["url"])
    @InternalWowApi
    fun wowWebhookNotifier(properties: WowProperties, identity: NodeIdentity): WebhookNotifier {
        val webhook = properties.ops.webhook
        return WebhookNotifier(
            uri = checkNotNull(webhook.url) { "wow.ops.webhook.url is not set." },
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
         * The service identities the internal port trusts by their static tokens (`wow.http.services`, decision D193), in
         * the default tenant.
         */
        internal fun serviceTokens(properties: WowProperties): ServiceTokens = ServiceTokens(
            properties.http.services.mapValues { it.value.tokens.toList() },
            properties.tenant.default,
        )

        /**
         * Where commands run: `wow.cluster.router`, by default local on the memory backend, whose stores no other
         * node shares, and partitioned on the others. The memory backend refuses partitioned routing.
         */
        internal fun routing(properties: WowProperties, kind: BackendKind?): Routing {
            val configured = properties.cluster.router
            if (kind == BackendKind.MEMORY) {
                check(configured != Routing.PARTITIONED) {
                    "wow.cluster.router=partitioned routes across nodes, which the memory backend cannot serve: " +
                        "back Wow with PostgreSQL or MongoDB."
                }
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
                    val model = checkNotNull(served[key]) {
                        "wow.aggregates.[$key].tenant names no aggregate type this node serves; it serves " +
                            "${served.keys}."
                    }
                    check(tenant.isNotBlank()) { "wow.aggregates.[$key].tenant is blank." }
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
                    checkNotNull(served[key]) {
                        "wow.aggregates.[$key].routing names no aggregate type this node serves; it serves ${served.keys}."
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
                    val model = checkNotNull(served[key]) {
                        "wow.aggregates.[$key].id-generator names no aggregate type this node serves; it serves " +
                            "${served.keys}."
                    }
                    check(name.isNotBlank()) { "wow.aggregates.[$key].id-generator is blank." }
                    val generator = checkNotNull(lookup(name)) {
                        "wow.aggregates.[$key].id-generator names $name, which is no AggregateIdGenerator bean and " +
                            "no generator an AggregateIdGeneratorSource bean finds."
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
