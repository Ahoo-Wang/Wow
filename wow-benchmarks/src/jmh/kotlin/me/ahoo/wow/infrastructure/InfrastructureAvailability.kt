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

package me.ahoo.wow.infrastructure

import java.net.InetSocketAddress
import java.net.Socket

object InfrastructureAvailability {
    fun requireRedis() {
        requireService("Redis", "localhost", 6379)
    }

    fun requireMongo() {
        requireService("MongoDB", "localhost", 27017)
    }

    private fun requireService(service: String, host: String, port: Int) {
        val available = runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), 2000)
            }
        }.isSuccess
        require(available) {
            "$service is required for Infrastructure I/O benchmarks at $host:$port. " +
                "Start $service and rerun `./gradlew :wow-benchmarks:benchmarkInfrastructure`."
        }
    }
}
