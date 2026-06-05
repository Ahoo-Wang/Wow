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

import org.testcontainers.containers.GenericContainer

object ContainerDiagnostics {
    fun describe(name: String, container: GenericContainer<*>): String {
        return buildString {
            append("name=").append(name)
            append(", image=").append(container.dockerImageName)
            append(", running=").append(container.isRunning)
            append(", host=").append(container.host)
            append(", ports=").append(describePorts(container))
            container.containerId?.let {
                append(", id=").append(it)
            }
        }
    }

    fun printFailure(name: String, container: GenericContainer<*>, cause: Throwable) {
        System.err.println("${describe(name, container)}, failure=${cause::class.qualifiedName}: ${cause.message}")
    }

    private fun describePorts(container: GenericContainer<*>): String {
        return runCatching {
            val exposedPorts = container.exposedPorts
            if (exposedPorts.isEmpty()) {
                "[]"
            } else {
                exposedPorts.joinToString(prefix = "[", postfix = "]") { exposedPort ->
                    "$exposedPort->${container.getMappedPort(exposedPort)}"
                }
            }
        }.getOrElse {
            "unmapped"
        }
    }
}
