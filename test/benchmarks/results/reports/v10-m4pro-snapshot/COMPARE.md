### mongo, e1-25

| | S (1) | S/G1 |
| --- | --- | --- |
| throughput | 3,678 | - |
| failed | 14,311 |  |
| p50 ms | 24.6 | - |
| p99 ms | 3,228 | - |
| node-1 CPU ms | 0.445 | - |
| node-1 KB allocated | 143 | - |
| kafka CPU ms | 0.183 | - |
| mongo CPU ms | 1.530 | - |
| mongo insert | 1.006 | - |
| mongo query | 0.343 | - |
| mongo update | 1.135 | - |
| mongo command | 0.345 | - |

### mongo, e1-50

| | S (1) | S/G1 |
| --- | --- | --- |
| throughput | 5,068 | - |
| failed | 165,918 |  |
| p50 ms | 692 | - |
| p99 ms | 2,327 | - |
| node-1 CPU ms | 0.407 | - |
| node-1 KB allocated | 155 | - |
| kafka CPU ms | 0.131 | - |
| mongo CPU ms | 1.800 | - |
| mongo insert | 1.016 | - |
| mongo query | 0.358 | - |
| mongo update | 1.105 | - |
| mongo command | 0.363 | - |

### mongo, e1-75

| | S (1) | S/G1 |
| --- | --- | --- |
| throughput | 764 | - |
| failed | 659,174 |  |
| p50 ms | 5,767 | - |
| p99 ms | 11,469 | - |
| node-1 CPU ms | 1.840 | - |
| kafka CPU ms | 1.182 | - |
| mongo CPU ms | 10.9 | - |
| mongo insert | 1.047 | - |
| mongo query | 2.451 | - |
| mongo update | 0.664 | - |
| mongo command | 1.064 | - |

### mongo, e1-fixed

| | G1 (4) | G2 (4) | G2/G1 |
| --- | --- | --- | --- |
| throughput | 7,400 | 7,502 | 1.014 |
| failed | 6,124 | 0 |  |
| p50 ms | 10.1 | 10.9 | 1.082 |
| p99 ms | 512 | 225 | 0.440 |
| node-1 CPU ms | 0.279 | 0.272 | 0.974 |
| node-1 KB allocated | 91.0 | 91.0 | 0.999 |
| kafka CPU ms | 0.062 | 0.059 | 0.955 |
| mongo CPU ms | 0.369 | 0.364 | 0.986 |
| mongo insert | 1.009 | 1.016 | 1.007 |
| mongo query | 0.001 | 0.001 | 0.992 |
| mongo update | 1.080 | 1.087 | 1.007 |
| mongo command | 0.005 | 0.005 | 0.980 |

### mongo, e1-hot-sweep

| | S (1) | S/G1 |
| --- | --- | --- |
| peak | 10,209 | - |
| peak at | 256 |  |
| peak is the top step | 0 |  |

### mongo, e1-sweep

| | G1 (4) | G2 (4) | S (1) | G2/G1 | S/G1 |
| --- | --- | --- | --- | --- | --- |
| peak | 15,004 | 14,679 | 15,667 | 0.978 | 1.044 |
| peak at | 1,024 | 1,024 | 1,024 |  |  |
| peak is the top step | 1 | 1 | 1 |  |  |

### mongo, e1-zipf-sweep

| | S (1) | S/G1 |
| --- | --- | --- |
| peak | 13,273 | - |
| peak at | 256 |  |
| peak is the top step | 0 |  |

### mongo, e3-25

| | S (1) | S/G1 |
| --- | --- | --- |
| throughput | 792 | - |
| failed | 0 |  |
| p50 ms | 9.343 | - |
| p99 ms | 206 | - |
| node-1 CPU ms | 1.683 | - |
| kafka CPU ms | 0.509 | - |
| mongo CPU ms | 3.733 | - |
| mongo insert | 3.036 | - |
| mongo query | 0.984 | - |
| mongo update | 3.685 | - |
| mongo command | 0.985 | - |

