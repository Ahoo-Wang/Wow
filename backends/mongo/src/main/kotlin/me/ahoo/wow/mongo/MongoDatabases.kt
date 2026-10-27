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

import com.mongodb.WriteConcern
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoDatabase
import me.ahoo.wow.api.annotation.InternalWowApi
import java.util.concurrent.TimeUnit
import kotlin.time.Duration

/**
 * The database handle every store of Wow reads and writes through: each write acknowledged by a majority of the
 * replica set (`w: majority`), set on the handle so that a client whose default is `w: 1` cannot weaken it, and
 * each call bounded by its store's timeout ([MongoTimeouts]). A write the primary acknowledged alone is lost when the
 * primary fails over before it replicates: a reservation (a uniqueness broken), a timer (a command never sent), a
 * checkpoint, a lease, a ledger entry. Snapshots are derivable from the log, but take majority too: one rule for
 * every collection is simpler than an exception that has to stay true.
 */
@InternalWowApi
object MongoDatabases {
    /** [name] on [client] as Wow's stores use it: majority writes, each call bounded by [timeout]. */
    @JvmStatic
    fun of(client: MongoClient, name: String, timeout: Duration = MongoTimeouts.OPERATION): MongoDatabase =
        client.getDatabase(name)
            .withWriteConcern(WriteConcern.MAJORITY)
            .withTimeout(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
}
