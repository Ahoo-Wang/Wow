---
type: regex
pattern: '^(?!\s*-)[^\n]*size\s*<(?!=)\s*MAX_CART_ITEM_SIZE'
flags: m
match: contains
target: last_message
---

The answer writes the corrected `size < MAX_CART_ITEM_SIZE` bound on a line of its own: a quoted `-` line of the patch does not count, and the patch's `+` line has `<=`.
