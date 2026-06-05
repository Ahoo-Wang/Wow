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

class ContainerImagesTest {

    @Test
    fun `should expose container images`() {
        ContainerImages.MONGO.assert().isEqualTo("mongo:6.0.6")
        ContainerImages.KAFKA.assert().isEqualTo("confluentinc/cp-kafka:7.4.0")
        ContainerImages.ELASTICSEARCH_REPOSITORY.assert().isEqualTo("docker.elastic.co/elasticsearch/elasticsearch")
        ContainerImages.ELASTICSEARCH_TAG.assert().isEqualTo("9.2.6")
        ContainerImages.REDIS.assert().isEqualTo("redis:7.4-alpine")
        ContainerImages.MARIADB.assert().isEqualTo("mariadb:10.6.4")
    }
}
