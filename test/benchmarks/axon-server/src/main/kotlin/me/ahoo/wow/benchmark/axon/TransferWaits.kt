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

package me.ahoo.wow.benchmark.axon

import org.springframework.stereotype.Component
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/**
 * The transfers whose HTTP requests wait for the entry on the target, E3's completion point (Wow's
 * `committed > handled(TransferSaga.onPrepared) > committed`), in this node's memory: the benchmark runs one node.
 */
@Component
class TransferWaits {
    private val waiting = ConcurrentHashMap<String, CompletableFuture<Entered>>()

    /** Waits for transfer [transferId]'s entry; registered before its `Prepare` is sent. */
    fun await(transferId: String): CompletableFuture<Entered> =
        CompletableFuture<Entered>().also { waiting[transferId] = it }

    /** Transfer [transferId]'s entry is committed on [target] at [version]. */
    fun entered(transferId: String, target: String, version: Long?) {
        waiting.remove(transferId)?.complete(Entered(target, version))
    }

    /** Gives up waiting for [transferId] (its `Prepare` failed, or the wait timed out). */
    fun forget(transferId: String) {
        waiting.remove(transferId)
    }

    data class Entered(val target: String, val version: Long?)
}
