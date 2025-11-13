package me.ahoo.wow.api.query

/**
 * Interface representing a paginated list of items with total count information.
 *
 * This interface provides access to both the total number of items available
 * and the current page of items in the list.
 *
 * @param T The type of items in the list.
 */
interface IPagedList<out T> {
    /**
     * The total number of items available across all pages.
     */
    val total: Long

    /**
     * The list of items for the current page.
     */
    val list: List<T>
}

/**
 * Data class implementing a paginated list of items.
 *
 * This class provides a concrete implementation of [IPagedList] and includes
 * a companion object with factory methods for creating empty paginated lists.
 *
 * @param T The type of items in the list.
 * @property total The total number of items available across all pages.
 * @property list The list of items for the current page.
 *
 * @sample
 * ```
 * val page = PagedList(
 *     total = 150,
 *     list = listOf(item1, item2, item3)
 * )
 * ```
 */
data class PagedList<out T>(
    override val total: Long,
    override val list: List<T>
) : IPagedList<T> {
    companion object {
        private val EMPTY: PagedList<Nothing> = PagedList(0, emptyList())

        /**
         * Creates an empty paginated list.
         *
         * @param T The type of items the empty list would contain.
         * @return An empty PagedList with total = 0 and an empty list.
         */
        fun <T> empty(): PagedList<T> = EMPTY
    }
}
