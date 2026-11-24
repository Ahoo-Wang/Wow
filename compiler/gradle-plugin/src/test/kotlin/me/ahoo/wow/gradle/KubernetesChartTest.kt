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

package me.ahoo.wow.gradle

import me.ahoo.test.asserts.assert
import org.gradle.api.GradleException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipInputStream

/**
 * The chart the plugin renders is the repository's, `deploy/helm/wow-service`, at the plugin's version; and how
 * [GenerateManifests] reads what Helm renders and the values the build generates.
 */
class KubernetesChartTest {
    private val chart =
        File(requireNotNull(System.getProperty("wow-test.chart.directory")) { "wow-test.chart.directory" })

    /** The plugin's chart: each file of its resource, by its path within the chart, with its bytes. */
    private fun embedded(): Map<String, ByteArray> {
        val archive = checkNotNull(javaClass.getResourceAsStream("/me/ahoo/wow/gradle/helm/wow-service.zip"))
        return ZipInputStream(archive).use { zip ->
            generateSequence { zip.nextEntry }.filterNot { it.isDirectory }
                .associate { it.name.removePrefix("wow-service/") to zip.readBytes() }
        }
    }

    @Test
    fun `the plugin's chart should be the repository's, as helm package takes it, at the plugin's version`() {
        val ignored = File(chart, ".helmignore").readLines().map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("#") && it.endsWith("/") }
        val repository = chart.walk().filter { it.isFile }
            .map { it.relativeTo(chart).invariantSeparatorsPath }
            .filterNot { path -> ignored.any { path.startsWith(it) } || path.endsWith(".md.orig") || path.endsWith(".DS_Store") }
            .toSet()
        val embedded = embedded()
        embedded.keys.assert().isEqualTo(repository)
        embedded.keys.assert().contains("Chart.yaml", "values.yaml", "values.schema.json", "templates/deployment.yaml")
            .noneMatch { it.startsWith("tests/") || it.startsWith("ci/") }
        embedded.forEach { (path, bytes) -> bytes.assert().describedAs(path).isEqualTo(File(chart, path).readBytes()) }
        Regex("""(?m)^version:\s*(\S+)\s*$""").find(String(embedded.getValue("Chart.yaml")))!!.groupValues[1]
            .assert().isEqualTo(WowPlugin.version)
    }

    @Test
    fun `Helm's output should be one file per object, named after its kind and name`() {
        val output = """
            ---
            # Source: wow-service/templates/serviceaccount.yaml
            apiVersion: v1
            kind: ServiceAccount
            metadata:
              name: shop-service
              labels:
                app.kubernetes.io/name: shop-service
            ---
            # Source: wow-service/templates/hpa.yaml
            ---
            # Source: wow-service/templates/service.yaml
            apiVersion: v1
            kind: Service
            metadata:
              labels:
                app.kubernetes.io/name: shop-service
              name: "shop-service-cluster"
            spec:
              clusterIP: None
        """.trimIndent() + "\n"
        val objects = GenerateManifests.objectsOf(output)
        objects.map { it.file }.assert()
            .containsExactly("serviceaccount-shop-service.yaml", "service-shop-service-cluster.yaml")
        objects.first().text.assert()
            .startsWith("# Source: wow-service/templates/serviceaccount.yaml\napiVersion: v1\n")
            .endsWith("app.kubernetes.io/name: shop-service\n")
        val nameless = "---\napiVersion: v1\nmetadata:\n  name: x\n"
        assertThrows<IllegalStateException> { GenerateManifests.objectsOf(nameless) }
            .message.assert().contains("without a kind")
        assertThrows<IllegalStateException> { GenerateManifests.objectsOf("---\nkind: Service\nspec: {}\n") }
            .message.assert().contains("without a name")
    }

    @Test
    fun `the release should be the service the generated values name`(@TempDir directory: File) {
        val values = File(directory, "values.yaml")
        values.writeText(
            "# Generated\nservice:\n  name: \"shop-service\"\n  peers: []\n  manifest:\n    route: \"/wow/manifest\"\n"
        )
        GenerateManifests.serviceOf(values).assert().isEqualTo("shop-service")
        values.writeText("service:\n  peers: []\n")
        assertThrows<GradleException> { GenerateManifests.serviceOf(values) }
            .message.assert().contains("names no service")
    }
}
