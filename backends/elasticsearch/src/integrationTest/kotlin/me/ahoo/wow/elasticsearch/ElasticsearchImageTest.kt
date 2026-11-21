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

package me.ahoo.wow.elasticsearch

import co.elastic.clients.transport.Version
import me.ahoo.test.asserts.assert
import me.ahoo.wow.tck.containers.WowContainers
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Holds the Elasticsearch server the tests run to the Java client on the classpath: the client is supported only
 * against a server of its own or a later minor (elasticsearch-java's compatibility rule), and no bot moves the images,
 * so a client the Spring Boot BOM moves to a new minor fails here until the images follow. The client's version is
 * [Version.VERSION], which the client reads from its own jar (`co/elastic/clients/version.properties`). It starts no
 * container: the images are compared as text.
 */
class ElasticsearchImageTest {
    @Test
    fun `the test image should be of the client's major and no older a minor`() {
        val client = checkNotNull(Version.VERSION) { "The Elasticsearch Java client's jar names no version." }
        val image = versionOf(WowContainers.ELASTICSEARCH_IMAGE.substringAfterLast(':'))
        val advice = "WowContainers.ELASTICSEARCH_IMAGE (${WowContainers.ELASTICSEARCH_IMAGE}) is older than the " +
            "Elasticsearch Java client on the classpath ($client), which supports only a server of its own or a later " +
            "minor: move the image to the latest published patch of ${client.major()}.${client.minor()} (and " +
            "$GA_E2E_PROPERTY's file with it)."
        image.first.assert().describedAs(advice).isEqualTo(client.major())
        image.second.assert().describedAs(advice).isGreaterThanOrEqualTo(client.minor())
    }

    @Test
    fun `the GA end-to-end cluster should run the test image's version`() {
        val file = File(checkNotNull(System.getProperty(GA_E2E_PROPERTY)) { "The build sets $GA_E2E_PROPERTY." })
        val tag = checkNotNull(GA_E2E_IMAGE.find(file.readText())) { "No Elasticsearch image in $file." }.groupValues[1]
        tag.assert().describedAs(
            "$file runs Elasticsearch $tag, the tests ${WowContainers.ELASTICSEARCH_IMAGE}: move its tag (and its " +
                "digest, the index digest `docker buildx imagetools inspect` prints) to the test image's version.",
        ).isEqualTo(WowContainers.ELASTICSEARCH_IMAGE.substringAfterLast(':'))
    }

    private fun versionOf(tag: String): Pair<Int, Int> {
        val (major, minor) = tag.split('.').map { it.toInt() }
        return major to minor
    }

    private companion object {
        const val GA_E2E_PROPERTY = "wow-test.elasticsearch.ga-e2e-yaml"
        val GA_E2E_IMAGE = Regex("""image: docker\.elastic\.co/elasticsearch/elasticsearch:([^@\s]+)""")
    }
}
