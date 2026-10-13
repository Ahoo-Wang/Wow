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
import me.ahoo.wow.http.WowHttpOptions
import me.ahoo.wow.http.caller.CallerResolver
import me.ahoo.wow.http.command.JakartaCommandValidator
import me.ahoo.wow.http.internal.HttpClusterChannel
import me.ahoo.wow.http.internal.HttpSignalSender
import me.ahoo.wow.http.internal.InternalHttp
import me.ahoo.wow.http.internal.internalRoutes
import me.ahoo.wow.http.read.KernelStateReads
import me.ahoo.wow.http.read.StateReads
import me.ahoo.wow.http.wowRoutes
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.Dependencies
import me.ahoo.wow.kernel.ModelProvider
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.ServiceInfo
import me.ahoo.wow.runtime.admission.Caller
import me.ahoo.wow.runtime.admission.CommandAuthorizer
import me.ahoo.wow.runtime.admission.CommandRewriter
import me.ahoo.wow.runtime.admission.CommandValidator
import me.ahoo.wow.runtime.admission.IdGenerator
import me.ahoo.wow.runtime.admission.TenantPolicy
import me.ahoo.wow.runtime.binding.SubscriptionBindings
import me.ahoo.wow.runtime.cluster.ClusterChannel
import me.ahoo.wow.runtime.contract.ContractDirectory
import me.ahoo.wow.runtime.manifest.ManifestLoader
import me.ahoo.wow.runtime.node.AdmissionPlugins
import me.ahoo.wow.runtime.node.BackendFactory
import me.ahoo.wow.runtime.node.Routing
import me.ahoo.wow.runtime.node.ServiceNode
import me.ahoo.wow.runtime.node.ServiceNodeOptions
import me.ahoo.wow.runtime.node.SubscriptionFactory
import me.ahoo.wow.runtime.wait.SignalSender
import me.ahoo.wow.spi.json.WowJson
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.availability.ApplicationAvailability
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.util.ClassUtils
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.ServerResponse
import tools.jackson.databind.json.JsonMapper
import java.net.http.HttpClient
import javax.sql.DataSource
import kotlin.time.toKotlinDuration
import jakarta.validation.Validator as JakartaValidator
import me.ahoo.cosid.provider.IdGeneratorProvider as CosIdGeneratorProvider

/**
 * The Wow V10 host (roadmap 5b.3; decisions D30, D44, D89, D105–D110, D114): by what the classpath and the
 * application's beans offer, it assembles one [ServiceNode] and serves it on two ports.
 *
 * - **Models and Manifest**: the binding index on the classpath ([ModelProvider.load]), the models' parameters from the
 *   application's beans ([BeanFactoryDependencies]), the Manifest merged from the modules' fragments
 *   ([ManifestLoader], read with [WowJson.mapper], never Spring's mapper), and with it the peers' Manifests on the
 *   classpath ([ContractDirectory], decision D139).
 * - **Backend** (`wow.backend`, [BackendSelection]): PostgreSQL over the application's `DataSource`, MongoDB over its
 *   `MongoClient`, in memory (one node, with a warning) when it has neither, or the application's own
 *   [BackendFactory] bean.
 * - **Admission**: the application's [CommandAuthorizer], [CommandRewriter]s and [CommandValidator]s, the Jakarta
 *   validator when it has a Bean Validation provider (D106), the [IdGenerator]: CosId's shared generator when it
 *   runs CosId, UUIDv7 otherwise (D114), the fixed tenants of `wow.aggregates.*.tenant` (D116), and
 *   `wow.wait.default-timeout`, the node's default wait timeout for every entry (D64).
 * - **Ports**: the public routes ([wowRoutes]) on the application's WebFlux server, authenticated by its
 *   [CallerResolver] (anonymous otherwise, D97); the cluster channel and the wait signals ([internalRoutes]) on an
 *   [InternalServer] of their own at `wow.internal.port`, never on the public server, the node's address learned once
 *   it is bound when the port is 0; [HttpClusterChannel] and [HttpSignalSender] share one JDK client.
 * - **Subscriptions**: the annotated processors' handlers the binding indexes list ([ModelProvider.processors], built
 *   from the application's beans), then the application's own [SubscriptionFactory] bean.
 * - **Lifecycle**: [WowLifecycles].
 */
