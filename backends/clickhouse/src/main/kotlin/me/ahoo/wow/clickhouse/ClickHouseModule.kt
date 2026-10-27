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

package me.ahoo.wow.clickhouse

/**
 * The ClickHouse module (wow-clickhouse): the Sink (`me.ahoo.wow.clickhouse.sink`) and the
 * dataset query backend (`me.ahoo.wow.clickhouse.query`). The host's `ClickHouseAutoConfiguration` conditions
 * on this class by name.
 */
internal object ClickHouseModule {
    override fun toString(): String = "wow-clickhouse"
}
