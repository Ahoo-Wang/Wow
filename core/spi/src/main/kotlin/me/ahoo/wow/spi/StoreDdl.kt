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

package me.ahoo.wow.spi

/**
 * How a store manages the framework's own tables, collections and indexes (review A5-10): the logs, snapshots,
 * timers, reservations, leases, checkpoints and the failure ledger, with their format records (decision D59). The
 * three modes of `wow.query.index.ddl`, `wow.kafka.topics.ddl` and `wow.clickhouse.sink.ddl`, for the stores.
 */
enum class StoreDdl {
    /** Creates what is missing, then checks the format of what exists: the default, and the only behaviour before. */
    AUTO,

    /**
     * Creates nothing and checks that each structure exists in the format this version reads: for an account without
     * DDL rights, whose schema a migration or an earlier `auto` run created. A missing one refuses the store.
     */
    VALIDATE,

    /** Creates and checks nothing: the schema is someone else's, and a missing structure fails at its first use. */
    NONE,
}