@AutoConfiguration(
    afterName = [
        "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
        "org.springframework.boot.mongodb.autoconfigure.MongoAutoConfiguration",
        "org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration",
        "me.ahoo.cosid.spring.boot.starter.CosIdAutoConfiguration",
    ],
)
@EnableConfigurationProperties(WowProperties::class)
class WowAutoConfiguration {
    /** Wow's JSON mapper (decision D105); not a default candidate, so Spring's own mapper stays Spring's. */
    @Bean(WOW_JSON_MAPPER, defaultCandidate = false)
    fun wowJsonMapper(): JsonMapper = WowJson.mapper

    @Bean
    @ConditionalOnMissingBean
    fun wowNodeIdentity(properties: WowProperties, environment: Environment): NodeIdentity =
        NodeIdentity.of(properties, environment)

    @Bean
    @ConditionalOnMissingBean
    fun wowDependencies(beanFactory: ConfigurableListableBeanFactory): Dependencies =
        BeanFactoryDependencies(beanFactory)

    @Bean
    @ConditionalOnMissingBean
    fun wowModels(beanFactory: ConfigurableListableBeanFactory, dependencies: Dependencies): WowModels {
        val providers = ModelProvider.load(beanFactory.beanClassLoader)
        return WowModels(providers, ServiceNode.models(dependencies, providers))
    }

    @Bean
    @ConditionalOnMissingBean
    fun wowManifestLoader(beanFactory: ConfigurableListableBeanFactory): ManifestLoader =
        ManifestLoader(beanFactory.beanClassLoader ?: ClassUtils.getDefaultClassLoader()!!, WowJson.mapper)

    @Bean
    @ConditionalOnMissingBean
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
    fun wowBackends(
        properties: WowProperties,
        beanFactory: ConfigurableListableBeanFactory,
        factories: ObjectProvider<BackendFactory>,
    ): HostBackends {
        factories.ifAvailable?.let { return HostBackends.of(it) }
        val classLoader = beanFactory.beanClassLoader
        val postgres = ClassUtils.isPresent(POSTGRES_LOG, classLoader) &&
            beanFactory.getBeanNamesForType(DataSource::class.java).isNotEmpty()
        val mongoClient = MONGO_CLIENT.takeIf { ClassUtils.isPresent(MONGO_LOG, classLoader) }
            ?.takeIf { ClassUtils.isPresent(it, classLoader) }
            ?.let { ClassUtils.forName(it, classLoader) }
        val mongo = mongoClient != null && beanFactory.getBeanNamesForType(mongoClient).isNotEmpty()
        val partitions = properties.log.partitions
        return when (BackendSelection.select(properties.backend, postgres, mongo)) {
            BackendKind.POSTGRES -> PostgresBackends.open(beanFactory.getBean(DataSource::class.java), partitions)
            BackendKind.MONGO ->
                MongoBackends.open(beanFactory.getBean(mongoClient!!), properties.mongo.database, partitions)

            else -> {
                logger.warn {
                    "Wow runs on the memory backend (wow.backend=memory, or no DataSource or MongoClient backs it): " +
                        "its data does not outlive the node, and it serves one node only."
                }
                HostBackends.memory(partitions)
            }
        }
    }

    @Bean
    @ConditionalOnMissingBean
    fun wowIdGenerator(): IdGenerator = IdGenerator.UUID_V7

    @Bean(defaultCandidate = false)
    @ConditionalOnMissingBean(name = [INTERNAL_HTTP_CLIENT])
    fun wowInternalHttpClient(properties: WowProperties): HttpClient =
        InternalHttp.client(properties.internal.connectTimeout.toKotlinDuration())

    @Bean
    @ConditionalOnMissingBean
    fun wowClusterChannel(@Qualifier(INTERNAL_HTTP_CLIENT) client: HttpClient): ClusterChannel =
        HttpClusterChannel(client)

    @Bean
    @ConditionalOnMissingBean
    fun wowSignalSender(
        @Qualifier(INTERNAL_HTTP_CLIENT) client: HttpClient,
        properties: WowProperties,
    ): SignalSender = HttpSignalSender(client, queueCapacity = properties.internal.signalQueueCapacity)

