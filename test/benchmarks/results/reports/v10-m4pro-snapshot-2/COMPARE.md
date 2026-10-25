### mongo, e1-25

| | S (1) | S/G1 |
| --- | --- | --- |
| throughput | 4,054 | - |
| failed | 0 |  |
| p50 ms | 4.863 | - |
| p99 ms | 261 | - |
| node-1 CPU ms | 0.368 | - |
| node-1 KB allocated | 103 | - |
| kafka CPU ms | 0.105 | - |
| mongo CPU ms | 0.517 | - |
| mongo insert | 1.009 | - |
| mongo query | 0.005 | - |
| mongo update | 1.138 | - |
| mongo command | 0.006 | - |

### mongo, e1-50

| | S (1) | S/G1 |
| --- | --- | --- |
| throughput | 7,809 | - |
| failed | 17,955 |  |
| p50 ms | 23.0 | - |
| p99 ms | 582 | - |
| node-1 CPU ms | 0.255 | - |
| node-1 KB allocated | 91.6 | - |
| kafka CPU ms | 0.067 | - |
| mongo CPU ms | 0.427 | - |
| mongo insert | 1.001 | - |
| mongo query | 0.003 | - |
| mongo update | 1.066 | - |
| mongo command | 0.005 | - |

### mongo, e1-75

| | S (1) | S/G1 |
| --- | --- | --- |
| throughput | 11,758 | - |
| failed | 24,233 |  |
| p50 ms | 111 | - |
| p99 ms | 827 | - |
| node-1 CPU ms | 0.195 | - |
| node-1 KB allocated | 87.0 | - |
| kafka CPU ms | 0.046 | - |
| mongo CPU ms | 0.373 | - |
| mongo insert | 1.009 | - |
| mongo query | 0.001 | - |
| mongo update | 1.056 | - |
| mongo command | 0.005 | - |

### mongo, e1-fixed

| | G1 (4) | G2 (4) | G2/G1 |
| --- | --- | --- | --- |
| throughput | 8,380 | 8,380 | 1.000 |
| failed | 0 | 0 |  |
| p50 ms | 15.2 | 23.8 | 1.563 |
| p99 ms | 206 | 217 | 1.055 |
| node-1 CPU ms | 0.256 | 0.249 | 0.975 |
| node-1 KB allocated | 90.8 | 88.7 | 0.977 |
| kafka CPU ms | 0.055 | 0.059 | 1.072 |
| mongo CPU ms | 0.353 | 0.387 | 1.095 |
| mongo insert | 1.010 | 1.008 | 0.998 |
| mongo query | 0.001 | 0.001 | 1.000 |
| mongo update | 1.074 | 1.071 | 0.998 |
| mongo command | 0.005 | 0.005 | 0.993 |

### mongo, e1-hot-sweep

| | S (1) | S/G1 |
| --- | --- | --- |
| peak | 9,779 | - |
| peak at | 256 |  |
| peak is the top step | 0 |  |

### mongo, e1-sweep

| | G1 (4) | G2 (4) | S (1) | G2/G1 | S/G1 |
| --- | --- | --- | --- | --- | --- |
| peak | 16,761 | 16,466 | 16,215 | 0.982 | 0.967 |
| peak at | 1,024 | 1,024 | 1,024 |  |  |
| peak is the top step | 1 | 1 | 1 |  |  |

### mongo, e1-zipf-sweep

| | S (1) | S/G1 |
| --- | --- | --- |
| peak | 13,345 | - |
| peak at | 1,024 |  |
| peak is the top step | 1 |  |

### mongo, e3-25

| | S (1) | S/G1 |
| --- | --- | --- |
| throughput | 1,445 | - |
| failed | 0 |  |
| p50 ms | 7.679 | - |
| p99 ms | 328 | - |
| node-1 CPU ms | 0.975 | - |
| node-1 KB allocated | 270 | - |
| kafka CPU ms | 0.287 | - |
| mongo CPU ms | 1.477 | - |
| mongo insert | 3.024 | - |
| mongo query | 0.013 | - |
| mongo update | 3.385 | - |
| mongo command | 0.019 | - |

