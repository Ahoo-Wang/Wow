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

package me.ahoo.wow.example.eshop.basket.server

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The basket's Kubernetes manifests, as `generateWowKubernetes` renders them from the wow-service chart with
 * deploy/eshop-basket/values.yaml for the namespace `eshop` (the build file's `wow { kubernetes { … } }`): the eight
 * objects of a Kustomize base, and the figures the chart derives for the service.
 */
class BasketManifestsTest {
    private val base = File(requireNotNull(System.getProperty("eshop.manifests")) { "eshop.manifests is not set." })

    private fun read(file: String): String = File(base, file).readText()

    @Test
    fun `the base should hold the service's eight objects, in the namespace it is rendered for`() {
        val objects = listOf(
            "configmap-eshop-basket-service-config.yaml",
            "deployment-eshop-basket-service.yaml",
            "networkpolicy-eshop-basket-service-internal.yaml",
            "networkpolicy-eshop-basket-service-public.yaml",
            "poddisruptionbudget-eshop-basket-service.yaml",
            "service-eshop-basket-service-cluster.yaml",
            "service-eshop-basket-service.yaml",
            "serviceaccount-eshop-basket-service.yaml",
        )
        base.list()!!.toList().assert().containsExactlyInAnyOrderElementsOf(objects + "kustomization.yaml")
        read("kustomization.yaml").assert().contains(
            "kind: Kustomization\nnamespace: eshop\nresources:\n" + objects.joinToString("") { "  - $it\n" },
        )
    }

    @Test
    fun `the objects should carry what the chart derives from the build's values and the deployment's`() {
        read("deployment-eshop-basket-service.yaml").assert().contains(
            "kind: Deployment",
            "replicas: 2",
            "image: wow-eshop-basket-server:local",
            // The node's stop bound and the preStop wait, from the chart's shutdown values.
            "terminationGracePeriodSeconds: 170",
            "containerPort: 8082",
            "containerPort: 9092",
            "app.kubernetes.io/part-of: eshop",
        )
        read("configmap-eshop-basket-service-config.yaml").assert().contains(
            "OTEL_SERVICE_NAME: \"eshop-basket-service\"",
            "COSEC_PIPELINE_IDENTITY_ONLYGATEWAYCANREACH: \"true\"",
            "WOW_LOG_PARTITIONS: \"64\"",
        )
        // The internal port admits the peer the build found on the class path: the ordering service.
        read("networkpolicy-eshop-basket-service-internal.yaml").assert()
            .contains("app.kubernetes.io/name: eshop-ordering-service")
        read("service-eshop-basket-service-cluster.yaml").assert().contains("port: 9092")
    }
}
