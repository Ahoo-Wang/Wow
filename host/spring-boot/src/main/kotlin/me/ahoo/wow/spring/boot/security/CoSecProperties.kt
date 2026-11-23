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

package me.ahoo.wow.spring.boot.security

import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.spring.boot.config.ConfigurationKeys

/**
 * CoSec's settings (wow-cosec), which the host's CoSec configuration reads from here alone, their one reader and
 * their one default: they have metadata, and a key of `wow.cosec` that none of these is refuses
 * the startup ([ConfigurationKeys]) instead of being ignored, so a mistyped [Authorizer.enabled] cannot leave the
 * authorizer that allows all in place.
 */
@InternalWowApi
class CoSecProperties {
    /**
     * How CoSec runs in this service, as defaults its own `cosec.*` keys override
     * ([CoSecModeEnvironmentPostProcessor]): `gateway`, behind a CoSec gateway (`cosec.pipeline.mode=gateway`: the
     * caller read from the `CoSec-Identity` the gateway verified, with its app and space, and no token verified
     * here); `disabled`, CoSec off (`cosec.enabled=false`). Unset, CoSec's own defaults: CoSec decides the requests
     * itself, the Manifest's routes its route map.
     */
    var mode: CoSecMode? = null

    /** CoSec's authorization of the commands. */
    var authorizer: Authorizer = Authorizer()

    /**
     * How the body of CoSec's refusals is written: `cosec`, CoSec's own problems, as they are; `wow`, Wow's problems,
     * their type and code Wow's (`wow.auth.unauthenticated`, `wow.auth.forbidden`, `wow.request.malformed`,
     * `wow.request.rate-limited`, `wow.auth.unavailable`), CoSec's own in their member `cosec`, the status and the
     * headers CoSec's. For a service that decides its requests itself: behind a CoSec gateway, the gateway's refusals
     * keep CoSec's bodies. An application's own `ProblemRenderer` writes them instead, either way.
     */
    var problems: Problems = Problems.COSEC

    /** How the body of CoSec's refusals is written (`wow.cosec.problems`). */
    @InternalWowApi
    enum class Problems {
        /** CoSec's own problems. */
        COSEC,

        /** Wow's problems. */
        WOW,
    }

    /** How CoSec runs in a service (`wow.cosec.mode`). */
    @InternalWowApi
    enum class CoSecMode {
        /** Behind a CoSec gateway, reading the identity it forwards. */
        GATEWAY,

        /** CoSec off. */
        DISABLED,
    }

    /** The callers that may act across scopes: by CoSec role. */
    var crossScope: Principals = Principals()

    /** The tenant-level callers, who read and write every aggregate of their tenant. */
    var tenantLevel: Principals = Principals()

    @InternalWowApi
    class Authorizer {
        /**
         * Whether CoSec authorizes every command and service route call of a request as its caller
         * (`CoSecCommandAuthorizer`, `<context>:<command>` on `<aggregate>/<id>`), not only the public routes,
         * where CoSec decides itself. A request-less command gets no objection. Off, and without a
         * `CommandAuthorizer` of the application's, the command gateway allows every command that passed its
         * route: behind a CoSec gateway, authorization is the gateway's.
         */
        var enabled: Boolean = false
    }

    /**
     * Callers by their CoSec roles in the request's scope, comma-separated or as a list. CoSec 8 tells an application no
     * principal's policies, so no caller is named by one.
     */
    @InternalWowApi
    class Principals {
        /** The CoSec roles of these callers. */
        var roles: MutableSet<String> = LinkedHashSet()
    }
}
