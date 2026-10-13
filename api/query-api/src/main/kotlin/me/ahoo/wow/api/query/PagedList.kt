package me.ahoo.wow.api.query

/**
 * Interface representing a paginated list of items with its total, counted as the query's [TotalMode] asks.
 *
 * @param T The type of items in the list.
 */
interface IPagedList<out T> {
    /**
     * The number of items across all pages: exact when [exact] is `true`, a lower bound ("at least") when it is
     * `false`, and `null` when the query asked for [TotalMode.NONE].
     */
    val total: Long?

    /**
     * Whether [total] is every match. Always `false` when [total] is `null`.
     */
    val exact: Boolean

    /**
     * The list of items for the current page.
     */
    val list: List<T>
}

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
data class PagedList<out T>(
    override val total: Long?,
    override val list: List<T>,
    override val exact: Boolean = total != null,
) : IPagedList<T> {
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
    }
}