### mongo, e3-50

| | S (1) | S/G1 |
| --- | --- | --- |
| throughput | 2,644 | - |
| failed | 14,784 |  |
| p50 ms | 51.5 | - |
| p99 ms | 4,489 | - |
| node-1 CPU ms | 0.585 | - |
| node-1 KB allocated | 230 | - |
| kafka CPU ms | 0.172 | - |
| mongo CPU ms | 1.104 | - |
| mongo insert | 3.032 | - |
| mongo query | 0.020 | - |
| mongo update | 3.212 | - |
| mongo command | 0.019 | - |

### mongo, e3-75

| | S (1) | S/G1 |
| --- | --- | --- |
| throughput | 4,098 | - |
| failed | 14,287 |  |
| p50 ms | 102 | - |
| p99 ms | 2,671 | - |
| node-1 CPU ms | 0.502 | - |
| node-1 KB allocated | 219 | - |
| kafka CPU ms | 0.124 | - |
| mongo CPU ms | 1.132 | - |
| mongo insert | 3.027 | - |
| mongo query | 0.005 | - |
| mongo update | 3.153 | - |
| mongo command | 0.015 | - |

### mongo, e3-sweep

| | S (1) | S/G1 |
| --- | --- | --- |
| peak | 5,781 | - |
| peak at | 1,024 |  |
| peak is the top step | 1 |  |

### postgres, e1-25

| | S (1) | S/G1 |
| --- | --- | --- |
| throughput | 4,951 | - |
| failed | 17,992 |  |
| p50 ms | 6.111 | - |
| p99 ms | 2,998 | - |
| node-1 CPU ms | 0.319 | - |
| node-1 KB allocated | 85.9 | - |
| kafka CPU ms | 0.076 | - |
| postgres CPU ms | 0.188 | - |
| postgres xact_commit | 0.473 | - |
| postgres tup_fetched | 4.050 | - |
| postgres tup_inserted | 1.020 | - |
| postgres tup_updated | 1.963 | - |

### postgres, e1-50

| | S (1) | S/G1 |
| --- | --- | --- |
| throughput | 10,274 | - |
| failed | 13,742 |  |
| p50 ms | 28.0 | - |
| p99 ms | 516 | - |
| node-1 CPU ms | 0.221 | - |
| node-1 KB allocated | 79.9 | - |
| kafka CPU ms | 0.044 | - |
| postgres CPU ms | 0.144 | - |
| postgres xact_commit | 0.145 | - |
| postgres tup_fetched | 3.104 | - |
| postgres tup_inserted | 1.000 | - |
| postgres tup_updated | 1.504 | - |

### postgres, e1-75

| | S (1) | S/G1 |
| --- | --- | --- |
| throughput | 14,449 | - |
| failed | 78,295 |  |
| p50 ms | 246 | - |
| p99 ms | 778 | - |
| node-1 CPU ms | 0.189 | - |
| node-1 KB allocated | 82.0 | - |
| kafka CPU ms | 0.033 | - |
| postgres CPU ms | 0.129 | - |
| postgres xact_commit | 0.048 | - |
| postgres tup_fetched | 2.408 | - |
| postgres tup_inserted | 0.997 | - |
| postgres tup_updated | 1.167 | - |

### postgres, e1-aa

| | S (2) | S (3) | S/G1 |
| --- | --- | --- | --- |
| throughput | 10,381 | 10,491 | - |
| failed | 7,308 | 695 |  |
| p50 ms | 5.119 | 4.639 | - |
| p99 ms | 143 | 179 | - |
| node-1 CPU ms | 0.308 | 0.316 | - |
| node-1 KB allocated | 78.9 | 78.5 | - |
| kafka CPU ms | 0.042 | 0.041 | - |
| postgres CPU ms | 0.124 | 0.124 | - |
| postgres xact_commit | 0.332 | 0.341 | - |
| postgres tup_fetched | 3.591 | 3.622 | - |
| postgres tup_inserted | 1.008 | 1.010 | - |
| postgres tup_updated | 1.900 | 1.914 | - |

