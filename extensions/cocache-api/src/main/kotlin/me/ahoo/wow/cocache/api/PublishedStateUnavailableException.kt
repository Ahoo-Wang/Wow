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
 * A [PublishedStateCache] cannot serve the states of [keys] now: the cache does not hold them and their owner cannot be
 * read (it is unreachable, the node has no route to it, or the read timed out), or the cache's backend fails. It may
 * serve them later; the [cause] says what failed.
 *
 * Only an unavailable source is this exception: a state that does not decode as its view, or an error in the code
 * that reads it, is thrown as it is, so a reader that falls back on this exception never hides a defect.
 */
class PublishedStateUnavailableException(
    /** The keys of the states the read asked for. */
    val keys: Collection<StateKey>,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {
    /** The key of the state a single read asked for; null for a read of several. */
    val key: StateKey? get() = keys.singleOrNull()
}
