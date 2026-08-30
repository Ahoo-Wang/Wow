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

package me.ahoo.wow.infra.reflection;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.METHOD;

public final class JavaMergedAnnotationFixture {
    private JavaMergedAnnotationFixture() {
    }

    @Target(METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Marker {
        String value();
    }

    public interface LocalBase {
        @Marker("base")
        String value();
    }

    public static final class LocalChild implements LocalBase {
        @Override
        @Marker("local")
        public String value() {
            return "local";
        }
    }

    public interface TransitiveBase {
        @Marker("base")
        String value();
    }

    public interface TransitiveMid extends TransitiveBase {
        @Override
        @Marker("mid")
        String value();
    }

    public static final class TransitiveChild implements TransitiveMid {
        @Override
        public String value() {
            return "value";
        }
    }

    public interface EqualLeft {
        @Marker("same")
        String value();
    }

    public interface EqualRight {
        @Marker("same")
        String value();
    }

    public static final class EqualDiamond implements EqualLeft, EqualRight {
        @Override
        public String value() {
            return "value";
        }
    }

    public interface DifferentLeft {
        @Marker("left")
        String value();
    }

    public interface DifferentRight {
        @Marker("right")
        String value();
    }

    public static final class DifferentDiamond implements DifferentLeft, DifferentRight {
        @Override
        public String value() {
            return "value";
        }
    }

    public interface OverloadedBase {
        @Marker("string")
        String convert(String value);
    }

    public static final class OverloadedChild implements OverloadedBase {
        @Override
        public String convert(String value) {
            return value;
        }

        public String convert(Integer value) {
            return value.toString();
        }
    }

    public interface CovariantBase {
        @Marker("base")
        Number value();
    }

    public interface CovariantMid extends CovariantBase {
        @Override
        @Marker("mid")
        Integer value();
    }

    public static final class CovariantChild implements CovariantMid {
        @Override
        public Integer value() {
            return 1;
        }
    }
}
