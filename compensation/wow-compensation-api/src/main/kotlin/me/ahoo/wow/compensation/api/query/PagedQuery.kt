package me.ahoo.wow.compensation.api.query

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.PositiveOrZero
import me.ahoo.wow.compensation.api.query.IPagedQuery.Companion.DEFAULT_PAGE_INDEX
import me.ahoo.wow.compensation.api.query.IPagedQuery.Companion.DEFAULT_PAGE_SIZE

interface IPagedQuery {
    @get:PositiveOrZero
    @get:Schema(example = "1")
    val pageIndex: Int

    @get:Schema(example = "10")
    @get:Positive
    val pageSize: Int

    companion object {
        const val DEFAULT_PAGE_INDEX = 0
        const val DEFAULT_PAGE_SIZE = 10
        fun offset(pageIndex: Int, pageSize: Int) = (pageIndex - 1) * pageSize
        fun of(pageIndex: Int, pageSize: Int) = PagedQuery(pageIndex, pageSize)
    }

    fun offset() = offset(pageIndex, pageSize)
}

data class PagedQuery(
    override val pageIndex: Int = DEFAULT_PAGE_INDEX,
    override val pageSize: Int = DEFAULT_PAGE_SIZE,
) : IPagedQuery
