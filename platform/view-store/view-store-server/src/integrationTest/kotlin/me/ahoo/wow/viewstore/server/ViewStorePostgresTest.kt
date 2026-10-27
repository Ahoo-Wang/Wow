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

package me.ahoo.wow.viewstore.server

import me.ahoo.wow.tck.containers.WowContainers

/**
 * The view store's contract on PostgreSQL (roadmap 7.2a, decision D196): the views' log and snapshots in a container,
 * and their snapshot queries served there.
 */
class ViewStorePostgresTest : ViewStoreContract() {
    private val postgres = WowContainers.postgres

    override val store: ViewStoreNode = startViewStore(
        "spring.datasource.url" to postgres.jdbcUrl,
        "spring.datasource.username" to postgres.username,
        "spring.datasource.password" to postgres.password,
        "wow.backend" to "postgres",
        "wow.log.partitions" to 4,
        *SYSTEM_VIEWS,
        profiles = listOf("postgres", "dev"),
    )
}
