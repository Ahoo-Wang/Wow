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

package me.ahoo.wow.runtime.source

import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.state.StateEventPayload
import me.ahoo.wow.spi.state.StateEventRecord
import me.ahoo.wow.spi.state.PublishedState
import me.ahoo.wow.spi.transport.EnvelopeCodec
import me.ahoo.wow.spi.transport.TopicProducer

/** The aggregate another context publishes, and its topic. */
val ORDER = AggregateType("sales", "order")
const val ORDER_TOPIC = "wow.sales.order.state"

fun order(id: String): AggregateRef = ORDER.ref("t", id)

/**
 * The record of [id]'s commit at [version], committed at `1000 × version`, with the state `{"total": version}`
 * unless [withState] is false (decision D140).
 */
fun orderRecord(id: String, version: Int, withState: Boolean = true): StateEventRecord = StateEventRecord(
    ref = order(id),
    version = version,
    commandId = "c-$id-$version",
    requestId = "r-$id-$version",
    commandType = "sales.order.Place",
    commitTime = 1_000L * version,
    events = listOf(StateEventPayload("sales.order.Placed", 1, WowJson.mapper.readTree("""{"n":$version}"""))),
    state = if (withState) PublishedState(version, "fp", WowJson.mapper.readTree("""{"total":$version}""")) else null,
)

/** Publishes [records] to [topic], each keyed by its aggregate, as the transport sink does. */
suspend fun TopicProducer.publish(vararg records: StateEventRecord, topic: String = ORDER_TOPIC) {
    send(topic, records.map(EnvelopeCodec::message))
}
