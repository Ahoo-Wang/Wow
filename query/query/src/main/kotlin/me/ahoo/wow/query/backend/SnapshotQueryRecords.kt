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

@file:JvmName("SnapshotQueryRecords")

package me.ahoo.wow.query.backend

import me.ahoo.wow.api.read.Snapshot
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.json.query.toRecord
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import tools.jackson.databind.node.ObjectNode

/**
 * This snapshot as the record a snapshot query reads and returns ([toRecord]): the state parsed as JSON. Every
 * snapshot backend returns records of this shape, whatever its storage keeps.
 */
internal fun SnapshotRecord.toQueryRecord(): ObjectNode = Snapshot.of(
    ref = ref,
    version = version,
    scope = scope,
    tags = tags,
    deleted = deleted,
    operator = operator,
    firstEventTime = firstEventTime,
    lastEventTime = lastEventTime,
    state = WowJson.mapper.readTree(state),
    firstOperator = firstOperator,
    schedules = schedules,
).toRecord()
