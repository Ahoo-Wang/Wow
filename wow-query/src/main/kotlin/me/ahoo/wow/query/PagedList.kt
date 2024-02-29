package me.ahoo.wow.query

interface IPagedList<out T> {
    val total: Long
    val list: List<T>
}

data class PagedList<out T>(
    override val total: Long,
    override val list: List<T>,
) : IPagedList<T>
