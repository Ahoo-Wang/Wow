package me.ahoo.wow.compensation.api.query

interface IPagedList<T> {
    val total: Long
    val list: List<T>
}

data class PagedList<T>(
    override val total: Long,
    override val list: List<T>,
) : IPagedList<T>
