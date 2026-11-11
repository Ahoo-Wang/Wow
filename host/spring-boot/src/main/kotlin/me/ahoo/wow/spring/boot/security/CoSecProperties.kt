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

    /** How CoSec runs in a service (`wow.cosec.mode`). */
    @InternalWowApi
    enum class CoSecMode {
        /** Behind a CoSec gateway, reading the identity it forwards. */
        GATEWAY,

        /** CoSec off. */
        DISABLED,
    }

    /** The callers that may act across scopes: by CoSec role or policy. */
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

    /** Callers by their CoSec roles or policies, comma-separated or as a list. */
    @InternalWowApi
    class Principals {
        /** The CoSec roles of these callers. */
        var roles: MutableSet<String> = LinkedHashSet()

        /** The CoSec policies of these callers. */
        var policies: MutableSet<String> = LinkedHashSet()
    }
}
