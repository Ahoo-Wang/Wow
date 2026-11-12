| Session | Commits | mongod CPU µs/commit, net | Idle cores | Log insert µs/commit | Snapshot update µs/op | Inserts, updates /commit | Log avgObjSize | Log indexes, MB | p50 / p99 ms | Drops |
| --- | ---: | ---: | ---: | ---: | ---: | --- | ---: | --- | --- | ---: |
| r1-before | 1,199,980 | 185.1 | 0.132 | 26.59 | 71.1 | 1.000, 1.070 | 559 | 5, 356.6 | 10.05 / 190.5 | 20 |
| r1-d420 | 1,199,769 | 185.0 | 0.126 | 25.30 | 71.4 | 1.000, 1.071 | 545 | 5, 291.1 | 8.89 / 193.5 | 231 |
| r1-tag | 1,199,756 | 194.6 | 0.123 | 25.08 | 76.1 | 1.000, 1.065 | 523 | 3, 295.6 | 7.81 / 132.1 | 244 |
| r2-before | 1,199,168 | 200.7 | 0.135 | 28.71 | 74.3 | 1.000, 1.074 | 559 | 5, 357.4 | 7.29 / 124.9 | 832 |
| r2-d420 | 1,200,000 | 182.5 | 0.142 | 25.13 | 71.2 | 1.000, 1.070 | 545 | 5, 296.9 | 9.60 / 182.3 | 0 |
| r2-tag | 1,198,128 | 188.9 | 0.129 | 24.37 | 75.7 | 1.000, 1.063 | 523 | 3, 292.3 | 8.45 / 236.5 | 1872 |

| Session | _id_ | access_owner | access_space | request_key | version_key |
| --- | ---: | ---: | ---: | ---: | ---: |
| r1-before | 41.1 | 31.2 | 31.6 | 107.4 | 145.4 |
| r1-d420 | 40.1 | 0.0 | 0.0 | 106.6 | 144.3 |
| r1-tag | 41.5 |  |  | 108.9 | 145.2 |
| r2-before | 41.4 | 30.8 | 30.8 | 110.0 | 144.4 |
| r2-d420 | 42.0 | 0.0 | 0.0 | 110.1 | 144.8 |
| r2-tag | 40.8 |  |  | 106.5 | 145.0 |

| Session | Log documents | Keys per index | The last document's `access` |
| --- | ---: | --- | --- |
| r1-before | 1,399,986 | _id_ 1,399,986, access_owner 1,399,986, access_space 1,399,986, request_key 1,399,986, version_key 1,399,986 | `{"owner": null, "space": null}` |
| r1-d420 | 1,399,775 | _id_ 1,399,775, access_owner 0, access_space 0, request_key 1,399,775, version_key 1,399,775 | `{}` |
| r1-tag | 1,399,762 | _id_ 1,399,762, request_key 1,399,762, version_key 1,399,762 | `"(absent)"` |
| r2-before | 1,399,174 | _id_ 1,399,174, access_owner 1,399,174, access_space 1,399,174, request_key 1,399,174, version_key 1,399,174 | `{"owner": null, "space": null}` |
| r2-d420 | 1,400,006 | _id_ 1,400,006, access_owner 0, access_space 0, request_key 1,400,006, version_key 1,400,006 | `{}` |
| r2-tag | 1,398,134 | _id_ 1,398,134, request_key 1,398,134, version_key 1,398,134 | `"(absent)"` |

tag / before: CPU 1.051, 0.941 (median 0.996); log insert 0.943, 0.849 (median 0.896)

d420 / tag: CPU 0.951, 0.966 (median 0.958); log insert 1.009, 1.031 (median 1.020)

d420 / before: CPU 1.000, 0.909 (median 0.954); log insert 0.951, 0.875 (median 0.913)
