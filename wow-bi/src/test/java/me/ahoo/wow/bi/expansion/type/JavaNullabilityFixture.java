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

package me.ahoo.wow.bi.expansion.type;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import me.ahoo.wow.api.Identifier;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class JavaNullabilityFixture {
    private final int primitive;
    private final Integer nullableBoxed;
    private final String nullableReference;
    private final String nonNullFromGetter;
    private final String unknownReference;
    private final List<@Nullable String> nullableElementList;
    private final String originalName;

    @JsonCreator
    public JavaNullabilityFixture(
        @JsonProperty("primitive") int primitive,
        @JsonProperty("nullableBoxed") @Nullable Integer nullableBoxed,
        @JsonProperty("nullableReference") @Nullable String nullableReference,
        @JsonProperty("nonNullFromGetter") String nonNullFromGetter,
        @JsonProperty("unknownReference") String unknownReference,
        @JsonProperty("nullableElementList") List<@Nullable String> nullableElementList,
        @JsonProperty("renamedJava") String originalName
    ) {
        this.primitive = primitive;
        this.nullableBoxed = nullableBoxed;
        this.nullableReference = nullableReference;
        this.nonNullFromGetter = nonNullFromGetter;
        this.unknownReference = unknownReference;
        this.nullableElementList = nullableElementList;
        this.originalName = originalName;
    }

    public int getPrimitive() {
        return primitive;
    }

    public Integer getNullableBoxed() {
        return nullableBoxed;
    }

    public String getNullableReference() {
        return nullableReference;
    }

    @NotNull
    public String getNonNullFromGetter() {
        return nonNullFromGetter;
    }

    public String getUnknownReference() {
        return unknownReference;
    }

    public List<@Nullable String> getNullableElementList() {
        return nullableElementList;
    }

    @JsonProperty("renamedJava")
    public String getOriginalName() {
        return originalName;
    }

    public static class ConflictingAnnotations {
        private final @Nullable String conflicting;

        public ConflictingAnnotations(String conflicting) {
            this.conflicting = conflicting;
        }

        @NotNull
        public String getConflicting() {
            return conflicting;
        }
    }

    public static class KotlinGenericContractState
        implements KotlinGenericListContract, KotlinGenericValueContract<String>, Identifier {
        @Override
        public String getId() {
            return "id";
        }

        @Override
        public List<String> getNonNullValues() {
            return List.of();
        }

        @Override
        public List<String> getNullableElementValues() {
            return List.of();
        }

        @Override
        public List<String> getGenericValues() {
            return List.of();
        }

        @NotNull
        public Map<String, Integer> getUnknownKeyValues() {
            return Map.of();
        }
    }

    @SuppressWarnings("serial")
    public static class StringList extends ArrayList<String> {
    }

    @SuppressWarnings("serial")
    public static class StringMap extends HashMap<String, Integer> {
    }

    public static class RefinedContractImplementation implements RefinedValueContract {
        @Override
        public String getRefinedValue() {
            return "value";
        }
    }

    public static class ConflictingParallelContractImplementation
        implements ParallelNullableValueContract, ParallelNonNullValueContract {
        @Override
        public String getParallelValue() {
            return "value";
        }
    }

    public static class GenericBox<T> {
        private final T value;

        public GenericBox(T value) {
            this.value = value;
        }

        public T getValue() {
            return value;
        }
    }

    public static final class AnnotatedMapBox
        extends GenericBox<@NotNull Map<@NotNull String, @NotNull Integer>> {
        public AnnotatedMapBox() {
            super(Map.of());
        }
    }

    public interface GenericRoot<T> {
        default T getValue() {
            return null;
        }
    }

    public interface ANonNullRoot extends GenericRoot<@NotNull String> {
    }

    public interface ZNullableRoot extends GenericRoot<@Nullable String> {
    }

    public static final class ConflictingGenericDiamond implements ANonNullRoot, ZNullableRoot {
    }

    public static final class GenericBoxState implements Identifier {
        @Override
        @NotNull
        public String getId() {
            return "id";
        }

        @NotNull
        public GenericBox<@NotNull String> getScalarBox() {
            return new GenericBox<>("");
        }

        @NotNull
        public GenericBox<@NotNull List<@Nullable String>> getListBox() {
            return new GenericBox<>(List.of());
        }

        @NotNull
        public GenericBox<@NotNull Map<@NotNull String, @NotNull Integer>> getMapBox() {
            return new GenericBox<>(Map.of());
        }
    }

    public interface NonNullMapContract {
        @NotNull
        Map<@NotNull String, @NotNull Integer> getInheritedJavaMap();
    }

    public static final class NonNullMapContractImplementation implements NonNullMapContract {
        @Override
        public Map<String, Integer> getInheritedJavaMap() {
            return Map.of();
        }
    }
}
