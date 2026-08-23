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

package me.ahoo.wow.schema;

import com.fasterxml.jackson.annotation.JsonProperty;

public class JavaBeanFieldPathFixture {
    @JsonProperty("display_name")
    private String name = "";
    @JsonProperty("display name")
    private String invalidName = "";
    @JsonProperty("display.name")
    private String dottedName = "";
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String secret = "";
    private boolean frozen;

    public String getName() {
        return name;
    }

    public boolean isFrozen() {
        return frozen;
    }

    public String getInvalidName() {
        return invalidName;
    }

    public String getDottedName() {
        return dottedName;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }
}
