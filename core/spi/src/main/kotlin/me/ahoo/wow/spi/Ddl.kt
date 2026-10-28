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

import me.ahoo.wow.api.annotation.WowSpi

/**
 * How the framework manages the structures it owns: a store's tables, collections and indexes (the logs, snapshots,
 * timers, reservations, leases, checkpoints and the failure ledger, with their format records), the query
 * indexes the Manifest declares, the Kafka topics and the ClickHouse tables. One setting, `wow.ddl`, overridden by
 * `wow.stores.<store>.ddl`, `wow.kafka.ddl` and `wow.clickhouse.ddl`; each mode means the same everywhere.
 */
enum class Ddl {
    /** Creates what is missing, then checks the format of what exists: the default. */
    AUTO,

    /**
     * Creates nothing and checks that each structure exists in the format this version reads: for an account without
     * DDL rights, whose schema a migration or an earlier `auto` run created. A missing or incompatible one refuses the
     * startup, naming what is missing.
     */
    VALIDATE,

    /** Creates and checks nothing: the schema is someone else's, and a missing structure fails at its first use. */
    NONE,
    ;

    /** Whether this mode creates what is missing: [AUTO]. */
    val creates: Boolean get() = this == AUTO

    /** Whether this mode checks what exists: [AUTO] and [VALIDATE]. */
    val checks: Boolean get() = this != NONE

    /**
     * Makes sure of the structure [describe] names as this mode says, with the backend's native checks: under [AUTO]
     * [create]s it unless [exists] says it is there, and [validate]s it when it is; under [VALIDATE] refuses it,
     * naming it, when it does not exist, and validates it when it does; under [NONE] does nothing. A backend whose
     * [create] is idempotent and checks what it finds leaves [exists] out: it then always creates.
     */
    @WowSpi
    suspend fun apply(
        describe: String,
        exists: suspend () -> Boolean = { false },
        create: suspend () -> Unit,
        validate: suspend () -> Unit,
    ) {
        when (this) {
            AUTO -> if (exists()) validate() else create()
            VALIDATE -> {
                check(exists()) { "$describe does not exist, and the store only validates its schema (ddl validate)." }
                validate()
            }

            NONE -> Unit
        }
    }
}
