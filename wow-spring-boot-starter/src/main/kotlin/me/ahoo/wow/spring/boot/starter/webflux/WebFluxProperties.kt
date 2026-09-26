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

package me.ahoo.wow.spring.boot.starter.webflux

import me.ahoo.wow.api.Wow
import me.ahoo.wow.api.naming.EnabledCapable
import me.ahoo.wow.spring.boot.starter.ENABLED_SUFFIX_KEY
import me.ahoo.wow.webflux.route.query.HttpQueryGuard.Companion.DEFAULT_LIST_SIZE
import me.ahoo.wow.webflux.route.state.PointReadAdmission
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.DefaultValue
import java.time.Duration

@ConfigurationProperties(prefix = WebFluxProperties.PREFIX)
class WebFluxProperties
@Autowired(required = false)
constructor(
    override var enabled: Boolean = true,
    var globalError: GlobalError = GlobalError(),
    var batch: Batch = Batch(),
) : EnabledCapable {
    var query: Query = Query()

    var state: State = State()

    companion object {
        const val PREFIX = "${Wow.WOW_PREFIX}webflux"
        const val COMMAND_REQUEST_APPENDER_PREFIX = "$PREFIX.command.request.appender"
        const val GLOBAL_ERROR_ENABLED = "$PREFIX.global-error$ENABLED_SUFFIX_KEY"
    }

    data class GlobalError(
        @DefaultValue("true")
        override var enabled: Boolean = true
    ) : EnabledCapable

    data class Batch(
        @DefaultValue("128")
        var concurrency: Int = 128,
        @DefaultValue("4")
        var prefetch: Int = 4
    )

    /** The HTTP adapter's side of queries; the HTTP budget itself is `wow.query.http`. */
    data class Query(
        @DefaultValue("$DEFAULT_LIST_SIZE")
        var defaultListSize: Int = DEFAULT_LIST_SIZE,
        @DefaultValue("10s")
        var idleTimeout: Duration = Duration.ofSeconds(10),
        /** Reject a count request body whose root names neither `op` nor `operator`; off, it counts every row. */
        @DefaultValue("false")
        var strictCountFilter: Boolean = false,
    )

    /**
     * State point reads (load by id, version or time, and tracing).
     *
     * @property pointReadAdmission admits each loaded state through the query admission: the caller's request scope,
     * the query policies and the snapshot default scope are checked on it in memory (a state outside them reads as
     * absent), the response is masked, and tracing is capped at [tracingMaxVersions]. Off by default.
     * @property tracingMaxVersions the most versions one tracing request may return under point-read admission;
     * `0` disables the cap.
     */
    data class State(
        @DefaultValue("false")
        var pointReadAdmission: Boolean = false,
        @DefaultValue("${PointReadAdmission.DEFAULT_TRACING_MAX_VERSIONS}")
        var tracingMaxVersions: Int = PointReadAdmission.DEFAULT_TRACING_MAX_VERSIONS,
    )
}
