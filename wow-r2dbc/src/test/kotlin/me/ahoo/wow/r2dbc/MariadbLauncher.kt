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

package me.ahoo.wow.r2dbc

import org.testcontainers.containers.MariaDBContainer

object MariadbLauncher {
    private const val DEV_HOST = "localhost"
    private val CONTAINER = MariaDBContainer("mariadb:10.6.4")
        .withNetworkAliases("mariadb")
        .withPassword("root")
        .withDatabaseName("wow_db")
        .withInitScript("init-schema-mysql.sql")
        .withReuse(true)
    private val inCI = System.getenv("CI").isNullOrBlank().not()

    init {
        if (inCI) {
            CONTAINER.start()
        }
    }

    fun getHost(): String {
        if (inCI) {
            return CONTAINER.host
        }
        return DEV_HOST
    }

    fun getPort(): Int {
        val innerPort = 3306
        if (inCI) {
            return CONTAINER.getMappedPort(innerPort)
        }
        return innerPort
    }
}
