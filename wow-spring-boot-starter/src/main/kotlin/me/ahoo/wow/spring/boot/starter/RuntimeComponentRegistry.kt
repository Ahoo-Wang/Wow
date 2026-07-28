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

import me.ahoo.wow.infra.lifecycle.ForceStoppable
import me.ahoo.wow.infra.lifecycle.Lifecycle
import me.ahoo.wow.infra.lifecycle.forceStopAll
import me.ahoo.wow.messaging.dispatcher.MessageDispatcher
import me.ahoo.wow.runtime.RuntimeComponent
import me.ahoo.wow.runtime.RuntimeLifecycleAdapter
import me.ahoo.wow.runtime.RuntimePreparable
import me.ahoo.wow.runtime.WowRuntime
import me.ahoo.wow.spring.WowRuntimeComponent
import org.springframework.aop.framework.Advised
import org.springframework.aop.scope.ScopedProxyFactoryBean
import org.springframework.aop.scope.ScopedProxyUtils
import org.springframework.aop.support.AopUtils
import org.springframework.beans.factory.BeanFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.FactoryBean
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.beans.factory.config.BeanDefinition
import org.springframework.beans.factory.config.BeanFactoryPostProcessor
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.beans.factory.support.AbstractBeanDefinition
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.beans.factory.support.MergedBeanDefinitionPostProcessor
import org.springframework.beans.factory.support.RootBeanDefinition
import org.springframework.core.PriorityOrdered
import org.springframework.util.ReflectionUtils
import java.time.Duration
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import org.springframework.context.Lifecycle as SpringLifecycle

/**
 * Canonical Spring membership and ownership boundary for [WowRuntimeComponent]s
 * and [MessageDispatcher]s.
 *
 * Membership is derived once from the Spring-declared bean types without eager
 * initialization. The immutable descriptor snapshot is then used both to build
 * the Wow runtime and to suppress Spring's independent component destruction.
 */
