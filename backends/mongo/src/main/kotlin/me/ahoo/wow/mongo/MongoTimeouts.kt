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

import com.mongodb.client.MongoCollection
import com.mongodb.client.MongoDatabase
import me.ahoo.wow.api.annotation.InternalWowApi
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * How long a store's calls to MongoDB may take (the driver's client-side operation timeout, `timeoutMS`): without
 * one, a reply lost on the way (a failover, a proxy) blocks its caller forever, the driver's socket reads having no
 * timeout. Every call of the log and the stores has [OPERATION] unless its store sets another
 * (`wow.mongo.operation-timeout`); an append has its own, shorter (`wow.mongo.append-timeout`); the
 * schema's commands (format records, indexes, sharding) have [DDL], as an index built over a large collection may
 * take minutes. A call that times out fails as transient ([MongoFailures]): a write's outcome is then unknown. The
 * queries keep their own budget (`maxTimeMS`).
 */
@InternalWowApi
object MongoTimeouts {
    /** The default timeout of a store's calls besides appends and the schema's. */
    val OPERATION: Duration = 30.seconds

    /** The timeout of the schema's commands. */
    val DDL: Duration = 5.minutes

    /** [database] for the schema's commands. */
    internal fun ddl(database: MongoDatabase): MongoDatabase =
        database.withTimeout(DDL.inWholeMilliseconds, TimeUnit.MILLISECONDS)

    /** [collection] for the schema's commands. */
    internal fun <T : Any> ddl(collection: MongoCollection<T>): MongoCollection<T> =
        collection.withTimeout(DDL.inWholeMilliseconds, TimeUnit.MILLISECONDS)
}
