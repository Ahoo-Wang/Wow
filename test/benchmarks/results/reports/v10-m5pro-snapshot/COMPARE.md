### mongo, e1-25

| | S (1) | S/G1 |
| --- | --- | --- |
| throughput | 4,246 | - |
| failed | 0 |  |
| p50 ms | 5.791 | - |
| p99 ms | 152 | - |
| node-1 CPU ms | 0.415 | - |
| node-1 KB allocated | 102 | - |
| kafka CPU ms | 0.097 | - |
| mongo CPU ms | 0.492 | - |
| mongo insert | 1.012 | - |
| mongo query | 0.005 | - |
| mongo update | 1.135 | - |
| mongo command | 0.006 | - |

### mongo, e1-50

| | S (1) | S/G1 |
| --- | --- | --- |
| throughput | 8,476 | - |
| failed | 959 |  |
| p50 ms | 47.9 | - |
| p99 ms | 428 | - |
| node-1 CPU ms | 0.277 | - |
| node-1 KB allocated | 90.6 | - |
| kafka CPU ms | 0.061 | - |
| mongo CPU ms | 0.426 | - |
| mongo insert | 1.009 | - |
| mongo query | 0.002 | - |
| mongo update | 1.072 | - |
| mongo command | 0.005 | - |

### mongo, e1-75

| | S (1) | S/G1 |
| --- | --- | --- |
| throughput | 12,477 | - |
| failed | 15,664 |  |
| p50 ms | 144 | - |
| p99 ms | 545 | - |
| node-1 CPU ms | 0.205 | - |
| node-1 KB allocated | 87.3 | - |
| kafka CPU ms | 0.041 | - |
| mongo CPU ms | 0.372 | - |
| mongo insert | 1.008 | - |
| mongo query | 0.001 | - |
| mongo update | 1.053 | - |
| mongo command | 0.005 | - |

### mongo, e1-fixed

| | G1 (4) | G2 (4) | G2/G1 |
| --- | --- | --- | --- |
| throughput | 6,812 | 7,017 | 1.030 |
| failed | 12,280 | 0 |  |
| p50 ms | 12.2 | 10.2 | 0.837 |
| p99 ms | 1,475 | 72.7 | 0.049 |
| node-1 CPU ms | 0.334 | 0.328 | 0.980 |
| node-1 KB allocated | 91.6 | 91.3 | 0.997 |
| kafka CPU ms | 0.074 | 0.071 | 0.965 |
| mongo CPU ms | 0.451 | 0.445 | 0.987 |
| mongo insert | 1.009 | 1.012 | 1.003 |
| mongo query | 0.001 | 0.001 | 0.971 |
| mongo update | 1.084 | 1.088 | 1.004 |
| mongo command | 0.005 | 0.005 | 0.932 |

### mongo, e1-hot-sweep

| | S (1) | S/G1 |
| --- | --- | --- |
| peak | 8,804 | - |
| peak at | 256 |  |
| peak is the top step | 0 |  |

### mongo, e1-sweep

| | G1 (4) | G2 (4) | S (1) | G2/G1 | S/G1 |
| --- | --- | --- | --- | --- | --- |
| peak | 14,034 | 14,045 | 16,984 | 1.001 | 1.210 |
| peak at | 1,024 | 1,024 | 1,024 |  |  |
| peak is the top step | 1 | 1 | 1 |  |  |

### mongo, e1-zipf-sweep

| | S (1) | S/G1 |
| --- | --- | --- |
| peak | 12,111 | - |
| peak at | 256 |  |
| peak is the top step | 0 |  |

### mongo, e3-25

| | S (1) | S/G1 |
| --- | --- | --- |
| throughput | 1,251 | - |
| failed | 0 |  |
| p50 ms | 7.583 | - |
| p99 ms | 253 | - |
| node-1 CPU ms | 1.256 | - |
| node-1 KB allocated | 283 | - |
| kafka CPU ms | 0.320 | - |
| mongo CPU ms | 1.541 | - |
| mongo insert | 3.033 | - |
| mongo query | 0.016 | - |
| mongo update | 3.455 | - |
| mongo command | 0.020 | - |

