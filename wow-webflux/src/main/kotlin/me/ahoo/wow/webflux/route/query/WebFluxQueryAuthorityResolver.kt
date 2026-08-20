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

package me.ahoo.wow.webflux.route.query

import me.ahoo.wow.query.invocation.QueryAuthorityView
import reactor.core.publisher.Mono
import java.security.Principal

/** Maps an authenticated WebFlux [Principal] to trusted query authority. */
fun interface WebFluxQueryAuthorityResolver {
    fun resolve(principal: Principal): Mono<QueryAuthorityView>

    companion object {
        /** Default mapping intentionally exposes only the authenticated subject identity. */
        val SUBJECT: WebFluxQueryAuthorityResolver = WebFluxQueryAuthorityResolver { principal ->
            Mono.fromCallable {
                QueryAuthorityView(
                    subjectId = principal.name,
                    tenantId = null,
                    ownerId = null,
                    spaceIds = emptySet(),
                    permissions = emptySet()
                )
            }
        }
    }
}
