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
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.annotation.JsonPOJOBuilder;
import tools.jackson.databind.ser.std.ToStringSerializer;
import com.fasterxml.jackson.annotation.JsonSetter;
import tools.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import me.ahoo.wow.api.query.mask.Mask;

public final class JavaCreatorMaskedState {
    @Mask
    @java.lang.annotation.Target(java.lang.annotation.ElementType.PARAMETER)
    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
    public @interface ParameterMask { }

    @JsonSerialize(using = ToStringSerializer.class)
    public static final class OpaqueConstructor {
        private final String stored;
        public OpaqueConstructor(@ParameterMask String secret) { stored = secret; }
        @Override public String toString() { return stored; }
    }

    @JsonSerialize(using = ToStringSerializer.class)
    public static final class OpaqueFactory {
        private final String stored;
        private OpaqueFactory(String stored) { this.stored = stored; }
        @Override public String toString() { return stored; }
        @JsonCreator
        public static OpaqueFactory create(@JsonProperty("secret") @ParameterMask String secret) {
            return new OpaqueFactory(secret);
        }
    }

    public static final class AliasSetter {
        private String stored;
        @Mask @JsonGetter("wire_secret")
        public String secret() { return stored; }
        @JsonSetter("input") @JsonAlias("wire_secret")
        public void input(String value) { stored = value; }
    }

    public static final class AliasCreator {
        private final String stored;
        @JsonCreator
        public AliasCreator(@JsonProperty("input") @JsonAlias("wire_secret") String value) { stored = value; }
        @Mask @JsonGetter("wire_secret")
        public String secret() { return stored; }
    }

    @JsonSerialize(using = ToStringSerializer.class)
    public static final class OpaqueGenericConstructor<T> {
        private final String stored;
        public OpaqueGenericConstructor(java.util.List<T> values) { stored = values.toString(); }
        @Override public String toString() { return stored; }
    }

    @JsonSerialize(using = ToStringSerializer.class)
    public static final class OpaqueImplicitFactory {
        private final String stored;
        private OpaqueImplicitFactory(String stored) { this.stored = stored; }
        public static OpaqueImplicitFactory valueOf(@ParameterMask String secret) {
            return new OpaqueImplicitFactory(secret);
        }
        @Override public String toString() { return stored; }
    }

    @JsonSerialize(using = ToStringSerializer.class)
    public static final class OpaqueGenericFactory<T> {
        private final String stored;
        private OpaqueGenericFactory(String stored) { this.stored = stored; }
        @JsonCreator
        public static <T> OpaqueGenericFactory<T> create(@JsonProperty("values") java.util.List<T> values) {
            return new OpaqueGenericFactory<>(values.toString());
        }
        @Override public String toString() { return stored; }
    }

    @JsonDeserialize(builder = AliasBuilder.Builder.class)
    public static final class AliasBuilder {
        private final String stored;
        private AliasBuilder(String stored) { this.stored = stored; }
        @Mask @JsonGetter("wire_secret")
        public String secret() { return stored; }
        @JsonPOJOBuilder(withPrefix = "with")
        public static final class Builder {
            private String stored;
            @JsonAlias("wire_secret")
            public Builder withInput(String value) { stored = value; return this; }
            public AliasBuilder build() { return new AliasBuilder(stored); }
        }
    }

    @JsonPropertyOrder({"input", "wire_secret"})
    public static final class CanonicalWins {
        private String stored;
        @Mask @JsonGetter("wire_secret")
        public String secret() { return stored; }
        @JsonSetter("wire_secret")
        public void canonical(String value) { stored = value; }
        @JsonSetter("input") @JsonAlias("wire_secret")
        @JsonDeserialize(using = MaskMaterializationSafetyTest.RawDecoder.class)
        public void alias(String value) { stored = value; }
    }

    @JsonPropertyOrder({"first", "second", "wire_secret"})
    public static final class AliasCollision {
        private String stored;
        @Mask @JsonGetter("wire_secret")
        public String secret() { return stored; }
        @JsonSetter("first") @JsonAlias("wire_secret")
        public void first(String value) { stored = value; }
        @JsonSetter("second") @JsonAlias("wire_secret")
        @JsonDeserialize(using = MaskMaterializationSafetyTest.RawDecoder.class)
        public void second(String value) { stored = value; }
    }

    public static final class CreatorAliasCollision {
        private final String stored;
        @JsonCreator
        public CreatorAliasCollision(
                @JsonProperty("first") @JsonAlias("wire_secret")
                @JsonDeserialize(using = MaskMaterializationSafetyTest.RawDecoder.class) String first,
                @JsonProperty("second") @JsonAlias("wire_secret") String second) { stored = second; }
        @Mask @JsonGetter("wire_secret")
        public String secret() { return stored; }
    }

    public static final class CreatorBeforeSetter {
        private String stored;
        @JsonCreator
        public CreatorBeforeSetter(@JsonProperty("input") @JsonAlias("wire_secret") String value) { stored = value; }
        @Mask @JsonGetter("wire_secret")
        public String secret() { return stored; }
        @JsonSetter("wire_secret")
        @JsonDeserialize(using = MaskMaterializationSafetyTest.RawDecoder.class)
        public void secret(String value) { stored = value; }
    }

    @JsonIgnoreProperties("input")
    public static final class IgnoredAlias {
        private String stored = "raw";
        @Mask @JsonGetter("wire_secret")
        public String secret() { return stored; }
        @JsonSetter("input") @JsonAlias("wire_secret")
        public void input(String value) { stored = value; }
    }

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
