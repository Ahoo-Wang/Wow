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

package me.ahoo.wow.spring.boot.starter.r2dbc

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = DataSourceProperties.PREFIX)
class DataSourceProperties(var type: Type = Type.SIMPLE) {
    companion object {
        const val PREFIX = "${R2dbcProperties.PREFIX}.datasource"
        const val TYPE = "$PREFIX.type"
    }

    enum class Type {
        SIMPLE,
        SHARDING
        ;

        companion object {
            const val SIMPLE_NAME = "simple"
            const val SHARDING_NAME = "sharding"
        }
    }
}