internal class RuntimeComponentRegistry :
    BeanFactoryPostProcessor,
    MergedBeanDefinitionPostProcessor,
    BeanPostProcessor,
    SmartInitializingSingleton,
    DisposableBean,
    PriorityOrdered {
    private val monitor = Any()
    private val memberNames = linkedSetOf<String>()
    private lateinit var beanFactory: DefaultListableBeanFactory
    private var state = State.NEW
    private var frozenSnapshot: List<RuntimeComponentDescriptor> = emptyList()
    private val snapshotFuture = CompletableFuture<List<RuntimeComponentDescriptor>>()
    private var resolvingThread: Thread? = null
    private var resolutionFailure: Throwable? = null
    private val fallbackDescriptors = linkedMapOf<String, RuntimeComponentDescriptor>()
    private var runtimeOwner: WowRuntime? = null
    private var destroyed = false

    override fun postProcessBeanFactory(beanFactory: ConfigurableListableBeanFactory) {
        check(beanFactory is DefaultListableBeanFactory) {
            "Wow runtime component discovery requires DefaultListableBeanFactory, but found: " +
                beanFactory.javaClass.name
        }
        val declaredMembers = synchronized(monitor) {
            check(state == State.NEW) {
                "Runtime component membership can only be initialized once. Current state: $state."
            }
            this.beanFactory = beanFactory
            memberNames += beanFactory.declaredRuntimeComponentNames()
            state = State.COLLECTING
            memberNames.toList()
        }
        declaredMembers.forEach { beanName ->
            beanFactory.registerDependentBean(
                beanName,
                RUNTIME_COMPONENT_REGISTRY_BEAN_NAME,
            )
            configureDeclaredOwnership(beanName)
        }
    }

    /**
     * Freezes the declared membership and resolves one stable lifecycle target
     * for each member. Every caller observes the same immutable descriptor list.
     */
    @Suppress("ThrowsCount", "TooGenericExceptionCaught")
    internal fun snapshot(): List<RuntimeComponentDescriptor> {
        val resolveSnapshot = synchronized(monitor) {
            when (state) {
                State.FROZEN -> return frozenSnapshot
                State.FAILED -> throw IllegalStateException(
                    "Runtime component membership resolution previously failed.",
                    resolutionFailure,
                )

                State.RESOLVING -> {
                    check(resolvingThread !== Thread.currentThread()) {
                        "Runtime component membership resolution is recursive. " +
                            "Runtime component beans must not depend on WowRuntime."
                    }
                    false
                }

                State.NEW -> error("Runtime component membership has not been initialized.")
                State.COLLECTING -> {
                    state = State.RESOLVING
                    resolvingThread = Thread.currentThread()
                    true
                }
            }
        }

        if (!resolveSnapshot) {
            try {
                return snapshotFuture.join()
            } catch (error: CompletionException) {
                throw IllegalStateException(
                    "Runtime component membership resolution failed.",
                    error.cause ?: error,
                )
            }
        }

        try {
            val resolved = resolveSnapshot()
            synchronized(monitor) {
                frozenSnapshot = resolved
                state = State.FROZEN
                resolvingThread = null
            }
            snapshotFuture.complete(resolved)
            return resolved
        } catch (error: Throwable) {
            synchronized(monitor) {
                state = State.FAILED
                resolvingThread = null
                resolutionFailure = error
            }
            snapshotFuture.completeExceptionally(error)
            throw error
        }
    }

    override fun postProcessMergedBeanDefinition(
        beanDefinition: RootBeanDefinition,
        beanType: Class<*>,
        beanName: String,
    ) {
        if (
            !isMember(beanName) ||
            FactoryBean::class.java.isAssignableFrom(beanType) ||
            !beanType.isRuntimeOwnershipEligible()
        ) {
            return
        }
        configureRuntimeOwnership(beanName, beanType, beanDefinition)
    }

    override fun postProcessBeforeInitialization(bean: Any, beanName: String): Any {
        validateMaterializedMembership(bean, beanName)
        if (!isMember(beanName) || bean is FactoryBean<*>) {
            return bean
        }
        val descriptor = resolveMaterializedDescriptor(beanName, bean)
            ?: return bean
        registerMaterializedFallback(descriptor)
        validateRuntimeOwnership(beanName, bean.javaClass)
        validateRuntimeOwnership(beanName, descriptor.lifecycleTarget.javaClass)
        configureBeanDefinitionOwnership(beanName, descriptor.lifecycleTarget.javaClass)
        return bean
    }

    override fun postProcessAfterInitialization(bean: Any, beanName: String): Any {
        validateMaterializedMembership(bean, beanName)
        if (!isMember(beanName) || bean is FactoryBean<*>) {
            return bean
        }
        val descriptor = resolveMaterializedDescriptor(beanName, bean)
            ?: return bean
        registerMaterializedFallback(descriptor)
        validateRuntimeOwnership(beanName, bean.javaClass)
        validateRuntimeOwnership(beanName, descriptor.lifecycleTarget.javaClass)
        return bean
    }

    override fun afterSingletonsInstantiated() {
        snapshot().forEach { descriptor ->
            val beanName = descriptor.beanName
            if (!beanFactory.containsBeanDefinition(beanName) || beanFactory.isFactoryDefinition(beanName)) {
                return@forEach
            }
            beanFactory.ownershipBeanDefinitions(beanName).forEach { beanDefinition ->
                val destroyMethods = beanDefinition.destroyMethods().filter(String::isNotEmpty)
                check(
                    beanDefinition.getAttribute(OWNERSHIP_PROCESSED_ATTRIBUTE) == true &&
                        destroyMethods.isEmpty(),
                ) {
                    "Runtime-owned bean '$beanName' restored destroy methods $destroyMethods after " +
                        "the canonical runtime membership was frozen. WowRuntime must remain the " +
                        "only component shutdown owner."
                }
            }
        }
    }

    /**
     * Transfers fallback cleanup to the fully constructed canonical runtime.
     *
     * The registry remains a destruction backstop: failed context refresh can
     * occur after the factory method returns but before Spring starts the
     * lifecycle processor.
     */
    internal fun attachRuntime(runtime: WowRuntime) {
        synchronized(monitor) {
            check(!destroyed) {
                "Runtime component registry was destroyed before runtime ownership transfer."
            }
            check(runtimeOwner == null) {
                "Runtime ownership has already been transferred."
            }
            runtimeOwner = runtime
        }
    }

    /**
     * Closes the ownership handoff gap during failed ApplicationContext refresh.
     */
    override fun destroy() {
        val cleanup = synchronized(monitor) {
            if (destroyed) {
                return
            }
            destroyed = true
            val cleanupTargets =
                Collections.newSetFromMap(IdentityHashMap<Lifecycle, Boolean>())
            RuntimeFallbackCleanup(
                runtime = runtimeOwner,
                components = fallbackDescriptors.values
                    .sortedBy(RuntimeComponentDescriptor::order)
                    .asReversed()
                    .filter { descriptor ->
                        cleanupTargets.add(descriptor.lifecycleTarget)
                    }
                    .map(RuntimeComponentDescriptor::runtimeComponent),
            )
        }
        val runtime = cleanup.runtime
        if (runtime == null) {
            cleanup.components
                .map { component ->
                    ForceStoppable {
                        WowRuntime(
                            components = listOf(component),
                            shutdownTimeout = FALLBACK_SHUTDOWN_TIMEOUT,
                            shutdownQuietPeriod = Duration.ZERO,
                        ).forceStop()
                    }
                }
                .forceStopAll()
        } else {
            runtime.forceStop()
        }
    }

    override fun getOrder(): Int = PriorityOrdered.HIGHEST_PRECEDENCE

    private fun resolveSnapshot(): List<RuntimeComponentDescriptor> {
        val names = memberNames.toList()
        names.forEach { beanName ->
            beanFactory.validateRuntimeComponentScope(beanName)
        }

        val descriptors = names.map { beanName ->
            val exposedBean = beanFactory.getBean(beanName)
            check(exposedBean is Lifecycle) {
                "Runtime-owned bean '$beanName' does not implement Lifecycle: " +
                    exposedBean.javaClass.name
            }
            validateRuntimeOwnership(beanName, exposedBean.javaClass)
            val lifecycleTarget = resolveStableRuntimeTarget(beanName, exposedBean)
            validateRuntimeOwnership(beanName, lifecycleTarget.javaClass)
            val runtimeComponent = lifecycleTarget.toRuntimeComponent(beanName)
            RuntimeComponentDescriptor(
                beanName = beanName,
                lifecycleTarget = lifecycleTarget,
                runtimeComponent = runtimeComponent,
                order = beanFactory.getOrder(beanName, exposedBean),
            ).also(::registerFallback)
        }

        val owners = IdentityHashMap<Lifecycle, RuntimeComponentDescriptor>()
        descriptors.forEach { descriptor ->
            val existing = owners.put(descriptor.lifecycleTarget, descriptor)
            check(existing == null) {
                "Runtime-owned beans '${existing!!.beanName}' and '${descriptor.beanName}' " +
                    "resolve to the same singleton lifecycle target. Register exactly one bean " +
                    "for each WowRuntime component."
            }
        }
        return descriptors.sortedBy(RuntimeComponentDescriptor::order).toList()
    }

    private fun registerFallback(descriptor: RuntimeComponentDescriptor) {
        synchronized(monitor) {
            fallbackDescriptors[descriptor.beanName] = descriptor
        }
    }

    private fun registerMaterializedFallback(descriptor: RuntimeComponentDescriptor) {
        synchronized(monitor) {
            if (fallbackDescriptors.containsKey(descriptor.beanName)) {
                return
            }
            fallbackDescriptors[descriptor.beanName] =
                descriptor.copy(order = fallbackDescriptors.size)
        }
    }

    private fun resolveMaterializedDescriptor(
        beanName: String,
        bean: Any,
    ): RuntimeComponentDescriptor? {
        val exposedBean = bean as? Lifecycle ?: return null
        val lifecycleTarget = resolveStableRuntimeTarget(beanName, exposedBean)
        if (!lifecycleTarget.javaClass.isRuntimeOwnershipEligible()) {
            return null
        }
        return RuntimeComponentDescriptor(
            beanName = beanName,
            lifecycleTarget = lifecycleTarget,
            runtimeComponent = lifecycleTarget.toRuntimeComponent(beanName),
            order = 0,
        )
    }

    private fun configureDeclaredOwnership(beanName: String) {
        if (!beanFactory.containsBeanDefinition(beanName) || beanFactory.isFactoryDefinition(beanName)) {
            return
        }
        val beanType = beanFactory.getType(beanName, false) ?: return
        configureRuntimeOwnership(
            beanName = beanName,
            beanType = beanType,
            beanDefinition = beanFactory.getBeanDefinition(beanName),
        )
    }

    private fun configureBeanDefinitionOwnership(beanName: String, beanType: Class<*>) {
        if (!beanFactory.containsBeanDefinition(beanName) || beanFactory.isFactoryDefinition(beanName)) {
            return
        }
        beanFactory.ownershipBeanDefinitions(beanName).forEach { beanDefinition ->
            configureRuntimeOwnership(
                beanName = beanName,
                beanType = beanType,
                beanDefinition = beanDefinition,
            )
        }
    }

    private fun configureRuntimeOwnership(
        beanName: String,
        beanType: Class<*>,
        beanDefinition: BeanDefinition,
    ) {
        if (!beanType.isRuntimeOwnershipEligible()) {
            return
        }
        validateRuntimeOwnership(beanName, beanType)
        val explicitDestroyMethods = beanDefinition.destroyMethods()
            .filter { destroyMethod ->
                destroyMethod.isNotEmpty() && destroyMethod != AbstractBeanDefinition.INFER_METHOD
            }
        check(explicitDestroyMethods.isEmpty()) {
            "Runtime-owned bean '$beanName' declares explicit destroy methods $explicitDestroyMethods. " +
                "WowRuntime exclusively owns component shutdown; move cleanup to " +
                "stopGracefully()/forceStop() or set destroyMethod=\"\"."
        }
        beanDefinition.destroyMethodName = ""
        beanDefinition.setAttribute(OWNERSHIP_PROCESSED_ATTRIBUTE, true)
    }

    private fun validateMaterializedMembership(bean: Any, beanName: String) {
        if (isMember(beanName)) {
            return
        }
        val targetType = AopUtils.getTargetClass(bean)
        if (
            !bean.javaClass.isSpringRuntimeCandidate() &&
            !targetType.isSpringRuntimeCandidate()
        ) {
            return
        }
        val declaredType = beanFactory.declaredBeanTypeName(beanName)
        error(
            "Runtime component bean '$beanName' has actual type '${bean.javaClass.name}' " +
                "and target type '${targetType.name}' but was " +
                "not included in the canonical runtime membership because its declared Spring bean type " +
                "'$declaredType' does not expose MessageDispatcher or WowRuntimeComponent. Declare the " +
                "@Bean return type as MessageDispatcher, WowRuntimeComponent, or the concrete implementation.",
        )
    }

    private fun validateRuntimeOwnership(beanName: String, beanType: Class<*>) {
        check(!DisposableBean::class.java.isAssignableFrom(beanType)) {
            "Runtime-owned bean '$beanName' implements DisposableBean. WowRuntime exclusively owns " +
                "component shutdown; move cleanup to stopGracefully()/forceStop()."
        }
        check(!SpringLifecycle::class.java.isAssignableFrom(beanType)) {
            "Runtime-owned bean '$beanName' implements Spring Lifecycle. WowRuntime exclusively owns " +
                "component start/stop; remove the Spring Lifecycle/SmartLifecycle contract."
        }
        val preDestroyMethods = beanType.findPreDestroyMethods()
        check(preDestroyMethods.isEmpty()) {
            "Runtime-owned bean '$beanName' declares @PreDestroy methods $preDestroyMethods. " +
                "WowRuntime exclusively owns component shutdown; move cleanup to stopGracefully()/forceStop()."
        }
    }

    private fun Lifecycle.toRuntimeComponent(beanName: String): RuntimeComponent {
        if (this is RuntimeComponent) {
            return this
        }
        check(this is MessageDispatcher) {
            "Runtime-owned bean '$beanName' resolves to '${javaClass.name}', which is neither " +
                "RuntimeComponent nor a legacy MessageDispatcher."
        }
        val forceStoppable = this as? ForceStoppable
        checkNotNull(forceStoppable) {
            "Legacy MessageDispatcher bean '$beanName' must implement ForceStoppable before it " +
                "can be adapted to RuntimeComponent. Implement RuntimeComponent for full runtime " +
                "readiness and ownership semantics."
        }
        val preparable = this as? RuntimePreparable
        return RuntimeLifecycleAdapter(
            delegate = this,
            forceStopAction = forceStoppable::forceStop,
            prepareAction = { runtimeContext ->
                preparable?.prepare(runtimeContext)
            },
        )
    }

    @Suppress("TooGenericExceptionCaught")
    private fun resolveStableRuntimeTarget(beanName: String, component: Lifecycle): Lifecycle {
        val visited = IdentityHashMap<Any, Unit>()
        var candidate: Any = component
        while (AopUtils.isAopProxy(candidate)) {
            check(visited.put(candidate, Unit) == null) {
                "Runtime-owned bean '$beanName' contains a cyclic AOP target chain. WowRuntime " +
                    "cannot establish one stable lifecycle target."
            }
            check(candidate is Advised) {
                "Runtime-owned bean '$beanName' uses an opaque AOP proxy. WowRuntime must inspect " +
                    "the TargetSource to guarantee one stable lifecycle target."
            }
            val targetSource = candidate.targetSource
            check(targetSource.isStatic) {
                "Runtime-owned bean '$beanName' uses a non-static TargetSource. WowRuntime " +
                    "components must resolve to one stable singleton lifecycle target."
            }
            val target = try {
                targetSource.target
            } catch (error: Exception) {
                throw IllegalStateException(
                    "Runtime-owned bean '$beanName' has a static TargetSource that could not be resolved. " +
                        "WowRuntime cannot establish one stable lifecycle target.",
                    error,
                )
            }
            candidate = checkNotNull(target) {
                "Runtime-owned bean '$beanName' has a static TargetSource that resolved to null. " +
                    "WowRuntime cannot establish one stable lifecycle target."
            }
        }
        check(visited.put(candidate, Unit) == null) {
            "Runtime-owned bean '$beanName' contains a cyclic AOP target chain. WowRuntime " +
                "cannot establish one stable lifecycle target."
        }
        check(candidate is Lifecycle) {
            "Runtime-owned bean '$beanName' resolves to a target that does not implement Lifecycle: " +
                candidate.javaClass.name
        }
        return candidate
    }

    private fun isMember(beanName: String): Boolean =
        synchronized(monitor) {
            beanName in memberNames
        }

    private fun DefaultListableBeanFactory.declaredRuntimeComponentNames(): Set<String> =
        linkedSetOf<String>().apply {
            addAll(getBeanNamesForType(MessageDispatcher::class.java, true, false))
            addAll(getBeanNamesForType(WowRuntimeComponent::class.java, true, false))
        }.filterNotTo(linkedSetOf()) { beanName ->
            isHiddenScopedTarget(beanName)
        }

    private fun DefaultListableBeanFactory.validateRuntimeComponentScope(beanName: String) {
        check(scopedProxyTargetName(beanName) == null) {
            "Runtime-owned bean '$beanName' is a scoped proxy. WowRuntime components must be " +
                "singleton beans so that one lifecycle owner controls one stable instance."
        }
        check(isSingleton(beanName)) {
            "Runtime-owned bean '$beanName' is not a singleton. WowRuntime components must be " +
                "singleton beans so that one lifecycle owner controls one stable instance."
        }
    }

    private fun DefaultListableBeanFactory.isHiddenScopedTarget(beanName: String): Boolean =
        ScopedProxyUtils.isScopedTarget(beanName) &&
            scopedProxyTargetName(ScopedProxyUtils.getOriginalBeanName(beanName)) == beanName

    private fun DefaultListableBeanFactory.scopedProxyTargetName(beanName: String): String? {
        if (!containsBeanDefinition(beanName)) {
            return null
        }
        val beanDefinition = getBeanDefinition(beanName)
        if (beanDefinition.beanClassName != ScopedProxyFactoryBean::class.java.name) {
            return null
        }
        return beanDefinition.propertyValues
            .getPropertyValue("targetBeanName")
            ?.value as? String
    }

    private fun DefaultListableBeanFactory.isFactoryDefinition(beanName: String): Boolean =
        getType(BeanFactory.FACTORY_BEAN_PREFIX + beanName, false)
            ?.let(FactoryBean::class.java::isAssignableFrom)
            ?: false

    private fun DefaultListableBeanFactory.ownershipBeanDefinitions(
        beanName: String,
    ): List<BeanDefinition> {
        val original = getBeanDefinition(beanName)
        val merged = getMergedBeanDefinition(beanName)
        return if (merged === original) {
            listOf(original)
        } else {
            listOf(original, merged)
        }
    }

    private fun DefaultListableBeanFactory.declaredBeanTypeName(beanName: String): String {
        if (!containsBeanDefinition(beanName)) {
            return "<manually registered singleton>"
        }
        val beanDefinition = getMergedBeanDefinition(beanName)
        return beanDefinition.resolvableType.resolve()?.name
            ?: getType(beanName, false)?.name
            ?: "<unknown>"
    }

    private fun BeanDefinition.destroyMethods(): List<String> =
        if (this is AbstractBeanDefinition) {
            destroyMethodNames?.toList().orEmpty()
        } else {
            listOfNotNull(destroyMethodName)
        }

    private companion object {
        const val OWNERSHIP_PROCESSED_ATTRIBUTE = "wow.runtimeOwnershipProcessed"
        const val JAKARTA_PRE_DESTROY = "jakarta.annotation.PreDestroy"
        const val JAVAX_PRE_DESTROY = "javax.annotation.PreDestroy"
        val FALLBACK_SHUTDOWN_TIMEOUT: Duration = Duration.ofSeconds(1)

        fun Class<*>.isSpringRuntimeCandidate(): Boolean =
            MessageDispatcher::class.java.isAssignableFrom(this) ||
                WowRuntimeComponent::class.java.isAssignableFrom(this)

        fun Class<*>.isRuntimeOwnershipEligible(): Boolean =
            RuntimeComponent::class.java.isAssignableFrom(this) ||
                (
                    MessageDispatcher::class.java.isAssignableFrom(this) &&
                        ForceStoppable::class.java.isAssignableFrom(this)
                    )

        fun Class<*>.findPreDestroyMethods(): List<String> {
            val preDestroyMethods = linkedSetOf<String>()
            ReflectionUtils.doWithMethods(this) { method ->
                val hasPreDestroy = method.declaredAnnotations.any { annotation ->
                    annotation.annotationClass.java.name == JAKARTA_PRE_DESTROY ||
                        annotation.annotationClass.java.name == JAVAX_PRE_DESTROY
                }
                if (hasPreDestroy) {
                    preDestroyMethods += method.toGenericString()
                }
            }
            return preDestroyMethods.toList()
        }
    }

    private enum class State {
        NEW,
        COLLECTING,
        RESOLVING,
        FROZEN,
        FAILED,
    }
}

internal data class RuntimeComponentDescriptor(
    val beanName: String,
    val lifecycleTarget: Lifecycle,
    val runtimeComponent: RuntimeComponent,
    val order: Int,
)

private data class RuntimeFallbackCleanup(
    val runtime: WowRuntime?,
    val components: List<RuntimeComponent>,
)
