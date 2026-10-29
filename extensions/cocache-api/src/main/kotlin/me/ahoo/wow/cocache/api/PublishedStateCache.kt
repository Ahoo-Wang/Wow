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

package me.ahoo.wow.cocache.api

/**
 * The shared cache of another context's aggregate states, each read as its generated view [V] (the `<State>View` of
 * the context's api module): what a Saga, a name lookup or an internal computation reads instead of querying the
 * context on every call.
 *
 * - **Kept by the context that owns the aggregate.** The owner writes each state into the cache after its own
 *   commit, in version order, so the cache follows the aggregate; a deleted aggregate reads as `null`. A state the
 *   cache does not hold is read from the owner (its typed query, in process or over HTTP as this service) and cached,
 *   an absent one too, for a while.
 * - **Declared by no one.** A service injects `PublishedStateCache<OrderStateView>`: everything else (the cache's name,
 *   its keys, the view's JSON) follows from the view, on the owner and on every reader alike.
 * - **When it cannot serve.** A state the cache does not hold, whose owner cannot be read now, is a
 *   [PublishedStateUnavailableException]. A read for the reader's own use (a name, a description) may fall back on it; a
 *   read a decision rests on must not, and fails as the owner's read fails.
 * - **A trusted read.** The cache holds the full view as this service's identity sees it, for every tenant and owner:
 *   never return it to an end user as it is, nor decide by it what the owner alone decides. A command's decision rests
 *   on its own aggregate's state; a check that must be consistent is the owner's, which rejects the command.
 */
interface PublishedStateCache<V : Any> {
    /** The cache's name, as the cache metrics and the cache settings name it. */
    val name: String

    /**
     * The state of the aggregate [key] names, `null` when it does not exist or is deleted.
     *
     * @throws PublishedStateUnavailableException when the cache does not hold it and its owner cannot be read now, or
     *   the cache's backend fails: a read that may fall back catches exactly this.
     */
    suspend fun get(key: StateKey): V?

    /**
     * The state of the aggregate [id] of [tenantId], `null` when it does not exist or is deleted.
     *
     * @throws PublishedStateUnavailableException as [get] of a key does.
     */
    suspend fun get(tenantId: String, id: String): V? = get(StateKey(tenantId, id))

    /**
     * The states of the aggregates [keys] name, those that exist: one read of the cache, one of the owner.
     *
     * @throws PublishedStateUnavailableException when the cache does not hold some of them and their owner cannot be
     *   read now, or the cache's backend fails.
     */
    suspend fun getAll(keys: Collection<StateKey>): Map<StateKey, V>
}
