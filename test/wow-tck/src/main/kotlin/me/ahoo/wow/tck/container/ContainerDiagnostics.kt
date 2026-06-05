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
            append(", image=").append(readField { container.dockerImageName })
            append(", running=").append(readField { container.isRunning })
            append(", host=").append(readField { container.host })
            append(", ports=").append(describePorts(container))
            readField { container.containerId }
                .takeIf { it.isNotBlank() && it != UNAVAILABLE }
                ?.let {
                    append(", id=").append(it)
                }
        }
    }

    fun printFailure(name: String, container: GenericContainer<*>, cause: Throwable) {
        val diagnostics = runCatching {
            describe(name, container)
        }.getOrElse {
            "name=$name, diagnostics=$UNAVAILABLE"
        }
        System.err.println(
            "Container fixture failed: $diagnostics, failure=${cause::class.qualifiedName}: ${cause.message}",
        )
    }

    private fun describePorts(container: GenericContainer<*>): String {
        val exposedPorts = runCatching {
            container.exposedPorts
        }.getOrElse {
            return UNAVAILABLE
        }
        return if (exposedPorts.isEmpty()) {
            "[]"
        } else {
            exposedPorts.joinToString(prefix = "[", postfix = "]") { exposedPort ->
                val mappedPort = runCatching {
                    container.getMappedPort(exposedPort).toString()
                }.getOrElse {
                    "unmapped"
                }
                "$exposedPort->$mappedPort"
            }
        }
    }

    private fun readField(read: () -> Any?): String {
        return runCatching {
            read()?.toString().orEmpty()
        }.getOrElse {
            UNAVAILABLE
        }
    }

    private const val UNAVAILABLE = "unavailable"
}
