**PRELIMINARY** (V10 `4251e3463`, before R3-A, R3-B and P.16; to be rerun): see [`../v10-counts-e1-e3.md`](../v10-counts-e1-e3.md).

### E1

| Kind | 9.x MongoDB | V10 MongoDB | V10 PostgreSQL |
| --- | ---: | ---: | ---: |
| request-id check |  | 1.00 | 1.00 |
| aggregate load | 2.00 |  |  |
| log append | 1.00 | 4.00 | 1.00 |
| snapshot | 1.00 | 1.00 | 1.00 |
| tail |  | 0.57 | 0.51 |
| checkpoint |  | 0.05 | 0.06 |
| lease |  | 0.03 | -0.01 |
| **Round trips** | 4.00 | 6.64 | 3.56 |
| Store operations | 4.00 | 13.49 | 10.37 |
| Kafka messages | 3.00 | 1.00 | 1.00 |
| Allocated KiB | 391.60 | 386.50 | 126.40 |

### E2

| Kind | 9.x MongoDB | V10 MongoDB | V10 PostgreSQL |
| --- | ---: | ---: | ---: |
| request-id check |  | 1.00 | 1.00 |
| aggregate load | 2.00 |  |  |
| log append | 1.00 | 4.00 | 1.00 |
| snapshot | 1.00 | 1.00 | 1.00 |
| state read | 1.00 | 1.00 | 1.00 |
| tail |  | 0.58 | 0.52 |
| checkpoint |  | 0.05 | 0.06 |
| lease |  | -0.02 | -0.01 |
| other periodic |  | -0.01 |  |
| **Round trips** | 5.00 | 7.61 | 4.57 |
| Store operations | 5.00 | 14.41 | 11.42 |
| Kafka messages | 3.00 | 1.00 | 1.00 |
| Allocated KiB | 461.90 | 446.30 | 159.80 |

### E3

| Kind | 9.x MongoDB | V10 MongoDB | V10 PostgreSQL |
| --- | ---: | ---: | ---: |
| request-id check |  | 3.00 | 3.00 |
| aggregate load | 6.00 |  |  |
| log append | 3.00 | 12.00 | 3.00 |
| snapshot | 3.00 | 3.00 | 3.00 |
| tail |  | 2.13 | 2.09 |
| checkpoint |  | 0.05 | 0.06 |
| lease |  | -0.01 | -0.01 |
| **Round trips** | 12.00 | 20.18 | 11.14 |
| Store operations | 12.00 | 32.05 | 22.95 |
| Kafka messages | 9.00 | 3.00 | 3.00 |
| Allocated KiB | 1010.00 | 1055.00 | 319.70 |