    @Bean
    @ConditionalOnMissingBean
    @Suppress("LongParameterList") // One parameter per part of the node, each a bean the application may replace.
    fun wowServiceNode(
        properties: WowProperties,
        environment: Environment,
        identity: NodeIdentity,
        models: WowModels,
        manifest: Manifest,
        contracts: ContractDirectory,
        backends: HostBackends,
        beanFactory: ConfigurableListableBeanFactory,
        dependencies: Dependencies,
        channel: ClusterChannel,
        signals: SignalSender,
        ids: IdGenerator,
        authorizers: ObjectProvider<CommandAuthorizer>,
        rewriters: ObjectProvider<CommandRewriter>,
        validators: ObjectProvider<CommandValidator>,
        subscriptions: ObjectProvider<SubscriptionFactory>,
        listeners: ObjectProvider<ServiceNodeListener>,
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
                subscription = properties.subscription.options(properties.subscriptions),
                appendQueue = properties.log.appendQueue.options(backends.appendQueue),
                laneCapacity = properties.executor.laneCapacity,
                stateCacheCapacity = properties.stateCache.capacity,
                defaultWaitTimeout = properties.wait.defaultTimeout.toKotlinDuration(),
            ),
            admission = AdmissionPlugins(
                authorizer = authorizers.getIfAvailable { CommandAuthorizer.ALLOW_ALL },
                rewriters = rewriters.orderedStream().toList(),
                validators = validators.orderedStream().toList(),
                ids = ids,
                tenants = tenants(properties, models.models),
            ),
            subscriptions = bindings(beanFactory, dependencies, subscriptions.ifAvailable),
            listener = { step -> stepListeners.forEach { it.onStep(step) } },
            contracts = contracts,
        )
    }

    @Bean
    fun wowBackendsLifecycle(backends: HostBackends): BackendsLifecycle = BackendsLifecycle(backends)

    @Bean
    @ConditionalOnMissingBean
    fun wowInternalServer(properties: WowProperties, node: ServiceNode): InternalServer =
        InternalServer(
            properties.internal.port,
            properties.internal.host,
            internalRoutes(node.forwardHandler, node.waits)
        )

    /** Binds the internal port, then gives a node without a configured address the one it is bound at. */
    @Bean
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
    fun wowServiceNodeLifecycle(
        node: ServiceNode,
        events: ApplicationEventPublisher,
        availability: ObjectProvider<ApplicationAvailability>,
    ): ServiceNodeLifecycle = ServiceNodeLifecycle(node, events) { availability.ifAvailable }

    @Bean
    @ConditionalOnMissingBean
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
    )

    /** The public routes, served by the application's WebFlux server. */
    @Bean
    fun wowRouterFunction(
        manifest: Manifest,
        node: ServiceNode,
        reads: StateReads,
        callers: CallerResolver,
        options: WowHttpOptions,
    ): RouterFunction<ServerResponse> = wowRoutes(manifest, node.gateway, reads, callers, options)

    /** Activated when the application has a Bean Validation provider (decision D106). */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = [JAKARTA_VALIDATOR])
    class JakartaValidation {
        @Bean
        @ConditionalOnBean(type = [JAKARTA_VALIDATOR])
        @ConditionalOnMissingBean
        fun wowJakartaCommandValidator(validator: ObjectProvider<JakartaValidator>): JakartaCommandValidator =
            JakartaCommandValidator(validator.getObject())
    }

    /** CosId's shared generator, when the application runs CosId (decision D114). */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = [COSID_PROVIDER])
    class CosId {
        @Bean
        @ConditionalOnBean(type = [COSID_PROVIDER])
        @ConditionalOnMissingBean
        fun wowCosIdGenerator(provider: CosIdGeneratorProvider): IdGenerator = CosIdGenerator(provider)
    }

    companion object {
        const val WOW_JSON_MAPPER: String = "wowJsonMapper"
        const val INTERNAL_HTTP_CLIENT: String = "wowInternalHttpClient"
        private const val POSTGRES_LOG = "me.ahoo.wow.postgres.PostgresEventLog"
        private const val MONGO_LOG = "me.ahoo.wow.mongo.log.MongoEventLog"
        private const val MONGO_CLIENT = "com.mongodb.client.MongoClient"
        private const val JAKARTA_VALIDATOR = "jakarta.validation.Validator"
        private const val COSID_PROVIDER = "me.ahoo.cosid.provider.IdGeneratorProvider"
        private val logger = KotlinLogging.logger {}

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
         * The subscriptions of the annotated processors on the class path (their modules' generated
         * [ModelProvider.processors]), their processors built from [dependencies], then the application's own
         * [SubscriptionFactory] bean, when it has one.
         */
        internal fun bindings(
            beanFactory: ConfigurableListableBeanFactory,
            dependencies: Dependencies,
            application: SubscriptionFactory?,
        ): SubscriptionFactory {
            val bindings = SubscriptionBindings.of(dependencies, ModelProvider.load(beanFactory.beanClassLoader))
            return if (application == null) bindings else bindings + application
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

/** [IdGenerator] over CosId's shared generator (decision D114), looked up for each id. */
class CosIdGenerator(private val provider: CosIdGeneratorProvider) : IdGenerator {
    override fun next(): String = provider.share.generateAsString()
}
