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
package me.ahoo.wow.eventsourcing

import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean

internal class RoutingStoreLifecycle(
    stores: Sequence<AutoCloseable>,
) : AutoCloseable {
    private val stores = stores.distinctByIdentity()
    private val closed = AtomicBoolean()

    @Suppress("TooGenericExceptionCaught")
    override fun close() {
        if (!closed.compareAndSet(false, true)) {
            return
        }
        var failure: Throwable? = null
        stores.forEach { store ->
            try {
                store.close()
            } catch (closeFailure: Throwable) {
                if (failure == null) {
                    failure = closeFailure
                } else if (failure !== closeFailure) {
                    failure.addSuppressed(closeFailure)
                }
            }
        }
        failure?.let { throw it }
    }
}

private fun Sequence<AutoCloseable>.distinctByIdentity(): List<AutoCloseable> {
    val identities = Collections.newSetFromMap(IdentityHashMap<AutoCloseable, Boolean>())
    return filter(identities::add).toList()
}
