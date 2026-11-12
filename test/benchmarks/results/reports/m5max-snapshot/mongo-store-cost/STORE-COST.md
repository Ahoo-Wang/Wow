| Session | Commits | mongod CPU µs/commit, net | Idle cores | Log insert µs/commit | Snapshot update µs/op | Inserts, updates /commit | Log avgObjSize | Log indexes, MB | p50 / p99 ms | Drops |
| --- | ---: | ---: | ---: | ---: | ---: | --- | ---: | --- | --- | ---: |
| r1-before | 1,199,469 | 219.7 | 0.139 | 32.22 | 83.3 | 1.000, 1.072 | 559 | 5, 354.9 | 9.41 / 190.5 | 531 |
| r1-d420 | 1,200,000 | 216.8 | 0.133 | 29.98 | 86.8 | 1.000, 1.071 | 545 | 5, 291.6 | 10.30 / 184.3 | 0 |
| r1-tag | 1,200,000 | 225.5 | 0.138 | 30.80 | 89.9 | 1.000, 1.066 | 523 | 3, 296.2 | 7.78 / 111.1 | 0 |
| r2-before | 1,198,641 | 204.1 | 0.138 | 29.79 | 77.3 | 1.000, 1.074 | 559 | 5, 357.4 | 7.39 / 174.1 | 1359 |
| r2-d420 | 1,200,000 | 219.1 | 0.140 | 30.68 | 84.2 | 1.000, 1.073 | 545 | 5, 295.3 | 8.26 / 126.5 | 0 |
| r2-tag | 1,199,800 | 183.1 | 0.130 | 23.85 | 75.6 | 1.000, 1.062 | 523 | 3, 296.9 | 9.92 / 198.7 | 200 |

| Session | _id_ | access_owner | access_space | request_key | version_key |
| --- | ---: | ---: | ---: | ---: | ---: |
| r1-before | 41.5 | 30.1 | 31.1 | 106.8 | 145.4 |
| r1-d420 | 40.9 | 0.0 | 0.0 | 106.5 | 144.1 |
| r1-tag | 42.1 |  |  | 109.7 | 144.4 |
| r2-before | 40.7 | 31.6 | 30.8 | 109.3 | 145.1 |
| r2-d420 | 41.3 | 0.0 | 0.0 | 107.9 | 146.0 |
| r2-tag | 42.2 |  |  | 108.8 | 145.9 |

| Session | Log documents | Keys per index | The last document's `access` |
| --- | ---: | --- | --- |
| r1-before | 1,399,475 | _id_ 1,399,475, access_owner 1,399,475, access_space 1,399,475, request_key 1,399,475, version_key 1,399,475 | `{"owner": null, "space": null}` |
| r1-d420 | 1,400,006 | _id_ 1,400,006, access_owner 0, access_space 0, request_key 1,400,006, version_key 1,400,006 | `{}` |
| r1-tag | 1,400,006 | _id_ 1,400,006, request_key 1,400,006, version_key 1,400,006 | `"(absent)"` |
| r2-before | 1,398,647 | _id_ 1,398,647, access_owner 1,398,647, access_space 1,398,647, request_key 1,398,647, version_key 1,398,647 | `{"owner": null, "space": null}` |
| r2-d420 | 1,400,006 | _id_ 1,400,006, access_owner 0, access_space 0, request_key 1,400,006, version_key 1,400,006 | `{}` |
| r2-tag | 1,399,806 | _id_ 1,399,806, request_key 1,399,806, version_key 1,399,806 | `"(absent)"` |

tag / before: CPU 1.026, 0.897 (median 0.961); log insert 0.956, 0.801 (median 0.878)

d420 / tag: CPU 0.962, 1.197 (median 1.079); log insert 0.973, 1.286 (median 1.130)

d420 / before: CPU 0.987, 1.073 (median 1.030); log insert 0.930, 1.030 (median 0.980)
