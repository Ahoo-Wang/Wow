---
type: regex
pattern: 'ReservationTenancy\.SYSTEM|tenancy"?\s*[:=]\s*"?SYSTEM'
match: contains
target: last_message
---

The answer names the `SYSTEM` reservation tenancy.