### mongo, e3-50

| | S (1) | S/G1 |
| --- | --- | --- |
| throughput | 1,584 | - |
| failed | 0 |  |
| p50 ms | 32.1 | - |
| p99 ms | 569 | - |
| node-1 CPU ms | 1.076 | - |
| node-1 KB allocated | 370 | - |
| kafka CPU ms | 0.315 | - |
| mongo CPU ms | 3.687 | - |
| mongo insert | 3.003 | - |
| mongo query | 0.903 | - |
| mongo update | 3.314 | - |
| mongo command | 0.911 | - |

### mongo, e3-75

| | S (1) | S/G1 |
| --- | --- | --- |
| throughput | 1,709 | - |
| failed | 40,068 |  |
| p50 ms | 1,491 | - |
| p99 ms | 3,162 | - |
| node-1 CPU ms | 0.972 | - |
| node-1 KB allocated | 371 | - |
| kafka CPU ms | 0.356 | - |
| mongo CPU ms | 5.177 | - |
| mongo insert | 3.055 | - |
| mongo query | 0.919 | - |
| mongo update | 3.321 | - |
| mongo command | 0.920 | - |

### mongo, e3-sweep

| | S (1) | S/G1 |
| --- | --- | --- |
| peak | 3,169 | - |
| peak at | 1,024 |  |
| peak is the top step | 1 |  |

### postgres, e1-25

| | S (1) | S/G1 |
| --- | --- | --- |
| throughput | 4,895 | - |
| failed | 24,350 |  |
| p50 ms | 27.1 | - |
| p99 ms | 2,097 | - |
| node-1 CPU ms | 0.501 | - |
| node-1 KB allocated | 109 | - |
| kafka CPU ms | 0.105 | - |
| postgres CPU ms | 0.483 | - |
| postgres xact_commit | 1.028 | - |
| postgres tup_fetched | 23.8 | - |
| postgres tup_inserted | 1.025 | - |
| postgres tup_updated | 1.715 | - |

### postgres, e1-50

| | S (1) | S/G1 |
| --- | --- | --- |
| throughput | 4,906 | - |
| failed | 341,775 |  |
| p50 ms | 791 | - |
| p99 ms | 1,786 | - |
| node-1 CPU ms | 0.702 | - |
| node-1 KB allocated | 159 | - |
| kafka CPU ms | 0.099 | - |
| postgres CPU ms | 0.485 | - |
| postgres xact_commit | 1.415 | - |
| postgres tup_fetched | 27.0 | - |
| postgres tup_inserted | 1.017 | - |
| postgres tup_updated | 0.571 | - |

### postgres, e1-75

| | S (1) | S/G1 |
| --- | --- | --- |
| throughput | 0.000 | - |
| failed | 954,180 |  |
| p50 ms | 0.000 | - |
| p99 ms | 0.000 | - |
| node-1 CPU ms | - | - |
| kafka CPU ms | - | - |
| postgres CPU ms | - | - |
| postgres xact_commit | - | - |
| postgres tup_fetched | - | - |
| postgres tup_inserted | - | - |
| postgres tup_updated | - | - |

### postgres, e1-aa

| | S (2) | S (3) | S/G1 |
| --- | --- | --- | --- |
| throughput | 10,602 | 10,602 | - |
| failed | 0 | 0 |  |
| p50 ms | 28.7 | 27.5 | - |
| p99 ms | 122 | 127 | - |
| node-1 CPU ms | 0.299 | 0.293 | - |
| node-1 KB allocated | 85.0 | 85.1 | - |
| kafka CPU ms | 0.047 | 0.047 | - |
| postgres CPU ms | 0.248 | 0.237 | - |
| postgres xact_commit | 0.347 | 0.394 | - |
| postgres tup_fetched | 2.214 | 2.255 | - |
| postgres tup_inserted | 0.985 | 0.983 | - |
| postgres tup_updated | 1.361 | 1.402 | - |

