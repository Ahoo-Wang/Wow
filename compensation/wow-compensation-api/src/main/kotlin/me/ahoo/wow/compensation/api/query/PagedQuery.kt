package me.ahoo.wow.compensation.api.query

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.PositiveOrZero
import me.ahoo.wow.compensation.api.query.IPagedQuery.Companion.DEFAULT_PAGE_INDEX
import me.ahoo.wow.compensation.api.query.IPagedQuery.Companion.DEFAULT_PAGE_SIZE
import me.ahoo.wow.compensation.api.query.IPagedQuery.Companion.DEFAULT_SORT
import me.ahoo.wow.compensation.api.query.IPagedQuery.Companion.DEFAULT_SORT_FIELD

data class Sort(
    @get:Schema(example = DEFAULT_SORT_FIELD)
    val field: String,
    @get:Schema(example = "DESC")
    val order: Order = Order.ASC
) {
    enum class Order {
        ASC, DESC
    }
}

interface IPagedQuery {
    @get:PositiveOrZero
    @get:Schema(example = "1")
    val pageIndex: Int

    @get:Schema(example = "10")
    @get:Positive
    val pageSize: Int

    val sort: List<Sort>

    companion object {
        const val DEFAULT_PAGE_INDEX = 1
        const val DEFAULT_PAGE_SIZE = 10
        const val DEFAULT_SORT_FIELD = "_id"
        val DEFAULT_SORT = listOf(Sort(field = DEFAULT_SORT_FIELD, order = Sort.Order.DESC))
        fun offset(pageIndex: Int, pageSize: Int) = (pageIndex - 1) * pageSize
    }

    fun offset() = offset(pageIndex, pageSize)
}

data class PagedQuery(
    override val sort: List<Sort> = DEFAULT_SORT,
    override val pageIndex: Int = DEFAULT_PAGE_INDEX,
    override val pageSize: Int = DEFAULT_PAGE_SIZE,
) : IPagedQuery
