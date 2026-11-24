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

import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.Property

/**
 * The service's Kubernetes manifests, `wow { kubernetes { … } }`: what `generateWowManifests` renders the `wow-service`
 * chart (the plugin's own, at its version) with, into a Kustomize base of plain YAML, `build/wow/k8s/`, which a
 * deployment overlays with `kubectl apply -k` without running Helm.
 *
 * ```kotlin
 * wow {
 *     kubernetes {
 *         values.from(rootProject.file("deploy/eshop-basket/values.yaml"))
 *         namespace = "eshop"
 *     }
 * }
 * ```
 *
 * Naming [values] puts `generateWowManifests` in `assemble`. Every value that feeds a derivation of the chart (the
 * namespace, the peers' namespaces, `partOf`, the gateway rule, the monitors, the autoscaler, the shutdown bounds) is a
 * value here, checked by the chart's schema when the build renders; anything else (the image, the replicas, the
 * resources, extra environment, labels) an overlay patches.
 */
abstract class WowKubernetesExtension {
    /**
     * The deployment's values files, passed to the chart in order after the values the build generates
     * (`generateWowHelmValues`), so they override them. A build that names none renders nothing in `assemble`.
     */
    abstract val values: ConfigurableFileCollection

    /**
     * The namespace the service is deployed to. The chart derives from it (whether a peer is in another namespace, the
     * autoscaler's default query), so it is required, and the generated `kustomization.yaml` carries it: an overlay
     * must not move the base to another namespace, which would leave those derivations wrong.
     */
    abstract val namespace: Property<String>
}
