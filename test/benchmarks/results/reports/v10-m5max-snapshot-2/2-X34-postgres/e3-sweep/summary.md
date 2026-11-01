# Closed-loop Saturation: E3 (uniform)

- **Peak Throughput**: 6107.1/s
- **Peak Concurrency**: 256

| Concurrency | Throughput (/s) | p50 (ms) | p99 (ms) | OK | Errors |
| ---: | ---: | ---: | ---: | ---: | :--- |
| 1 | 334.9 | 2.96 | 5.28 | 10046 | {} |
| 4 | 875.4 | 4.25 | 9.02 | 26262 | {} |
| 16 | 1794.0 | 8.19 | 19.07 | 53820 | {} |
| 64 | 3733.6 | 16.64 | 32.26 | 112009 | {} |
| 256 | 6107.1 | 38.91 | 72.19 | 183214 | {'http-503-wow.command.unknown': 55, 'http-504-wow.wait.timeout': 33} |
| 1024 | 3081.0 | 82.43 | 700.41 | 92430 | {'http-503-wow.command.unknown': 4764, 'http-504-wow.wait.timeout': 1835, 'http-409-wow.command.contended': 4} |