### postgres, e1-fixed

| | G1 (4) | G2 (4) | G2/G1 |
| --- | --- | --- | --- |
| throughput | 8,399 | 8,269 | 0.985 |
| failed | 33,013 | 40,796 |  |
| p50 ms | 7.519 | 12.4 | 1.643 |
| p99 ms | 680 | 1,294 | 1.904 |
| node-1 CPU ms | 0.296 | 0.280 | 0.946 |
| node-1 KB allocated | 81.4 | 80.6 | 0.991 |
| kafka CPU ms | 0.052 | 0.051 | 0.971 |
| postgres CPU ms | 0.139 | 0.137 | 0.990 |
| postgres xact_commit | 0.358 | 0.284 | 0.793 |
| postgres tup_fetched | 3.502 | 3.214 | 0.918 |
| postgres tup_inserted | 1.025 | 1.006 | 0.982 |
| postgres tup_updated | 1.881 | 1.731 | 0.920 |

### postgres, e1-hot-sweep

| | S (1) | S/G1 |
| --- | --- | --- |
| peak | 9,054 | - |
| peak at | 256 |  |
| peak is the top step | 0 |  |

### postgres, e1-sweep

| | G1 (4) | G2 (4) | S (1) | G2/G1 | S/G1 |
| --- | --- | --- | --- | --- | --- |
| peak | 17,898 | 18,708 | 21,005 | 1.045 | 1.174 |
| peak at | 1,024 | 1,024 | 1,024 |  |  |
| peak is the top step | 1 | 1 | 1 |  |  |

### postgres, e1-zipf-sweep

| | S (1) | S/G1 |
| --- | --- | --- |
| peak | 11,951 | - |
| peak at | 256 |  |
| peak is the top step | 0 |  |

### postgres, e3-25

| | S (1) | S/G1 |
| --- | --- | --- |
| throughput | 1,775 | - |
| failed | 0 |  |
| p50 ms | 8.831 | - |
| p99 ms | 688 | - |
| node-1 CPU ms | 0.870 | - |
| node-1 KB allocated | 209 | - |
| kafka CPU ms | 0.226 | - |
| postgres CPU ms | 0.570 | - |
| postgres xact_commit | 1.731 | - |
| postgres tup_fetched | 12.0 | - |
| postgres tup_inserted | 3.014 | - |
| postgres tup_updated | 5.826 | - |

### postgres, e3-50

| | S (1) | S/G1 |
| --- | --- | --- |
| throughput | 3,420 | - |
| failed | 7,717 |  |
| p50 ms | 84.5 | - |
| p99 ms | 1,729 | - |
| node-1 CPU ms | 0.485 | - |
| node-1 KB allocated | 196 | - |
| kafka CPU ms | 0.127 | - |
| postgres CPU ms | 0.449 | - |
| postgres xact_commit | 0.304 | - |
| postgres tup_fetched | 8.455 | - |
| postgres tup_inserted | 3.000 | - |
| postgres tup_updated | 4.038 | - |

### postgres, e3-75

| | S (1) | S/G1 |
| --- | --- | --- |
| throughput | 5,089 | - |
| failed | 14,121 |  |
| p50 ms | 137 | - |
| p99 ms | 1,417 | - |
| node-1 CPU ms | 0.432 | - |
| node-1 KB allocated | 193 | - |
| kafka CPU ms | 0.099 | - |
| postgres CPU ms | 0.415 | - |
| postgres xact_commit | 0.136 | - |
| postgres tup_fetched | 7.226 | - |
| postgres tup_inserted | 3.025 | - |
| postgres tup_updated | 3.462 | - |

### postgres, e3-sweep

| | S (1) | S/G1 |
| --- | --- | --- |
| peak | 7,099 | - |
| peak at | 1,024 |  |
| peak is the top step | 1 |  |

