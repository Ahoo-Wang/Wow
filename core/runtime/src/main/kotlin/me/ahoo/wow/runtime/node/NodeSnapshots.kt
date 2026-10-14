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

package me.ahoo.wow.runtime.node

import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.EventCodec
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.runtime.snapshot.SnapshotAssembly
import me.ahoo.wow.runtime.snapshot.SnapshotRebuildModel
import me.ahoo.wow.runtime.snapshot.SnapshotTargets
import me.ahoo.wow.spi.log.EventLog

/** Which aggregates of a node keep snapshots, as its [manifest] says, and what that gives each context (decision D172). */
internal class NodeSnapshots(private val manifest: Manifest) {
    /**
     * Whether the Manifest enables [model]'s snapshots; a built-in job aggregate, not in it, keeps them, and a built-in
     * rebuild aggregate does not (decision D172).
     */
    fun enabled(model: AggregateModel<*>): Boolean = manifest.contexts
        .firstOrNull { it.name == model.type.context }?.aggregates
        ?.firstOrNull { it.name == model.type.name }?.snapshot?.enabled
        ?: !SnapshotRebuildModel.isRebuild(model)

    /**
     * The built-in rebuild aggregate of each context of [opened] whose snapshots are kept, taking the requests of its
     * snapshotted aggregate types among [models].
     */
    fun rebuildModels(
        opened: Map<String, ContextBackends>,
        models: Collection<AggregateModel<*>>,
    ): List<AggregateModel<*>> = opened.mapNotNull { (context, backends) ->
        backends.primarySnapshots ?: return@mapNotNull null
        val snapshotted = models.filter { it.type.context == context && enabled(it) }
        snapshotted.takeIf { it.isNotEmpty() }?.let {
            SnapshotRebuildModel.model(context, it.mapTo(LinkedHashSet()) { model -> model.type.name })
        }
    }

    /** The snapshot parts of [context]: its targets and the [models] whose snapshots it keeps. */
    @Suppress("LongParameterList")
    fun assembly(
        context: String,
        models: List<AggregateModel<*>>,
        backends: ContextBackends,
        kernel: Kernel,
        log: EventLog,
        codec: EventCodec,
    ): SnapshotAssembly {
        val targets = backends.snapshotTargets ?: backends.snapshots?.let { SnapshotTargets.single(it) }
        val snapshotted = if (targets == null) emptyList() else models.filter { enabled(it) }
        return SnapshotAssembly(context, targets, snapshotted, kernel, log, codec)
    }
}
