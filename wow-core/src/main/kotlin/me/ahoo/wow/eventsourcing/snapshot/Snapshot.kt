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
package me.ahoo.wow.eventsourcing.snapshot

import me.ahoo.wow.api.modeling.SnapshotTimeCapable
import me.ahoo.wow.infra.Decorator
import me.ahoo.wow.modeling.state.ReadOnlyStateAggregate

/**
 * Represents a snapshot of a state aggregate at a specific point in time.
 * Snapshots are used to optimize loading by providing a starting point for event replay.
 *
 * @param S the type of the state
 */
interface Snapshot<S : Any> :
    ReadOnlyStateAggregate<S>,
    SnapshotTimeCapable

/**
 * Simple implementation of Snapshot that wraps a ReadOnlyStateAggregate with a snapshot time.
 *
 * @param delegate the state aggregate being snapshotted
 * @param snapshotTime the time when the snapshot was taken (default: current time)
 */
data class SimpleSnapshot<S : Any>(
    override val delegate: ReadOnlyStateAggregate<S>,
    override val snapshotTime: Long = System.currentTimeMillis()
) : Snapshot<S>,
    ReadOnlyStateAggregate<S> by delegate,
    Decorator<ReadOnlyStateAggregate<S>>
