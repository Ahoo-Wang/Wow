# Closed-loop Saturation: E3 (uniform)

- **Peak Throughput**: 5562.8/s
- **Peak Concurrency**: 256

| Concurrency | Throughput (/s) | p50 (ms) | p99 (ms) | OK | Errors |
| ---: | ---: | ---: | ---: | ---: | :--- |
| 1 | 298.9 | 3.31 | 6.08 | 8966 | {} |
| 4 | 836.1 | 4.48 | 9.54 | 25084 | {} |
| 16 | 1668.7 | 8.64 | 21.38 | 50060 | {} |
| 64 | 3301.6 | 17.92 | 44.29 | 99049 | {} |
| 256 | 5562.8 | 41.73 | 77.31 | 166885 | {'http-503-wow.command.unknown': 67, 'http-504-wow.wait.timeout': 57} |
| 1024 | 2195.8 | 45.05 | 1654.78 | 65874 | {'http-503-wow.command.unknown': 1299, 'http-504-wow.wait.timeout': 1114, 'http-409-wow.command.contended': 11} |
