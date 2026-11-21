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

import me.ahoo.wow.api.query.annotation.Sensitive;
import me.ahoo.wow.api.query.annotation.SensitivityLevel;

/**
 * A Java superclass that overrides a generic interface's getter with a concrete type: javac also declares the bridge
 * {@code Object getSecret()}, and copies the annotation onto it.
 */
public abstract class JavaSecretBase implements QueryKeywordsInheritanceTest.Plain<String> {
    @Override
    @Sensitive(level = SensitivityLevel.CONFIDENTIAL)
    public abstract String getSecret();
}
