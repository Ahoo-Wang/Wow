---
type: regex
pattern: 'source: new \w*SnapshotQueryClient\('
match: contains
target: last_message
---

The answer registers the generated (or wow-client's) snapshot query client itself as the resource's source, with no adapter.
