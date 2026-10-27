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

package me.ahoo.wow.query.error

import me.ahoo.wow.spi.StorageUnavailableException
import me.ahoo.wow.spi.subscription.FailureClass
import me.ahoo.wow.spi.subscription.FailureClassifier
import java.util.ServiceLoader

/**
 * Tells a store that cannot be reached from a defect, wherever a backend wrapped the failure: a cause that is a
 * [StorageUnavailableException], or that a backend's [FailureClassifier] on the class path calls
 * [FailureClass.SYSTEMIC] (a connection refused or timed out, a server that cannot take the call now).
 */
internal object StoreUnavailability {
    private const val MAX_CAUSES = 16

    private val classifiers: List<FailureClassifier> by lazy {
        ServiceLoader.load(FailureClassifier::class.java, FailureClassifier::class.java.classLoader).toList()
    }

    /** Whether [failure], or one of its causes, says the store cannot be reached now. */
    fun of(failure: Throwable): Boolean {
        val seen = HashSet<Throwable>()
        return generateSequence(failure) { it.cause }.takeWhile { seen.add(it) }.take(MAX_CAUSES).any { cause ->
            cause is StorageUnavailableException ||
                classifiers.any { it.classify(cause) == FailureClass.SYSTEMIC }
        }
    }
}
