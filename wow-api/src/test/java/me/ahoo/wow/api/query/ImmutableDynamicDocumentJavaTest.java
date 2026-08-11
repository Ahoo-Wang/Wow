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

package me.ahoo.wow.api.query;

import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ImmutableDynamicDocumentJavaTest {
    @Test
    void mapDefaultMutatorsShouldAlwaysRejectEvenNoOpCalls() {
        Map<String, Object> document = document();

        assertAll(
            () -> assertThrows(UnsupportedOperationException.class, () -> document.putIfAbsent("key", "value")),
            () -> assertThrows(UnsupportedOperationException.class, () -> document.remove("missing", "value")),
            () -> assertThrows(UnsupportedOperationException.class, () -> document.replace("missing", "value")),
            () -> assertThrows(
                UnsupportedOperationException.class,
                () -> document.replace("missing", "old", "new")
            ),
            () -> assertThrows(
                UnsupportedOperationException.class,
                () -> document.computeIfAbsent("key", key -> "other")
            ),
            () -> assertThrows(
                UnsupportedOperationException.class,
                () -> document.computeIfPresent("missing", (key, value) -> value)
            ),
            () -> assertThrows(
                UnsupportedOperationException.class,
                () -> document.compute("missing", (key, value) -> null)
            ),
            () -> assertThrows(
                UnsupportedOperationException.class,
                () -> document.merge("key", "value", (left, right) -> left)
            ),
            () -> assertThrows(UnsupportedOperationException.class, () -> document.replaceAll((key, value) -> value))
        );
    }

    @Test
    void collectionViewsShouldAlwaysRejectEvenNoOpCalls() {
        Map<String, Object> document = document();

        assertStrictlyUnmodifiable(document.entrySet(), Map.entry("missing", "value"));
        assertStrictlyUnmodifiable(document.keySet(), "missing");
        assertStrictlyUnmodifiable(document.values(), "missing");
    }

    private static Map<String, Object> document() {
        return ImmutableDynamicDocument.Companion.copyOf(Map.of("key", "value"));
    }

    private static <E> void assertStrictlyUnmodifiable(Collection<E> view, E missing) {
        E existing = view.iterator().next();

        Iterator<E> iterator = view.iterator();
        iterator.next();
        assertAll(
            () -> assertThrows(UnsupportedOperationException.class, () -> view.add(existing)),
            () -> assertThrows(UnsupportedOperationException.class, () -> view.addAll(java.util.List.of())),
            () -> assertThrows(UnsupportedOperationException.class, () -> view.remove(missing)),
            () -> assertThrows(UnsupportedOperationException.class, () -> view.removeIf(value -> false)),
            () -> assertThrows(UnsupportedOperationException.class, () -> view.removeAll(java.util.List.of())),
            () -> assertThrows(UnsupportedOperationException.class, () -> view.retainAll(new HashSet<>(view))),
            () -> assertThrows(UnsupportedOperationException.class, view::clear),
            () -> assertThrows(UnsupportedOperationException.class, iterator::remove)
        );
    }
}
