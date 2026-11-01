# Closed-loop Saturation: E1 (uniform)

- **Peak Throughput**: 12717.2/s
- **Peak Concurrency**: 256

| Concurrency | Throughput (/s) | p50 (ms) | p99 (ms) | OK | Errors |
| ---: | ---: | ---: | ---: | ---: | :--- |
| 1 | 270.6 | 3.26 | 7.74 | 8117 | {} |
| 4 | 1446.8 | 2.27 | 6.88 | 43404 | {} |
| 16 | 3645.2 | 4.05 | 10.30 | 109356 | {} |
| 64 | 7606.4 | 7.55 | 19.20 | 228193 | {} |
| 256 | 12717.2 | 18.82 | 44.80 | 381517 | {} |
| 1024 | 3302.7 | 20.73 | 5931.01 | 99081 | {'http-503-wow.command.unknown': 1742, 'http-504-wow.wait.timeout': 351} |
