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

package me.ahoo.wow.spring.boot.starter

import me.ahoo.wow.runtime.WowRuntime
import me.ahoo.wow.spring.MessageDispatcherLauncher
import me.ahoo.wow.spring.WOW_RUNTIME_PHASE
import me.ahoo.wow.spring.WowRuntimeLifecycle
import org.springframework.aop.support.AopUtils
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.beans.factory.config.BeanFactoryPostProcessor
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.context.support.AbstractApplicationContext
import org.springframework.context.support.DefaultLifecycleProcessor
import org.springframework.core.Ordered
import org.springframework.core.PriorityOrdered
import java.time.Duration

/**
 * Fails context refresh when the single runtime ownership boundary is replaced or duplicated.
 */
internal class WowRuntimeOwnershipValidator :
    BeanFactoryPostProcessor,
    BeanPostProcessor,
    SmartInitializingSingleton,
    Ordered {
    private lateinit var beanFactory: ConfigurableListableBeanFactory

    override fun postProcessBeanFactory(beanFactory: ConfigurableListableBeanFactory) {
        this.beanFactory = beanFactory
        validateOwners(beanFactory)
    }

    override fun afterSingletonsInstantiated() {
        validateOwners(beanFactory)
    }

    override fun postProcessAfterInitialization(bean: Any, beanName: String): Any {
        val targetType = AopUtils.getTargetClass(bean)
        when {
            MessageDispatcherLauncher::class.java.isAssignableFrom(targetType) ->
                error(
                    "Legacy MessageDispatcherLauncher bean '$beanName' creates a second " +
                        "lifecycle owner. Remove it and let the canonical " +
                        "WowRuntimeLifecycle own every dispatcher.",
                )

            WowRuntime::class.java.isAssignableFrom(targetType) &&
                beanName != WOW_RUNTIME_BEAN_NAME ->
                error(
                    "Wow requires exactly one canonical WowRuntime bean named " +
                        "'$WOW_RUNTIME_BEAN_NAME'; remove additional bean '$beanName'.",
                )

            WowRuntimeLifecycle::class.java.isAssignableFrom(targetType) &&
                beanName != WOW_RUNTIME_LIFECYCLE_BEAN_NAME ->
                error(
                    "Wow requires exactly one canonical WowRuntimeLifecycle bean named " +
                        "'$WOW_RUNTIME_LIFECYCLE_BEAN_NAME'; remove additional bean '$beanName'.",
                )
        }
        return bean
    }

    private fun validateOwners(beanFactory: ConfigurableListableBeanFactory) {
        requireNoLegacyLaunchers(beanFactory)
        requireCanonicalOwner(
            beanFactory = beanFactory,
            ownerType = WowRuntime::class.java,
            canonicalBeanName = WOW_RUNTIME_BEAN_NAME,
        )
        requireCanonicalOwner(
            beanFactory = beanFactory,
            ownerType = WowRuntimeLifecycle::class.java,
            canonicalBeanName = WOW_RUNTIME_LIFECYCLE_BEAN_NAME,
        )
    }

    @Suppress("DEPRECATION")
    private fun requireNoLegacyLaunchers(beanFactory: ConfigurableListableBeanFactory) {
        val launcherNames = beanFactory
            .getBeanNamesForType(MessageDispatcherLauncher::class.java, true, false)
            .toList()
        check(launcherNames.isEmpty()) {
            "Legacy MessageDispatcherLauncher beans $launcherNames create a second lifecycle owner. " +
                "Remove them and let the canonical WowRuntimeLifecycle own every dispatcher."
        }
    }

    private fun requireCanonicalOwner(
        beanFactory: ConfigurableListableBeanFactory,
        ownerType: Class<*>,
        canonicalBeanName: String,
    ) {
        val ownerNames = beanFactory
            .getBeanNamesForType(ownerType, true, false)
            .toList()
        check(ownerNames.size == 1 && ownerNames.single() == canonicalBeanName) {
            "Wow requires exactly one canonical ${ownerType.simpleName} bean named " +
                "'$canonicalBeanName', but found: $ownerNames."
        }
    }

    override fun getOrder(): Int = Ordered.HIGHEST_PRECEDENCE
}

internal fun DefaultLifecycleProcessor.configureWowRuntimePhaseTimeout(
    shutdownTimeout: Duration,
) {
    setTimeoutForShutdownPhase(
        WOW_RUNTIME_PHASE,
        shutdownTimeout.plus(SHUTDOWN_PHASE_TIMEOUT_MARGIN).toMillis(),
    )
}

