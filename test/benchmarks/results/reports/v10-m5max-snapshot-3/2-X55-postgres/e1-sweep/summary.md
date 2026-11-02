# Closed-loop Saturation: E1 (uniform)

- **Peak Throughput**: 15293.8/s
- **Peak Concurrency**: 256

| Concurrency | Throughput (/s) | p50 (ms) | p99 (ms) | OK | Errors |
| ---: | ---: | ---: | ---: | ---: | :--- |
| 1 | 841.9 | 1.14 | 2.21 | 25257 | {} |
| 4 | 2261.1 | 1.69 | 3.49 | 67833 | {} |
| 16 | 4727.1 | 3.23 | 6.94 | 141814 | {} |
| 64 | 7780.4 | 6.94 | 20.22 | 233412 | {} |
| 256 | 15293.8 | 16.38 | 30.46 | 458813 | {} |
| 1024 | 6095.3 | 22.66 | 54.53 | 182858 | {'http-503-wow.command.unknown': 2151, 'http-504-wow.wait.timeout': 1136} |
