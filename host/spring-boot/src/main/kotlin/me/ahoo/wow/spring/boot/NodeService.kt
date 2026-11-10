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
import me.ahoo.wow.http.ops.WebhookNotifier
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
import me.ahoo.wow.runtime.node.AdmissionPlugins
import me.ahoo.wow.runtime.node.ServiceNode
import me.ahoo.wow.runtime.node.SourceTopics
import me.ahoo.wow.runtime.node.SubscriptionFactory
import me.ahoo.wow.runtime.sink.PublishedSinks
import me.ahoo.wow.runtime.state.KernelStateReads
import me.ahoo.wow.runtime.state.ReplayGate
import me.ahoo.wow.runtime.state.StateReads
import me.ahoo.wow.runtime.subscription.ParkedNotifier
import me.ahoo.wow.runtime.wait.SignalSender
import me.ahoo.wow.spi.call.CommandCaller
import me.ahoo.wow.spi.config.configNotNull
import me.ahoo.wow.spi.id.AggregateIdGenerator
import me.ahoo.wow.spi.id.AggregateIdGeneratorSource
import me.ahoo.wow.spi.id.GlobalIdGenerator
import me.ahoo.wow.spi.id.IdGenerators
import me.ahoo.wow.spi.observation.Observer
import me.ahoo.wow.spi.subscription.FailureClassifier
import me.ahoo.wow.spi.transport.TransportProvider
import me.ahoo.wow.spring.boot.NodeAdmission.Companion.aggregateIdGenerators
import me.ahoo.wow.spring.boot.NodeAdmission.Companion.idGeneratorLookup
import me.ahoo.wow.spring.boot.NodeAdmission.Companion.tenants
import me.ahoo.wow.spring.boot.NodeAdmission.Companion.writePolicies
import me.ahoo.wow.spring.boot.WowAutoConfiguration.Companion.WOW_AGGREGATE_ID_GENERATOR
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.availability.ApplicationAvailability
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import java.time.Clock
import kotlin.time.toKotlinDuration

/**
 * The node itself: the [ServiceNode] assembled from what the node is made of (its models, its stores, its admission, its
 * peers and its ports) with the application's subscriptions, sinks, listeners and observers; its lifecycle
 * ([ServiceNodeLifecycle]), its command gateway, the state reads over its contexts ([StateReads]), and the webhook it
 * tells of its parked entries ([WebhookNotifier]). Imported by [WowAutoConfiguration], whose order and conditions it
 * shares.
 */
@Configuration(proxyBeanMethods = false)
internal class NodeService {
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
    @InternalWowApi
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

    internal companion object {
        /** The host's log category: the lines that moved here from [WowAutoConfiguration] keep it. */
        private val logger = KotlinLogging.logger(WowAutoConfiguration::class.java.name)

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
