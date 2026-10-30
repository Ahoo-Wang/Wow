### mongo, e1-25

| | S (1) | S/J25 |
| --- | --- | --- |
| throughput | 5,686 | - |
| failed | 0 |  |
| p50 ms | 5.791 | - |
| p99 ms | 127 | - |
| node-1 CPU ms | 0.345 | - |
| node-1 KB allocated | 107 | - |
| kafka CPU ms | 0.067 | - |
| mongo CPU ms | 0.390 | - |
| mongo insert | 1.013 | - |
| mongo query | 0.003 | - |
| mongo update | 1.105 | - |
| mongo command | 0.007 | - |

### mongo, e1-50

| | S (1) | S/J25 |
| --- | --- | --- |
| throughput | 11,307 | - |
| failed | 3,967 |  |
| p50 ms | 16.5 | - |
| p99 ms | 119 | - |
| node-1 CPU ms | 0.244 | - |
| node-1 KB allocated | 98.1 | - |
| kafka CPU ms | 0.040 | - |
| mongo CPU ms | 0.358 | - |
| mongo insert | 1.011 | - |
| mongo query | 0.002 | - |
| mongo update | 1.061 | - |
| mongo command | 0.005 | - |

### mongo, e1-75

| | S (1) | S/J25 |
| --- | --- | --- |
| throughput | 16,840 | - |
| failed | 13,137 |  |
| p50 ms | 49.2 | - |
| p99 ms | 159 | - |
| node-1 CPU ms | 0.203 | - |
| node-1 KB allocated | 94.9 | - |
| kafka CPU ms | 0.027 | - |
| mongo CPU ms | 0.303 | - |
| mongo insert | 1.011 | - |
| mongo query | 0.001 | - |
| mongo update | 1.045 | - |
| mongo command | 0.005 | - |

### mongo, e1-hot-sweep

| | S (1) | S/J25 |
| --- | --- | --- |
| peak | 18,987 | - |
| peak at | 4,096 |  |
| peak is the top step | 1 |  |

### mongo, e1-sweep

| | N3 (5) | S (1) | T (4) | N3/J25 | S/J25 | T/J25 |
| --- | --- | --- | --- | --- | --- | --- |
| peak | 12,037 | 22,745 | 25,618 | - | - | - |
| peak at | 256 | 4,096 | 4,096 |  |  |  |
| peak is the top step | 0 | 1 | 1 |  |  |  |

### mongo, e1-zipf-sweep

| | S (1) | S/J25 |
| --- | --- | --- |
| peak | 17,871 | - |
| peak at | 4,096 |  |
| peak is the top step | 1 |  |

### mongo, e3-25

| | S (1) | S/J25 |
| --- | --- | --- |
| throughput | 1,689 | - |
| failed | 0 |  |
| p50 ms | 9.919 | - |
| p99 ms | 59.6 | - |
| node-1 CPU ms | 0.963 | - |
| node-1 KB allocated | 290 | - |
| kafka CPU ms | 0.209 | - |
| mongo CPU ms | 1.130 | - |
| mongo insert | 3.037 | - |
| mongo query | 0.012 | - |
| mongo update | 3.353 | - |
| mongo command | 0.023 | - |

### mongo, e3-50

| | S (1) | S/J25 |
| --- | --- | --- |
| throughput | 3,378 | - |
| failed | 0 |  |
| p50 ms | 20.7 | - |
| p99 ms | 256 | - |
| node-1 CPU ms | 0.645 | - |
| node-1 KB allocated | 260 | - |
| kafka CPU ms | 0.119 | - |
| mongo CPU ms | 0.947 | - |
| mongo insert | 3.038 | - |
| mongo query | 0.005 | - |
| mongo update | 3.201 | - |
| mongo command | 0.018 | - |

### mongo, e3-75

| | S (1) | S/J25 |
| --- | --- | --- |
| throughput | 4,999 | - |
| failed | 4,013 |  |
| p50 ms | 78.3 | - |
| p99 ms | 434 | - |
| node-1 CPU ms | 0.531 | - |
| node-1 KB allocated | 251 | - |
| kafka CPU ms | 0.084 | - |
| mongo CPU ms | 0.926 | - |
| mongo insert | 3.038 | - |
| mongo query | 0.009 | - |
| mongo update | 3.153 | - |
| mongo command | 0.016 | - |

### mongo, e3-sweep

| | N3 (5) | S (1) | T (4) | N3/J25 | S/J25 | T/J25 |
| --- | --- | --- | --- | --- | --- | --- |
| peak | 1,565 | 6,755 | 8,725 | - | - | - |
| peak at | 64 | 1,024 | 1,024 |  |  |  |
| peak is the top step | 0 | 0 | 0 |  |  |  |

### postgres, e1-25

| | S (1) | S/J25 |
| --- | --- | --- |
| throughput | 8,295 | - |
| failed | 0 |  |
| p50 ms | 4.575 | - |
| p99 ms | 139 | - |
| node-1 CPU ms | 0.358 | - |
| node-1 KB allocated | 110 | - |
| kafka CPU ms | 0.050 | - |
| postgres CPU ms | 0.155 | - |
| postgres xact_commit | 0.377 | - |
| postgres tup_fetched | 7.589 | - |
| postgres tup_inserted | 1.010 | - |
| postgres tup_updated | 1.931 | - |

