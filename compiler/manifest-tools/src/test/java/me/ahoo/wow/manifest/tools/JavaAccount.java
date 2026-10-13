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

package me.ahoo.wow.manifest.tools;

import me.ahoo.wow.api.query.annotation.QueryAlias;
import me.ahoo.wow.api.query.annotation.QueryIndex;
import me.ahoo.wow.api.query.annotation.Sensitive;
import me.ahoo.wow.api.query.annotation.SensitivityLevel;

import java.util.List;
import java.util.Map;

/** A Java state: its query annotations sit on public fields, whose value types come from the JVM generics. */
public class JavaAccount {
    @QueryIndex(with = {"lastEventTime"})
    @QueryAlias({"state.owner"})
    public String holder;
    @Sensitive(level = SensitivityLevel.CONFIDENTIAL)
    public String secret;
    public String[] codes;
    public List<String> names;
    public Map<String, Integer> limits;
}
