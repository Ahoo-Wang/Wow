package me.ahoo.wow.api.query

interface IPagedList<out T> {
    val total: Long
    val list: List<T>
}

data class PagedList<out T>(
    override val total: Long,
    override val list: List<T>,
) : IPagedList<T> {
    companion object {
        private val EMPTY: PagedList<Nothing> = PagedList(0, emptyList())

        fun <T> empty(): PagedList<T> = EMPTY
    }
}
