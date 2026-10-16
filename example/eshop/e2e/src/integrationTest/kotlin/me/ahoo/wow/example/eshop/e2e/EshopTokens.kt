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

package me.ahoo.wow.example.eshop.e2e

/**
 * The secrets of the shop's services in these tests, as a deployment configures them:
 *
 * - each service's cluster token (`wow.internal.token`, decision D226), which its nodes forward and signal with;
 * - the service tokens of the services whose sagas wait across contexts ([MESH]): each one's own identity
 *   (`wow.calls.token`, decision D193), and the others' it trusts (`wow.http.services`). The basket calls the ordering
 *   with its token, and each service signals with its own the waits that crossed into it from another one.
 */
object EshopTokens {
    /** The services whose waits and calls cross into one another. */
    val MESH: List<String> = listOf("basket", "ordering", "inventory", "payment")

    /** The cluster token of the service of [context]. */
    fun cluster(context: String): String = "e2e-eshop-$context-cluster-token-0123456789"

    /** The service token of the service of [context]. */
    fun service(context: String): String = "e2e-eshop-$context-service-token-0123456789"

    /** The command-line arguments that give the service of [context] its secrets. */
    fun arguments(context: String): List<String> = buildList {
        add("--wow.internal.token=${cluster(context)}")
        if (context in MESH) {
            add("--wow.calls.token=${service(context)}")
            MESH.filter { it != context }.forEach { add("--wow.http.services.eshop-$it-service.tokens[0]=${service(it)}") }
        }
    }
}