### mongo, e3-50

| | S (1) | S/G1 |
| --- | --- | --- |
| throughput | 2,501 | - |
| failed | 0 |  |
| p50 ms | 20.4 | - |
| p99 ms | 254 | - |
| node-1 CPU ms | 0.814 | - |
| node-1 KB allocated | 236 | - |
| kafka CPU ms | 0.196 | - |
| mongo CPU ms | 1.305 | - |
| mongo insert | 3.033 | - |
| mongo query | 0.008 | - |
| mongo update | 3.247 | - |
| mongo command | 0.016 | - |

### mongo, e3-75

| | S (1) | S/G1 |
| --- | --- | --- |
| throughput | 3,752 | - |
| failed | 0 |  |
| p50 ms | 92.2 | - |
| p99 ms | 664 | - |
| node-1 CPU ms | 0.603 | - |
| node-1 KB allocated | 221 | - |
| kafka CPU ms | 0.138 | - |
| mongo CPU ms | 1.227 | - |
| mongo insert | 3.038 | - |
| mongo query | 0.005 | - |
| mongo update | 3.183 | - |
| mongo command | 0.015 | - |

### mongo, e3-sweep

| | S (1) | S/G1 |
| --- | --- | --- |
| peak | 5,002 | - |
| peak at | 1,024 |  |
| peak is the top step | 1 |  |

### postgres, e1-25

| | S (1) | S/G1 |
| --- | --- | --- |
| throughput | 5,485 | - |
| failed | 8,691 |  |
| p50 ms | 5.279 | - |
| p99 ms | 1,786 | - |
| node-1 CPU ms | 0.450 | - |
| node-1 KB allocated | 84.3 | - |
| kafka CPU ms | 0.084 | - |
| postgres CPU ms | 0.198 | - |
| postgres xact_commit | 0.496 | - |
| postgres tup_fetched | 4.091 | - |
| postgres tup_inserted | 1.000 | - |
| postgres tup_updated | 1.990 | - |

### postgres, e1-50

| | S (1) | S/G1 |
| --- | --- | --- |
| throughput | 11,169 | - |
| failed | 5,458 |  |
| p50 ms | 108 | - |
| p99 ms | 322 | - |
| node-1 CPU ms | 0.246 | - |
| node-1 KB allocated | 78.9 | - |
| kafka CPU ms | 0.046 | - |
| postgres CPU ms | 0.149 | - |
| postgres xact_commit | 0.135 | - |
| postgres tup_fetched | 3.008 | - |
| postgres tup_inserted | 1.010 | - |
| postgres tup_updated | 1.446 | - |

### postgres, e1-75

| | S (1) | S/G1 |
| --- | --- | --- |
| throughput | 16,703 | - |
| failed | 11,300 |  |
| p50 ms | 157 | - |
| p99 ms | 344 | - |
| node-1 CPU ms | 0.200 | - |
| node-1 KB allocated | 78.5 | - |
| kafka CPU ms | 0.031 | - |
| postgres CPU ms | 0.129 | - |
| postgres xact_commit | 0.050 | - |
| postgres tup_fetched | 2.437 | - |
| postgres tup_inserted | 1.009 | - |
| postgres tup_updated | 1.186 | - |

### postgres, e1-aa

| | S (2) | S (3) | S/G1 |
| --- | --- | --- | --- |
| throughput | 11,260 | 11,260 | - |
| failed | 0 | 0 |  |
| p50 ms | 5.951 | 5.759 | - |
| p99 ms | 31.7 | 33.3 | - |
| node-1 CPU ms | 0.349 | 0.354 | - |
| node-1 KB allocated | 77.0 | 76.6 | - |
| kafka CPU ms | 0.049 | 0.047 | - |
| postgres CPU ms | 0.140 | 0.140 | - |
| postgres xact_commit | 0.235 | 0.235 | - |
| postgres tup_fetched | 3.560 | 3.559 | - |
| postgres tup_inserted | 1.005 | 1.003 | - |
| postgres tup_updated | 1.871 | 1.870 | - |

