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

package me.ahoo.wow.exception

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.api.exception.RecoverableType
import java.util.ServiceLoader
import java.util.concurrent.ConcurrentHashMap

/**
 * A contributor of recoverable exception classifications.
 *
 * Contributions come from Java's [ServiceLoader] (`META-INF/services`), and, in a Spring application, from
 * `RecoverableExceptionProvider` beans. Each one registers its exception-to-[RecoverableType] mappings into the
 * [RecoverableExceptionRegistrar] it is given.
 */
interface RecoverableExceptionProvider {
    fun register(registrar: RecoverableExceptionRegistrar)
}

/**
 * Where [RecoverableExceptionProvider]s register their classifications. The registry is a [RecoverableExceptionRegistry].
 */
interface RecoverableExceptionRegistrar {
    /**
     * Registers (or overwrites) the [RecoverableType] for the given exception class.
     *
     * @param throwableClass the exception class to classify
     * @param recoverableType the recoverability classification
     */
    fun register(
        throwableClass: Class<out Throwable>,
        recoverableType: RecoverableType
    )

    /**
     * Removes the registration for the given exception class.
     *
     * @param throwableClass the exception class to unregister
     */
    fun unregister(throwableClass: Class<out Throwable>)

    // compat(wow<9.3): the 9.2 static registrar calls, kept for one deprecation cycle; see docs/compat-debt.md.
    /**
     * The 9.2 static entry points: before 9.3.0 `RecoverableExceptionRegistrar` was the process's registry object.
     * They delegate to [RecoverableExceptionRegistry.DEFAULT], the registry [Class.recoverable] reads.
     */
    companion object {
        private const val DEPRECATION = "Scheduled for removal in 10.0.0. Use RecoverableExceptionRegistry.DEFAULT, " +
            "or register a RecoverableExceptionProvider."

        @Deprecated(
            DEPRECATION,
            ReplaceWith("RecoverableExceptionRegistry.DEFAULT.register(throwableClass, recoverableType)")
        )
        fun register(throwableClass: Class<out Throwable>, recoverableType: RecoverableType) =
            RecoverableExceptionRegistry.DEFAULT.register(throwableClass, recoverableType)

        @Deprecated(DEPRECATION, ReplaceWith("RecoverableExceptionRegistry.DEFAULT.unregister(throwableClass)"))
        fun unregister(throwableClass: Class<out Throwable>) =
            RecoverableExceptionRegistry.DEFAULT.unregister(throwableClass)

        @Deprecated(DEPRECATION, ReplaceWith("RecoverableExceptionRegistry.DEFAULT.getRecoverableType(throwableClass)"))
        fun getRecoverableType(throwableClass: Class<out Throwable>): RecoverableType? =
            RecoverableExceptionRegistry.DEFAULT.getRecoverableType(throwableClass)
    }
}

/**
 * Maps exception classes to their [RecoverableType], for [Class.recoverable].
 *
 * Explicit registrations take precedence over the default rules (the [RecoverableException] marker interface,
 * [java.util.concurrent.TimeoutException]). The backing store is a [ConcurrentHashMap], so registration and lookup
 * are thread-safe.
 *
 * The classification is one per process, [DEFAULT]: it is seeded with the [ServiceLoader] contributions; the Spring
 * starter exposes it as the `recoverableExceptionRegistry` bean and registers the `RecoverableExceptionProvider` beans
 * into it. A separate instance classifies nothing [Class.recoverable] reads; it is for tests and tools.
 *
 * @see RecoverableExceptionProvider
 * @see RecoverableType
 * @see Class.recoverable
 */
class RecoverableExceptionRegistry : RecoverableExceptionRegistrar {
    companion object {
        private val log = KotlinLogging.logger {}

        /** The process's classification, seeded with the [ServiceLoader] contributions. */
        val DEFAULT: RecoverableExceptionRegistry by lazy {
            RecoverableExceptionRegistry().apply {
                ServiceLoader.load(RecoverableExceptionProvider::class.java).forEach { register(it) }
            }
        }
    }

    private val registry = ConcurrentHashMap<Class<out Throwable>, RecoverableType>()

    /** Lets [provider] register its classifications. */
    fun register(provider: RecoverableExceptionProvider) {
        provider.register(this)
    }

    override fun register(
        throwableClass: Class<out Throwable>,
        recoverableType: RecoverableType
    ) {
        val previous = registry.put(throwableClass, recoverableType)
        log.info {
            "Register - throwableClass:[$throwableClass] - previous:[$previous],current:[$recoverableType]."
        }
    }

    override fun unregister(throwableClass: Class<out Throwable>) {
        val removed = registry.remove(throwableClass)
        log.info {
            "Unregister - throwableClass:[$throwableClass] - removed:[$removed]."
        }
    }

    /**
     * Returns the registered [RecoverableType] for the given exception class, or `null` if no registration exists.
     *
     * Lookup walks the class hierarchy: if the exact class is not registered, each superclass is checked in order, so
     * subclasses of a registered exception are classified without registering each of them.
     */
    fun getRecoverableType(throwableClass: Class<out Throwable>): RecoverableType? {
        registry[throwableClass]?.let { return it }
        var superclass = throwableClass.superclass
        while (superclass != null && Throwable::class.java.isAssignableFrom(superclass)) {
            registry[superclass]?.let { return it }
            superclass = superclass.superclass
        }
        return null
    }
}
