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

/**
 * What the server a store talks to is: a member of a replica set, a mongos of a sharded cluster, or a standalone
 * mongod. A log appends and reads with write and read concern `majority`, which only a replica set (each shard of a
 * cluster is one) keeps through a failover; on a standalone server a write acknowledged is lost with its disk.
 */
internal object MongoTopology {
    /** Whether the answer [hello] gave is a standalone mongod's: no replica set name, and not a mongos. */
    fun standalone(hello: Document): Boolean = hello.getString(SET_NAME) == null && hello.getString(MSG) != MONGOS

    /**
     * Fails unless [client] talks to a replica set or a sharded cluster, or the node runs for [development], which may
     * run a log on a standalone server; [key] names the store's settings.
     */
    fun requireReplicated(client: MongoClient, key: String, development: Boolean) {
        if (development) {
            return
        }
        val hello = MongoTimeouts.ddl(client.getDatabase(ADMIN)).runCommand(Document("hello", 1))
        check(!standalone(hello)) {
            "MongoDB needs a replica set or a sharded cluster: the log writes with majority. The client of $key " +
                "connects to a standalone mongod; run it as a replica set (a single member is one), or run the node " +
                "for development."
        }
    }

    private const val ADMIN = "admin"
    private const val SET_NAME = "setName"
    private const val MSG = "msg"
    private const val MONGOS = "isdbgrid"
}