### postgres, e1-50

| | S (1) | S/J25 |
| --- | --- | --- |
| throughput | 16,340 | - |
| failed | 14,972 |  |
| p50 ms | 23.3 | - |
| p99 ms | 158 | - |
| node-1 CPU ms | 0.233 | - |
| node-1 KB allocated | 87.4 | - |
| kafka CPU ms | 0.027 | - |
| postgres CPU ms | 0.108 | - |
| postgres xact_commit | 0.097 | - |
| postgres tup_fetched | 2.813 | - |
| postgres tup_inserted | 0.998 | - |
| postgres tup_updated | 1.335 | - |

### postgres, e1-75

| | S (1) | S/J25 |
| --- | --- | --- |
| throughput | 24,235 | - |
| failed | 38,987 |  |
| p50 ms | 52.5 | - |
| p99 ms | 126 | - |
| node-1 CPU ms | 0.200 | - |
| node-1 KB allocated | 87.1 | - |
| kafka CPU ms | 0.019 | - |
| postgres CPU ms | 0.092 | - |
| postgres xact_commit | 0.025 | - |
| postgres tup_fetched | 2.301 | - |
| postgres tup_inserted | 1.003 | - |
| postgres tup_updated | 1.106 | - |

### postgres, e1-aa

| | S (2) | S (3) | S/J25 |
| --- | --- | --- | --- |
| throughput | 16,337 | 16,523 | - |
| failed | 15,208 | 3,995 |  |
| p50 ms | 15.9 | 14.1 | - |
| p99 ms | 176 | 72.2 | - |
| node-1 CPU ms | 0.235 | 0.241 | - |
| node-1 KB allocated | 85.0 | 84.8 | - |
| kafka CPU ms | 0.026 | 0.027 | - |
| postgres CPU ms | 0.096 | 0.096 | - |
| postgres xact_commit | 0.105 | 0.115 | - |
| postgres tup_fetched | 2.801 | 2.901 | - |
| postgres tup_inserted | 1.005 | 1.010 | - |
| postgres tup_updated | 1.446 | 1.495 | - |

### postgres, e1-hot-sweep

| | S (1) | S/J25 |
| --- | --- | --- |
| peak | 21,191 | - |
| peak at | 4,096 |  |
| peak is the top step | 1 |  |

### postgres, e1-sweep

| | J25 (6) | N3 (5) | S (1) | T (4) | N3/J25 | S/J25 | T/J25 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| peak | 34,213 | 15,039 | 33,180 | 36,020 | 0.440 | 0.970 | 1.053 |
| peak at | 4,096 | 256 | 4,096 | 4,096 |  |  |  |
| peak is the top step | 1 | 0 | 1 | 1 |  |  |  |

### postgres, e1-zipf-sweep

| | S (1) | S/J25 |
| --- | --- | --- |
| peak | 19,599 | - |
| peak at | 4,096 |  |
| peak is the top step | 1 |  |

### postgres, e3-25

| | S (1) | S/J25 |
| --- | --- | --- |
| throughput | 2,275 | - |
| failed | 0 |  |
| p50 ms | 8.511 | - |
| p99 ms | 582 | - |
| node-1 CPU ms | 0.865 | - |
| node-1 KB allocated | 234 | - |
| kafka CPU ms | 0.159 | - |
| postgres CPU ms | 0.430 | - |
| postgres xact_commit | 1.134 | - |
| postgres tup_fetched | 11.8 | - |
| postgres tup_inserted | 3.006 | - |
| postgres tup_updated | 5.592 | - |

### postgres, e3-50

| | S (1) | S/J25 |
| --- | --- | --- |
| throughput | 4,215 | - |
| failed | 20,127 |  |
| p50 ms | 56.6 | - |
| p99 ms | 709 | - |
| node-1 CPU ms | 0.553 | - |
| node-1 KB allocated | 224 | - |
| kafka CPU ms | 0.088 | - |
| postgres CPU ms | 0.323 | - |
| postgres xact_commit | 0.282 | - |
| postgres tup_fetched | 8.689 | - |
| postgres tup_inserted | 3.033 | - |
| postgres tup_updated | 4.060 | - |

### postgres, e3-75

| | S (1) | S/J25 |
| --- | --- | --- |
| throughput | 5,372 | - |
| failed | 87,157 |  |
| p50 ms | 99.3 | - |
| p99 ms | 799 | - |
| node-1 CPU ms | 0.517 | - |
| node-1 KB allocated | 228 | - |
| kafka CPU ms | 0.070 | - |
| postgres CPU ms | 0.287 | - |
| postgres xact_commit | 0.114 | - |
| postgres tup_fetched | 8.146 | - |
| postgres tup_inserted | 3.013 | - |
| postgres tup_updated | 3.454 | - |

### postgres, e3-sweep

| | J25 (6) | N3 (5) | S (1) | T (4) | N3/J25 | S/J25 | T/J25 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| peak | 10,049 | 5,622 | 9,101 | 11,337 | 0.559 | 0.906 | 1.128 |
| peak at | 1,024 | 256 | 1,024 | 1,024 |  |  |  |
| peak is the top step | 0 | 0 | 0 | 0 |  |  |  |

