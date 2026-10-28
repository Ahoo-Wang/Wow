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

package me.ahoo.wow.api.query

/**
 * The structure of a query field's value, as a query model's descriptor writes it (`kind`), by wire name: `scalar`
 * (a string, a number, a boolean or a time), `object`, `array`, `union` (more than one of these), `null` (only
 * ever null), or `unknown` (not described). Schema declarations and storage metadata share it.
 */
enum class QueryValueKind {
    UNKNOWN,
    NULL,
    SCALAR,
    OBJECT,
    ARRAY,
    UNION,
}
