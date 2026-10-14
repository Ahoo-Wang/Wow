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

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.api.annotation.InternalWowApi

/**
 * A topic one of a node's contexts owns (decision D168): its name, the [partitions] it is created with, whether a
 * topic of another count is refused ([exact], a command topic), and what it is for, as a failed check says.
 */
@InternalWowApi
data class TopicRequirement(val topic: String, val partitions: Int, val exact: Boolean, val purpose: String) {
    init {
        require(partitions >= 1) { "A topic has at least one partition." }
    }
}

/** Validates or creates a node's topics at its start, as [SourceTopics.ddl] says (decision D168). */
internal object NodeTopics {
    private val logger = KotlinLogging.logger {}

    /**
     * Prepares [requirements] in [topics]: under [TopicDdl.AUTO], creates the missing ones, then, under it or
     * [TopicDdl.VALIDATE], fails unless each exists, with exactly its partitions when [TopicRequirement.exact], saying
     * every topic that is not as [node] needs it. Under [TopicDdl.NONE], does nothing.
     */
    suspend fun prepare(node: String, topics: SourceTopics, requirements: List<TopicRequirement>) {
        if (topics.ddl == TopicDdl.NONE || requirements.isEmpty()) {
            return
        }
        val admin = checkNotNull(topics.admin) { "Topics are validated or created through an admin." }
        val names = requirements.mapTo(LinkedHashSet()) { it.topic }
        var found = admin.partitions(names)
        if (topics.ddl == TopicDdl.AUTO) {
            val missing = requirements.filter { it.topic !in found }
            if (missing.isNotEmpty()) {
                admin.create(missing.associate { it.topic to it.partitions })
                logger.info {
                    "Node $node created the topics ${missing.joinToString { "${it.topic} (${it.partitions} partitions)" }}."
                }
                found = admin.partitions(names)
            }
        }
        val problems = requirements.mapNotNull { requirement ->
            val count = found[requirement.topic]
            when {
                count == null -> "${requirement.topic} does not exist (${requirement.purpose})"
                requirement.exact && count != requirement.partitions ->
                    "${requirement.topic} has $count partitions, not ${requirement.partitions} (${requirement.purpose})"

                else -> null
            }
        }
        check(problems.isEmpty()) {
            "The topics of node $node are not as it needs them (topic DDL ${topics.ddl.name.lowercase()}, decision " +
                "D168): ${problems.joinToString("; ")}. " +
                if (topics.ddl == TopicDdl.AUTO) {
                    "A topic that exists is never altered: give it the partitions it needs by hand."
                } else {
                    "Create them so, or let the node create the missing ones (with Spring Boot, " +
                        "wow.kafka.topics.ddl=auto)."
                }
        }
    }
}
