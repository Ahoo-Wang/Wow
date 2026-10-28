package me.ahoo.wow.api.query

import me.ahoo.wow.api.annotation.InternalWowApi

/**
 * Data class implementing a paginated list of items.
 *
 * @param T The type of items in the list.
 * @property total The number of items across all pages; `null` when not counted ([TotalMode.NONE]).
 * @property list The list of items for the current page.
 * @property exact Whether [total] is every match; it defaults to whether [total] is present, so a bounded total that
 * reached its limit passes `false`.
 *
 * ```
 * val exact = PagedList(total = 150, list = listOf(item1, item2, item3))
 * val bounded = PagedList(total = 10000, list = page, exact = false) // at least 10000
 * val uncounted = PagedList(total = null, list = page)
 * ```
 */
class PagedList<out T> @InternalWowApi constructor(
    val total: Long?,
    val list: List<T>,
    val exact: Boolean = total != null,
) {
    init {
        require(total == null || total >= 0) {
            "total must not be negative."
        }
        require(total != null || !exact) {
            "An uncounted total cannot be exact."
        }
    }

    companion object {
        private val EMPTY: PagedList<Nothing> = PagedList(0, emptyList())

        /**
         * Creates an empty paginated list.
         *
         * @param T The type of items the empty list would contain.
         * @return An empty PagedList with an exact total of 0 and an empty list.
         */
        fun <T> empty(): PagedList<T> = EMPTY

        /** A [PagedList] of the given values. */
        @JvmStatic
        @JvmOverloads
        fun <T> of(
            total: Long?,
            list: List<T>,
            exact: Boolean = total != null,
        ): PagedList<T> = PagedList(total, list, exact)
    }

    /** This PagedList with the given values replaced; for Wow's own query rewriting. */
    @InternalWowApi
    fun copy(
        total: Long? = this.total,
        list: List<@UnsafeVariance T> = this.list,
        exact: Boolean = this.exact,
    ): PagedList<T> = PagedList(total, list, exact)

    override fun equals(other: Any?): Boolean =
        this === other || other is PagedList<*> && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "PagedList(total=$total, list=$list, exact=$exact)"

    private fun valueFields(): List<Any?> = listOf(total, list, exact)
}
