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

package me.ahoo.wow.tck.container

import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.TestWatcher

class MariaDbTestFixture(
    private val databaseName: String = "wow_db",
) : BeforeEachCallback, TestWatcher {
    val host: String
        get() = WowTestContainers.mariaDb.host

    val port: Int
        get() = WowTestContainers.mariaDb.getMappedPort(3306)

    val jdbcUrl: String
        get() = WowTestContainers.mariaDb.jdbcUrl

    override fun beforeEach(context: ExtensionContext) {
        WowTestContainers.mariaDb.isRunning
    }

    fun r2dbcUrl(poolSize: Int = 32): String {
        return "r2dbc:pool:mariadb:sequential://root:root@$host:$port/$databaseName" +
            "?initialSize=$poolSize&maxSize=$poolSize&acquireRetry=3&maxLifeTime=PT30M"
    }

    override fun testFailed(context: ExtensionContext, cause: Throwable?) {
        ContainerDiagnostics.printFailure("mariadb", WowTestContainers.mariaDb, cause)
    }
}
