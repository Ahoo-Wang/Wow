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
import org.testcontainers.containers.KafkaContainer
import org.testcontainers.containers.MongoDBContainer
import org.testcontainers.elasticsearch.ElasticsearchContainer
import org.testcontainers.utility.DockerImageName
import java.time.Duration

object WowTestContainers {
    const val ELASTIC_USER = "elastic"

    private const val ELASTIC_PASSWORD = "wow"
    private const val ELASTICSEARCH_JAVA_OPTS = "-Xms512m -Xmx512m"

    val mongo: MongoDBContainer by lazy {
        MongoDBContainer(DockerImageName.parse(ContainerImages.MONGO))
            .withNetworkAliases("mongo")
            .also { it.start() }
    }

    val kafka: KafkaContainer by lazy {
        KafkaContainer(DockerImageName.parse(ContainerImages.KAFKA))
            .withNetworkAliases("kafka")
            .withKraft()
            .also { it.start() }
    }

    val elasticsearch: ElasticsearchContainer by lazy {
        ElasticsearchContainer(
            DockerImageName
                .parse(ContainerImages.ELASTICSEARCH_REPOSITORY)
                .withTag(ContainerImages.ELASTICSEARCH_TAG),
        )
            .withPassword(ELASTIC_PASSWORD)
            .withEnv("ES_JAVA_OPTS", ELASTICSEARCH_JAVA_OPTS)
            .withNetworkAliases("elasticsearch")
            .withStartupTimeout(Duration.ofMinutes(5))
            .also { it.start() }
    }

    val redis: GenericContainer<*> by lazy {
        GenericContainer(DockerImageName.parse(ContainerImages.REDIS))
            .withExposedPorts(6379)
            .withNetworkAliases("redis")
            .also { it.start() }
    }

    val elasticPassword: String
        get() = ELASTIC_PASSWORD
}
