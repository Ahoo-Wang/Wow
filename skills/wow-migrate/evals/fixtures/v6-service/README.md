# Synthetic Wow v6.21.5 service

This directory is a small, non-production input for forward evaluation of
`wow-migrate`. It contains a pinned v6 platform baseline, a representative
aggregate, and the `store` / `snapshot` configuration keys that already existed
in v6.

For the read-only case, inspect the checked-in files and distinguish facts from
missing dependency, runtime, data, and production evidence. For the local
platform-update case, change only `build.gradle.kts`; preserve Java 17 and the
application configuration. A human or standard Agent eval harness can compare
the resulting diff and report without any repository-owned runner.
