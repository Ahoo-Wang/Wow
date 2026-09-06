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

package me.ahoo.wow.schema.query;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import me.ahoo.wow.api.query.mask.Mask;

public final class JavaCreatorMaskedState {
    public static final class ConstructorState {
        @Mask
        public final String secret;
        @JsonIgnore
        public final String received;

        @JsonCreator
        public ConstructorState(@JsonProperty("secret") String secret) {
            this.received = secret;
            this.secret = secret;
        }
    }

    public static final class FactoryState {
        @Mask
        public final String secret;
        @JsonIgnore
        public final String received;

        private FactoryState(String secret, String received) {
            this.secret = secret;
            this.received = received;
        }

        @JsonCreator
        public static FactoryState create(@JsonProperty("secret") String secret) {
            return new FactoryState(secret, secret);
        }
    }
}
