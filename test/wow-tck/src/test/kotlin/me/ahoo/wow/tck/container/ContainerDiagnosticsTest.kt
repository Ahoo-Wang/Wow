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

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName

class ContainerDiagnosticsTest {

    @Test
    fun `should describe unstarted container without throwing`() {
        val container = GenericContainer(DockerImageName.parse(ContainerImages.REDIS))
            .withExposedPorts(6379)

        val description = ContainerDiagnostics.describe("redis", container)

        description.assert().contains("name=redis")
        description.assert().contains("image=${ContainerImages.REDIS}")
        description.assert().contains("running=false")
        description.assert().contains("host=")
        description.assert().contains("ports=")
    }

    @Test
    fun `should print failure without throwing when container is unstarted`() {
        val container = GenericContainer(DockerImageName.parse(ContainerImages.REDIS))
            .withExposedPorts(6379)

        ContainerDiagnostics.printFailure("redis", container, IllegalStateException("boom"))
    }

    @Test
    fun `should keep diagnostics safe when container fields fail`() {
        val description = ContainerDiagnostics.describe("redis", FailingContainer())

        description.assert().contains("name=redis")
        description.assert().contains("image=unavailable")
        description.assert().contains("running=unavailable")
        description.assert().contains("host=unavailable")
        description.assert().contains("ports=unavailable")
    }

    private class FailingContainer : GenericContainer<FailingContainer>(
        DockerImageName.parse(ContainerImages.REDIS),
    ) {
        override fun getDockerImageName(): String {
            throw IllegalStateException("image unavailable")
        }

        override fun isRunning(): Boolean {
            throw IllegalStateException("running unavailable")
        }

        override fun getHost(): String {
            throw IllegalStateException("host unavailable")
        }

        override fun getExposedPorts(): MutableList<Int> {
            throw IllegalStateException("ports unavailable")
        }

        override fun getContainerId(): String {
            throw IllegalStateException("id unavailable")
        }
    }
}
