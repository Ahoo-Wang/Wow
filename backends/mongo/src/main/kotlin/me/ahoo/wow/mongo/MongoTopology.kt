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

package me.ahoo.wow.mongo

import com.mongodb.client.MongoClient
import org.bson.Document
import org.slf4j.LoggerFactory

/**
 * What the server a store talks to is: a member of a replica set, a mongos of a sharded cluster, or a standalone
 * mongod. A log appends and reads with write and read concern `majority`, which only a replica set (each shard of a
 * cluster is one) keeps through a failover; on a standalone server a write acknowledged is lost with its disk.
 *
 * A replica set with an arbiter keeps a majority for elections but not for writes once a data-bearing member is down
 * (#6675): a store warns of it once at start ([warnOfArbiters]). Only a replica set the client connects to directly
 * lists its arbiters; a mongos does not show its shards' members, so a sharded cluster gets the documentation only.
 */
internal object MongoTopology {
    /** What the server [client] connects to says of itself (`hello`). */
    fun hello(client: MongoClient): Document =
        MongoTimeouts.ddl(client.getDatabase(ADMIN)).runCommand(Document("hello", 1))

    /** Whether the answer [hello] gave is a standalone mongod's: no replica set name, and not a mongos. */
    fun standalone(hello: Document): Boolean = hello.getString(SET_NAME) == null && hello.getString(MSG) != MONGOS

    /**
     * Fails unless the server that answered [hello] is a member of a replica set or a mongos of a sharded cluster;
     * [key] names the store's settings.
     */
    fun requireReplicated(hello: Document, key: String) {
        check(!standalone(hello)) {
            "MongoDB needs a replica set or a sharded cluster: the log writes with majority. The client of $key " +
                "connects to a standalone mongod; run it as a replica set (a single member is one), or run the node " +
                "for development."
        }
    }

    /** The arbiters of the replica set whose member answered [hello]; none from a mongos or a standalone mongod. */
    fun arbiters(hello: Document): List<String> = hello.getList(ARBITERS, String::class.java).orEmpty()

    /** What [warnOfArbiters] logs for [arbiters] of the replica set the client of [key] connects to; null for none. */
    fun arbiterWarning(arbiters: List<String>, key: String): String? = arbiters.takeIf { it.isNotEmpty() }?.let {
        "The client of $key connects to a replica set with an arbiter (${it.joinToString()}): while one of its " +
            "data-bearing members is down, MongoDB cannot acknowledge a majority write, so the log's appends and the " +
            "lease writes wait until they time out, the nodes stop their partitions, and the subscriptions' tails " +
            "stall. Replace the arbiter with a third data-bearing member, or bring a member that goes down back at " +
            "once."
    }

    /** Logs [arbiterWarning] for the replica set whose member answered [hello], once per call: a store calls it once. */
    fun warnOfArbiters(hello: Document, key: String) {
        arbiterWarning(arbiters(hello), key)?.let { log.warn(it) }
    }

    private val log = LoggerFactory.getLogger(MongoTopology::class.java)

    private const val ADMIN = "admin"
    private const val SET_NAME = "setName"
    private const val ARBITERS = "arbiters"
    private const val MSG = "msg"
    private const val MONGOS = "isdbgrid"
}
