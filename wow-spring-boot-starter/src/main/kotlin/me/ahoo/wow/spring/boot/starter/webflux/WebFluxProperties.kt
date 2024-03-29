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
import me.ahoo.wow.spring.boot.starter.ENABLED_SUFFIX_KEY
import me.ahoo.wow.spring.boot.starter.EnabledCapable
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.DefaultValue

@ConfigurationProperties(prefix = WebFluxProperties.PREFIX)
class WebFluxProperties(
    @DefaultValue("true") override var enabled: Boolean = true,
    var globalError: GlobalError = GlobalError()
) : EnabledCapable {
    companion object {
        const val PREFIX = "${Wow.WOW_PREFIX}webflux"
        const val GLOBAL_ERROR_ENABLED = "$PREFIX.global-error" + ENABLED_SUFFIX_KEY
    }

    data class GlobalError(
        @DefaultValue("true")
        override var enabled: Boolean = true
    ) : EnabledCapable
}
