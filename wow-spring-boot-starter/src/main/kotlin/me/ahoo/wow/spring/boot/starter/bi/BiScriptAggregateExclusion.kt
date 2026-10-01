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

package me.ahoo.wow.spring.boot.starter.bi

import me.ahoo.wow.api.modeling.NamedAggregate

/**
 * Leaves local aggregates out of the BI script the service generates (`wow.bi.script`); an aggregate any exclusion
 * bean names is left out.
 *
 * The script reads every aggregate's topics under one `topic-prefix`. A starter that puts its own aggregates on topics
 * under another prefix registers one, so the script never reads topics that do not exist.
 */
fun interface BiScriptAggregateExclusion {
    fun excludes(namedAggregate: NamedAggregate): Boolean
}
