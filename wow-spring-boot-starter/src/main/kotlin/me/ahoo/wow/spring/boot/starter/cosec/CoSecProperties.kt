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

package me.ahoo.wow.spring.boot.starter.cosec

import me.ahoo.wow.api.Wow
import me.ahoo.wow.cosec.abac.CoSecAbacQueryPolicy
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.DefaultValue

/**
 * CoSec settings.
 *
 * @property abac the principal's ABAC tags read from CoSec ([CoSecAbacQueryPolicy]).
 */
@ConfigurationProperties(prefix = CoSecProperties.PREFIX)
class CoSecProperties(
    var abac: Abac = Abac(),
) {
    /**
     * @property enabled registers [CoSecAbacQueryPolicy] where CoSec's WebFlux module is on the classpath and the
     * application declares no `AbacQueryPolicy` of its own. Off by default: it starts restricting queries by the
     * principal's attributes. Since 9.9.0.
     * @property attributePrefix the prefix of the principal attributes that are ABAC tags (`abac.region`).
     */
    data class Abac(
        @DefaultValue("false")
        var enabled: Boolean = false,
        @DefaultValue(CoSecAbacQueryPolicy.DEFAULT_ATTRIBUTE_PREFIX)
        var attributePrefix: String = CoSecAbacQueryPolicy.DEFAULT_ATTRIBUTE_PREFIX,
    )

    companion object {
        const val PREFIX = "${Wow.WOW_PREFIX}cosec"
        const val ABAC_ENABLED = "$PREFIX.abac.enabled"
    }
}
