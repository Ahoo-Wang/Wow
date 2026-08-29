/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package me.ahoo.wow.api.query

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class CursorQueryTest {
    @Test
    fun `should use cursor defaults`() {
        val query = CursorQuery(MatchAllFilter)
        query.projection.assert().isEqualTo(Projection.ALL)
        query.sort.assert().isEmpty()
        query.size.assert().isEqualTo(10)
        query.cursor.assert().isNull()
    }

    @Test
    fun `should reject size without lookahead capacity`() {
        assertThrows<IllegalArgumentException> { CursorQuery(MatchAllFilter, size = 0) }
        assertThrows<IllegalArgumentException> { CursorQuery(MatchAllFilter, size = Int.MAX_VALUE) }
    }

    @Test
    fun `should bound user sort fields at the aggregation query limit`() {
        val maximum = (0 until AggregationQuery.MAX_SORT_FIELDS).map { index ->
            Sort("field-$index", Sort.Direction.ASC)
        }

        CursorQuery(MatchAllFilter, sort = maximum).sort.assert().hasSize(AggregationQuery.MAX_SORT_FIELDS)
        assertThrows<IllegalArgumentException> {
            CursorQuery(
                MatchAllFilter,
                sort = maximum + Sort("overflow", Sort.Direction.ASC),
            )
        }
    }

    @Test
    fun `should rewrite filter and projection`() {
        val query = CursorQuery(MatchAllFilter, size = 20, cursor = "cursor")
            .withFilter(IdFilter("id"))
            .withProjection(Projection(include = listOf("state.name")))

        query.filter.assert().isEqualTo(IdFilter("id"))
        query.projection.include.assert().containsExactly("state.name")
        query.size.assert().isEqualTo(20)
        query.cursor.assert().isEqualTo("cursor")
    }
}
