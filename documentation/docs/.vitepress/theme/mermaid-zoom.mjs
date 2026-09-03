export const cycleFocus = (index, size, reverse) => {
    if (size <= 0) return -1
    if (index < 0) return reverse ? size - 1 : 0
    return (index + (reverse ? -1 : 1) + size) % size
}
