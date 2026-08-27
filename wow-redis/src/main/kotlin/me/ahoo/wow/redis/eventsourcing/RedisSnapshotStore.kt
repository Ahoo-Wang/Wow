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

package me.ahoo.wow.redis.eventsourcing

import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.eventsourcing.snapshot.Snapshot
import me.ahoo.wow.eventsourcing.snapshot.SnapshotStore
import me.ahoo.wow.redis.RedisScripts
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.serialization.toJsonNode
import me.ahoo.wow.serialization.toJsonString
import me.ahoo.wow.serialization.toObject
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import reactor.core.publisher.Mono
import tools.jackson.databind.node.ObjectNode

class RedisSnapshotStore(
    private val redisTemplate: ReactiveStringRedisTemplate
) : SnapshotStore {
    companion object {
        const val NAME = "redis"
        private val SCRIPT_SAVE_SNAPSHOT: RedisScript<String> =
            RedisScripts.load("snapshot_save.lua", String::class.java)
    }

    override val name: String
        get() = NAME

    override fun <S : Any> load(aggregateId: AggregateId): Mono<Snapshot<S>> {
        val snapshotKey = SnapshotKeyLayout.key(aggregateId)
        return redisTemplate.opsForValue()
            .get(snapshotKey)
            .map { it.toObject<Snapshot<S>>() }
    }

    override fun <S : Any> save(snapshot: Snapshot<S>): Mono<Void> {
        val snapshotKey = SnapshotKeyLayout.key(snapshot.aggregateId)
        val snapshotNode: ObjectNode = snapshot.toJsonNode()
        val snapshotVersion = snapshotNode.requiredSnapshotVersion()
        return redisTemplate.execute(
            SCRIPT_SAVE_SNAPSHOT,
            listOf(snapshotKey),
            listOf(snapshotVersion.toString(), snapshotNode.toJsonString()),
        ).then()
    }
}

internal fun ObjectNode.requiredSnapshotVersion(): Int {
    val versionNode = checkNotNull(this[MessageRecords.VERSION]) {
        "Serialized Wow snapshot has no version."
    }
    check(versionNode.isInt) {
        "Serialized Wow snapshot version must be an integer."
    }
    return versionNode.asInt()
}
