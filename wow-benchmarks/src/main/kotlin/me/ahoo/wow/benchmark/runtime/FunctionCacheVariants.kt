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

package me.ahoo.wow.benchmark.runtime

/**
 * Benchmark-only contract for comparing function-cache storage policies.
 */
interface FunctionCacheVariant<F : Any> {
    fun get(functionType: Class<*>, resolver: (Class<*>) -> F?): F?

    val cachedFunctionCount: Int
}

/**
 * Exact copy of the pre-change production baseline: allocate a HashMap on the first success.
 */
class MapFirstFunctionCache<F : Any> : FunctionCacheVariant<F> {
    private var cache: MutableMap<Class<*>, F>? = null

    override fun get(functionType: Class<*>, resolver: (Class<*>) -> F?): F? {
        cache?.get(functionType)?.let {
            return it
        }
        val function = resolver(functionType) ?: return null
        val currentCache = cache ?: HashMap<Class<*>, F>(1).also {
            cache = it
        }
        currentCache[functionType] = function
        return function
    }

    override val cachedFunctionCount: Int
        get() = cache?.size ?: 0
}

private data class SingleFunctionEntry<F : Any>(
    val functionType: Class<*>,
    val function: F
)

/**
 * Benchmark candidate that stores one immutable entry and promotes on a second successful key.
 *
 * An immutable entry keeps key/value publication coherent even if a caller violates the
 * aggregate-confinement assumption. The promoted-map field remains separate so multi-key hits use
 * the same direct map path as the production baseline. Like the baseline, this class is not thread-safe.
 */
class SingleEntryFunctionCache<F : Any> : FunctionCacheVariant<F> {
    private var singleEntry: SingleFunctionEntry<F>? = null
    private var cache: MutableMap<Class<*>, F>? = null

    override fun get(functionType: Class<*>, resolver: (Class<*>) -> F?): F? {
        val currentCache = cache
        if (currentCache != null) {
            currentCache[functionType]?.let {
                return it
            }
            val function = resolver(functionType) ?: return null
            currentCache[functionType] = function
            return function
        }
        val entry = singleEntry
        if (entry != null) {
            if (entry.functionType === functionType) {
                return entry.function
            }
            val function = resolver(functionType) ?: return null
            val promoted = HashMap<Class<*>, F>(4)
            promoted[entry.functionType] = entry.function
            promoted[functionType] = function
            cache = promoted
            singleEntry = null
            return function
        }

        val function = resolver(functionType) ?: return null
        singleEntry = SingleFunctionEntry(functionType, function)
        return function
    }

    override val cachedFunctionCount: Int
        get() = cache?.size ?: if (singleEntry == null) 0 else 1

    val promoted: Boolean
        get() = cache != null
}
