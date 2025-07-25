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

package me.ahoo.wow.spring.boot.starter.prepare

import me.ahoo.wow.api.Wow
import me.ahoo.wow.api.naming.EnabledCapable
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.DefaultValue

@ConfigurationProperties(prefix = PrepareProperties.PREFIX)
class PrepareProperties(
    @DefaultValue("true") override var enabled: Boolean = true,
    var storage: PrepareStorage = PrepareStorage.MONGO,
    var basePackages: List<String> = emptyList(),
) : EnabledCapable {
    companion object {
        const val PREFIX = "${Wow.WOW_PREFIX}prepare"
        const val STORAGE = "$PREFIX.storage"
        const val BASE_PACKAGES = "$PREFIX.base-packages"
    }
}

enum class PrepareStorage {
    MONGO,
    REDIS
    ;

    companion object {
        const val MONGO_NAME = "mongo"
        const val REDIS_NAME = "redis"
    }
}
