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
import org.gradle.testfixtures.ProjectBuilder
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * `generateWowKubernetes` built with the plugin (Gradle TestKit), on [ContextsFixture]'s context `a`: its service
 * `a-server` names a values file and a namespace in `wow { kubernetes { … } }`, and the plugin renders its chart with
 * the pinned Helm into `build/wow/k8s`. The test fetches that Helm once, into this build's Gradle user home, and hands it
 * to the builds as `wow.helm.executable`, so they run with the binary the plugin fetches.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KubernetesManifestsFunctionalTest {
    private lateinit var root: File

    private lateinit var helm: File

    /** The service's build file as the fixture writes it, before its `kubernetes` block. */
    private lateinit var serverBuild: String

    private val task = ":a-server:${WowPlugin.KUBERNETES_TASK}"
    private val output: File get() = File(root, "a-server/build/wow/k8s")

    @BeforeAll
    fun build(@TempDir directory: File) {
        root = directory
        val home = File(requireNotNull(System.getProperty("wow-test.gradle.userHome")) { "wow-test.gradle.userHome" })
        helm = HelmService.register(ProjectBuilder.builder().withGradleUserHomeDir(home).build()).get().executable()
        ContextsFixture.write(
            root,
            "settings.gradle.kts",
            """
            rootProject.name = "contexts"
            include("a-api", "a-domain", "a-server")
            buildCache { local { directory = file("build-cache") } }
            """,
        )
        ContextsFixture.properties(root)
        ContextsFixture.context(
            root,
            ContextsFixture.Context(
                "a",
                "Alpha",
                "b",
                "Beta",
                consumes = false,
            ),
        )
        serverBuild = File(root, "a-server/build.gradle.kts").readText()
        server()
        values("image:\n  repository: a-server\n  tag: \"1.0.0\"\nreplicas: 2\npartOf: shop\n")
    }

    private fun values(text: String) = ContextsFixture.write(root, "a-server/deploy/values.yaml", text)

    private fun runner(vararg arguments: String, executable: String = helm.path): GradleRunner = GradleRunner.create()
        .withProjectDir(root)
        .withPluginClasspath()
        .withArguments(*arguments, "-P${HelmService.EXECUTABLE_PROPERTY}=$executable", "--stacktrace")
        .forwardOutput()

    /** The service's build file with its `kubernetes` block: the values file and the namespace, each when asked for. */
    private fun server(
        values: Boolean = true,
        namespace: Boolean = true,
        files: List<String> = listOf("deploy/values.yaml"),
    ) {
        val lines = listOfNotNull(
            files.joinToString(", ", "values.from(", ")") { "\"$it\"" }.takeIf { values },
            "namespace = \"shop\"".takeIf { namespace },
        )
        val block = "\n\nwow {\n    kubernetes {\n" + lines.joinToString("") { "        $it\n" } + "    }\n}\n"
        File(root, "a-server/build.gradle.kts").writeText(serverBuild + block)
    }

    @Test
    fun `the chart should render into a Kustomize base, up to date the second time and from the cache after a clean`() {
        runner(task, "--build-cache").build().task(task)?.outcome.assert().isEqualTo(TaskOutcome.SUCCESS)
        output.list()!!.toList().assert().containsExactlyInAnyOrder(
            "kustomization.yaml",
            "configmap-a-server-config.yaml",
            "deployment-a-server.yaml",
            "networkpolicy-a-server-internal.yaml",
            "networkpolicy-a-server-public.yaml",
            "poddisruptionbudget-a-server.yaml",
            "service-a-server-cluster.yaml",
            "service-a-server.yaml",
            "serviceaccount-a-server.yaml",
        )
        File(output, "kustomization.yaml").readText().assert().contains(
            "rendered with Helm ${HelmRelease.VERSION} for a-server in shop",
            "kind: Kustomization\nnamespace: shop\nresources:\n  - configmap-a-server-config.yaml\n",
        )
        File(output, "deployment-a-server.yaml").readText().assert()
            .contains(
                "# Source: wow-service/templates/deployment.yaml",
                "kind: Deployment",
                "replicas: 2",
                "image: a-server:1.0.0"
            )
        runner(task, "--build-cache").build().task(task)?.outcome.assert().isEqualTo(TaskOutcome.UP_TO_DATE)
        output.deleteRecursively()
        runner(task, "--build-cache").build().task(task)?.outcome.assert().isEqualTo(TaskOutcome.FROM_CACHE)
        File(output, "deployment-a-server.yaml").isFile.assert().isTrue()
    }

    @Test
    fun `swapping two values files of one name should render again, and the other file win`() {
        ContextsFixture.write(root, "a-server/deploy/shop/values.yaml", "image:\n  repository: a-server\nreplicas: 3\n")
        ContextsFixture.write(root, "a-server/deploy/shop/prod/values.yaml", "replicas: 5\n")
        val deployment = File(output, "deployment-a-server.yaml")
        try {
            server(files = listOf("deploy/shop/values.yaml", "deploy/shop/prod/values.yaml"))
            runner(task, "--build-cache").build()
            deployment.readText().assert().contains("replicas: 5")
            server(files = listOf("deploy/shop/prod/values.yaml", "deploy/shop/values.yaml"))
            runner(task, "--build-cache").build().task(task)?.outcome.assert().isEqualTo(TaskOutcome.SUCCESS)
            deployment.readText().assert().contains("replicas: 3")
        } finally {
            server()
        }
    }

    @Test
    fun `a values file that does not exist should fail naming it and the line to correct`() {
        try {
            server(files = listOf("deploy/valeus.yaml"))
            runner(task).buildAndFail().output.assert().contains(
                "wow { kubernetes { values.from(…) } } names \"",
                "deploy/valeus.yaml\", which does not exist: correct the path in values.from(…), or remove it.",
            )
        } finally {
            server()
        }
    }

    @Test
    fun `a value the chart's schema refuses should fail the build with the chart's message`() {
        values("image:\n  repository: a-server\nreplicaz: 3\n")
        try {
            runner(task).buildAndFail().output.assert().contains(
                "Helm refused to render the wow-service chart",
                "additional properties 'replicaz' not allowed",
            )
        } finally {
            values("image:\n  repository: a-server\n  tag: \"1.0.0\"\nreplicas: 2\npartOf: shop\n")
        }
    }

    @Test
    fun `a render without a namespace, or without values, should fail naming the line to add`() {
        try {
            server(namespace = false)
            runner(task).buildAndFail().output.assert()
                .contains("set wow { kubernetes { namespace = \"<namespace>\" } }")
            server(values = false)
            runner(task).buildAndFail().output.assert()
                .contains("wow { kubernetes { values.from(\"deploy/<service>/values.yaml\") } }")
        } finally {
            server()
        }
    }

    @Test
    fun `configuring the build should look for no Helm, and assemble should render only when the build names values`() {
        // A Helm that does not exist: looking for it while configuring every task would fail the build.
        runner("tasks", "--all", executable = File(root, "no-helm").path).build()
        runner(":a-server:assemble", "--dry-run").build().output.assert().contains(task)
        try {
            server(values = false)
            runner(":a-server:assemble", "--dry-run").build().output.assert().doesNotContain(task)
        } finally {
            server()
        }
    }
}