### postgres, e1-fixed

| | G1 (4) | G2 (4) | G2/G1 |
| --- | --- | --- | --- |
| throughput | 7,995 | 7,189 | 0.899 |
| failed | 4,148 | 52,490 |  |
| p50 ms | 17.8 | 20.4 | 1.144 |
| p99 ms | 594 | 1,925 | 3.241 |
| node-1 CPU ms | 0.346 | 0.349 | 1.010 |
| node-1 KB allocated | 83.7 | 88.9 | 1.062 |
| kafka CPU ms | 0.058 | 0.065 | 1.123 |
| postgres CPU ms | 0.224 | 0.249 | 1.112 |
| postgres xact_commit | 0.674 | 0.593 | 0.879 |
| postgres tup_fetched | 2.502 | 2.545 | 1.017 |
| postgres tup_inserted | 1.014 | 1.022 | 1.008 |
| postgres tup_updated | 1.704 | 1.629 | 0.956 |

### postgres, e1-hot-sweep

| | S (1) | S/G1 |
| --- | --- | --- |
| peak | 10,260 | - |
| peak at | 256 |  |
| peak is the top step | 0 |  |

### postgres, e1-sweep

| | G1 (4) | G2 (4) | S (1) | G2/G1 | S/G1 |
| --- | --- | --- | --- | --- | --- |
| peak | 16,127 | 19,536 | 21,204 | 1.211 | 1.315 |
| peak at | 1,024 | 1,024 | 1,024 |  |  |
| peak is the top step | 1 | 1 | 1 |  |  |

### postgres, e1-zipf-sweep

| | S (1) | S/G1 |
| --- | --- | --- |
| peak | 9,351 | - |
| peak at | 256 |  |
| peak is the top step | 0 |  |

### postgres, e3-25

| | S (1) | S/G1 |
| --- | --- | --- |
| throughput | 756 | - |
| failed | 20,479 |  |
| p50 ms | 606 | - |
| p99 ms | 9,437 | - |
| node-1 CPU ms | 2.335 | - |
| node-1 KB allocated | 565 | - |
| kafka CPU ms | 0.478 | - |
| postgres CPU ms | 2.268 | - |
| postgres xact_commit | 9.560 | - |
| postgres tup_fetched | 235 | - |
| postgres tup_inserted | 3.668 | - |
| postgres tup_updated | 4.934 | - |

### postgres, e3-50

| | S (1) | S/G1 |
| --- | --- | --- |
| throughput | 821 | - |
| failed | 82,470 |  |
| p50 ms | 2,720 | - |
| p99 ms | 6,062 | - |
| node-1 CPU ms | 2.208 | - |
| node-1 KB allocated | 504 | - |
| kafka CPU ms | 0.520 | - |
| postgres CPU ms | 2.340 | - |
| postgres xact_commit | 5.986 | - |
| postgres tup_fetched | 205 | - |
| postgres tup_inserted | 3.642 | - |
| postgres tup_updated | 2.359 | - |

### postgres, e3-75

| | S (1) | S/G1 |
| --- | --- | --- |
| throughput | 180 | - |
| failed | 186,736 |  |
| p50 ms | 3,850 | - |
| p99 ms | 9,044 | - |
| node-1 CPU ms | 6.141 | - |
| kafka CPU ms | 2.011 | - |
| postgres CPU ms | 6.094 | - |
| postgres xact_commit | 10.2 | - |
| postgres tup_fetched | 586 | - |
| postgres tup_inserted | 8.015 | - |
| postgres tup_updated | 2.990 | - |

### postgres, e3-sweep

| | S (1) | S/G1 |
| --- | --- | --- |
| peak | 4,390 | - |
| peak at | 1,024 |  |
| peak is the top step | 1 |  |

