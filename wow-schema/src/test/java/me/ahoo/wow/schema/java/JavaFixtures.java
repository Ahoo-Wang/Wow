package me.ahoo.wow.schema.java;

import io.swagger.v3.oas.annotations.media.Schema;

public class JavaFixtures {
    public record RecordFixture(String name, long balance) {
    }

    public record AnnotatedRecordFixture(
            String name,
            @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED) String nickname) {
    }

    public static class BeanFixture {
        private String name;

        public String getName() {
            return name;
        }
    }
}