### postgres, e1-fixed

| | G1 (4) | G2 (4) | G2/G1 |
| --- | --- | --- | --- |
| throughput | 9,128 | 9,128 | 1.000 |
| failed | 0 | 0 |  |
| p50 ms | 5.407 | 5.119 | 0.947 |
| p99 ms | 40.7 | 31.0 | 0.761 |
| node-1 CPU ms | 0.376 | 0.398 | 1.057 |
| node-1 KB allocated | 77.9 | 77.9 | 0.999 |
| kafka CPU ms | 0.060 | 0.058 | 0.966 |
| postgres CPU ms | 0.168 | 0.173 | 1.028 |
| postgres xact_commit | 0.307 | 0.307 | 1.001 |
| postgres tup_fetched | 3.642 | 3.652 | 1.003 |
| postgres tup_inserted | 1.003 | 1.003 | 0.999 |
| postgres tup_updated | 1.936 | 1.938 | 1.001 |

### postgres, e1-hot-sweep

| | S (1) | S/G1 |
| --- | --- | --- |
| peak | 12,272 | - |
| peak at | 64 |  |
| peak is the top step | 0 |  |

### postgres, e1-sweep

| | G1 (4) | G2 (4) | S (1) | G2/G1 | S/G1 |
| --- | --- | --- | --- | --- | --- |
| peak | 18,255 | 18,613 | 22,521 | 1.020 | 1.234 |
| peak at | 1,024 | 1,024 | 1,024 |  |  |
| peak is the top step | 1 | 1 | 1 |  |  |

### postgres, e1-zipf-sweep

| | S (1) | S/G1 |
| --- | --- | --- |
| peak | 14,134 | - |
| peak at | 1,024 |  |
| peak is the top step | 1 |  |

### postgres, e3-25

| | S (1) | S/G1 |
| --- | --- | --- |
| throughput | 1,742 | - |
| failed | 0 |  |
| p50 ms | 5.503 | - |
| p99 ms | 281 | - |
| node-1 CPU ms | 1.379 | - |
| node-1 KB allocated | 218 | - |
| kafka CPU ms | 0.274 | - |
| postgres CPU ms | 0.739 | - |
| postgres xact_commit | 2.282 | - |
| postgres tup_fetched | 12.9 | - |
| postgres tup_inserted | 3.019 | - |
| postgres tup_updated | 6.223 | - |

### postgres, e3-50

| | S (1) | S/G1 |
| --- | --- | --- |
| throughput | 3,329 | - |
| failed | 9,260 |  |
| p50 ms | 49.9 | - |
| p99 ms | 2,163 | - |
| node-1 CPU ms | 0.659 | - |
| node-1 KB allocated | 196 | - |
| kafka CPU ms | 0.144 | - |
| postgres CPU ms | 0.464 | - |
| postgres xact_commit | 0.429 | - |
| postgres tup_fetched | 9.206 | - |
| postgres tup_inserted | 3.009 | - |
| postgres tup_updated | 4.376 | - |

### postgres, e3-75

| | S (1) | S/G1 |
| --- | --- | --- |
| throughput | 5,225 | - |
| failed | 0 |  |
| p50 ms | 114 | - |
| p99 ms | 410 | - |
| node-1 CPU ms | 0.533 | - |
| node-1 KB allocated | 192 | - |
| kafka CPU ms | 0.104 | - |
| postgres CPU ms | 0.432 | - |
| postgres xact_commit | 0.161 | - |
| postgres tup_fetched | 7.374 | - |
| postgres tup_inserted | 3.011 | - |
| postgres tup_updated | 3.538 | - |

### postgres, e3-sweep

| | S (1) | S/G1 |
| --- | --- | --- |
| peak | 6,967 | - |
| peak at | 1,024 |  |
| peak is the top step | 1 |  |

