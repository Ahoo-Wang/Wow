package me.ahoo.wow.api.query

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier

/**
 * [QueryErrorCodes] promises its codes are added, never renamed or reused: a client switches on them. The TypeScript
 * mirror follows the Kotlin, so a rename on both sides would pass its test; this list does not follow. A new code is
 * appended here with its constant; a code here is never changed or removed.
 */
class QueryErrorCodesTest {
    @Test
    fun `every code should be one the list holds, and none should leave it`() {
        val codes = QueryErrorCodes::class.java.declaredFields
            .filter { Modifier.isStatic(it.modifiers) && it.type == String::class.java }
            .map { it.get(null) as String }
        codes.assert().doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(CODES)
    }

    private companion object {
        val CODES = listOf(
            "invalid-json", "body-not-object", "empty-body", "unknown-property", "duplicate-property", "unknown-type",
            "unknown-value", "invalid-value", "invalid-request", "cursor-sort-duplicate", "cursor-sort-too-many",
            "invalid-cursor", "stream-not-resumable", "size-out-of-range", "filter-too-large",
            "expensive-operator-disabled", "count-requires-filter", "residual-groups-exceeded", "too-many-groups",
            "replay-too-long", "unknown-field", "unsupported-capability", "element-scope-required", "value-mismatch",
            "not-collection", "not-single-string", "model-search-unsupported", "cursor-not-allowed",
            "protected-aggregation", "protected-comparison", "missing-key-requires-string", "any-requires-single-value",
            "incomplete-projection", "metric-filter-search", "metric-filter-element-match", "metric-filter-array-field",
            "not-projectable", "event-projection-type-required", "temporal-representation-required",
            "parallel-array-sort", "array-equality", "first-last-requires-single-value", "first-last-requires-order-by",
            "temporal-aggregation-unsupported", "sort-too-many", "sort-field-duplicate", "identity-undefined",
            "storage-unsupported", "previous-unshiftable",
        )
    }
}
