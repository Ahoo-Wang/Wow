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

package me.ahoo.wow.datasetregistry.starter

import me.ahoo.wow.api.Wow
import me.ahoo.wow.api.naming.EnabledCapable
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * The dataset registry's settings.
 *
 * @property enabled whether the starter adds the dataset registry to the host.
 * @property refreshInterval how often this instance reads the published datasets and serves their current versions:
 * a dataset published or retired on any instance is served, or stops being served, here within this interval.
 */
@ConfigurationProperties(prefix = DatasetRegistryProperties.PREFIX)
class DatasetRegistryProperties(
    override var enabled: Boolean = true,
    var refreshInterval: Duration = Duration.ofSeconds(30),
) : EnabledCapable {
    companion object {
        const val PREFIX = "${Wow.WOW_PREFIX}dataset-registry"
        const val ENABLED_KEY = "$PREFIX.enabled"
    }
}