internal class WowRuntimeLifecycleProcessorCustomizer(
    private val initialShutdownTimeout: Duration,
) :
    BeanFactoryPostProcessor,
    BeanPostProcessor,
    SmartInitializingSingleton,
    PriorityOrdered {
    private lateinit var beanFactory: ConfigurableListableBeanFactory

    override fun postProcessBeanFactory(beanFactory: ConfigurableListableBeanFactory) {
        this.beanFactory = beanFactory
        val lifecycleProcessorBeanName = AbstractApplicationContext.LIFECYCLE_PROCESSOR_BEAN_NAME
        if (beanFactory.containsSingleton(lifecycleProcessorBeanName)) {
            configureLifecycleProcessor(
                beanFactory.getSingleton(lifecycleProcessorBeanName),
            )
        }
        if (!beanFactory.containsBeanDefinition(lifecycleProcessorBeanName)) {
            return
        }
        val lifecycleProcessorType = beanFactory.getType(lifecycleProcessorBeanName, false)
        when {
            lifecycleProcessorType == null -> return
            DefaultLifecycleProcessor::class.java.isAssignableFrom(lifecycleProcessorType) -> Unit
            lifecycleProcessorType.isAssignableFrom(DefaultLifecycleProcessor::class.java) -> return
            else -> error(
                "Wow requires the lifecycleProcessor to be a DefaultLifecycleProcessor so the " +
                    "Wow runtime phase can honor its shutdown deadline. Found: ${lifecycleProcessorType.name}.",
            )
        }
        val propertyValues = beanFactory.getBeanDefinition(lifecycleProcessorBeanName).propertyValues
        val configuredPhaseTimeouts =
            propertyValues.getPropertyValue(LIFECYCLE_PROCESSOR_PHASE_TIMEOUTS_PROPERTY)?.value
        check(configuredPhaseTimeouts == null || configuredPhaseTimeouts is Map<*, *>) {
            "The lifecycleProcessor property '$LIFECYCLE_PROCESSOR_PHASE_TIMEOUTS_PROPERTY' " +
                "must be a Map, but found: ${configuredPhaseTimeouts?.javaClass?.name}."
        }
        val mergedPhaseTimeouts = linkedMapOf<Any?, Any?>()
        if (configuredPhaseTimeouts is Map<*, *>) {
            mergedPhaseTimeouts.putAll(configuredPhaseTimeouts)
        }
        mergedPhaseTimeouts[WOW_RUNTIME_PHASE] =
            initialShutdownTimeout.plus(SHUTDOWN_PHASE_TIMEOUT_MARGIN).toMillis()
        propertyValues.add(
            LIFECYCLE_PROCESSOR_PHASE_TIMEOUTS_PROPERTY,
            mergedPhaseTimeouts,
        )
    }

    override fun afterSingletonsInstantiated() {
        val lifecycleProcessorBeanName = AbstractApplicationContext.LIFECYCLE_PROCESSOR_BEAN_NAME
        val runtime = beanFactory.getBean(WOW_RUNTIME_BEAN_NAME, WowRuntime::class.java)
        configureLifecycleProcessor(
            bean = beanFactory.getBean(lifecycleProcessorBeanName),
            shutdownTimeout = runtime.shutdownTimeout,
        )
    }

    override fun postProcessBeforeInitialization(bean: Any, beanName: String): Any {
        if (beanName != AbstractApplicationContext.LIFECYCLE_PROCESSOR_BEAN_NAME) {
            return bean
        }
        configureLifecycleProcessor(bean, initialShutdownTimeout)
        return bean
    }

    private fun configureLifecycleProcessor(
        bean: Any?,
        shutdownTimeout: Duration = initialShutdownTimeout,
    ) {
        check(bean is DefaultLifecycleProcessor) {
            "Wow requires the lifecycleProcessor to be a DefaultLifecycleProcessor so the " +
                "Wow runtime phase can honor its shutdown deadline. Found: ${bean?.javaClass?.name}."
        }
        bean.configureWowRuntimePhaseTimeout(shutdownTimeout)
    }

    override fun getOrder(): Int = Ordered.HIGHEST_PRECEDENCE
}

const val WOW_RUNTIME_BEAN_NAME = "wowRuntime"
const val WOW_RUNTIME_LIFECYCLE_BEAN_NAME = "wowRuntimeLifecycle"
internal const val RUNTIME_COMPONENT_REGISTRY_BEAN_NAME = "runtimeComponentRegistry"
internal const val WOW_RUNTIME_OWNERSHIP_VALIDATOR_BEAN_NAME = "wowRuntimeOwnershipValidator"
internal const val WOW_RUNTIME_LIFECYCLE_PROCESSOR_CUSTOMIZER_BEAN_NAME =
    "wowRuntimeLifecycleProcessorCustomizer"
private val SHUTDOWN_PHASE_TIMEOUT_MARGIN: Duration = Duration.ofSeconds(1)
private const val LIFECYCLE_PROCESSOR_PHASE_TIMEOUTS_PROPERTY = "timeoutsForShutdownPhases"
