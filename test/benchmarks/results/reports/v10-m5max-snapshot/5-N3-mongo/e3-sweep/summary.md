# Closed-loop Saturation: E3 (uniform)

- **Peak Throughput**: 1565.1/s
- **Peak Concurrency**: 64

| Concurrency | Throughput (/s) | p50 (ms) | p99 (ms) | OK | Errors |
| ---: | ---: | ---: | ---: | ---: | :--- |
| 1 | 1.2 | 12.10 | 2621.86 | 36 | {} |
| 4 | 540.1 | 6.27 | 20.09 | 16203 | {} |
| 16 | 1210.9 | 10.62 | 46.59 | 36328 | {} |
| 64 | 1565.1 | 21.25 | 240.64 | 46952 | {} |
| 256 | 1219.4 | 34.30 | 1982.46 | 36581 | {'http-503-wow.command.unknown': 55, 'http-504-wow.wait.timeout': 122} |
| 1024 | 614.0 | 58.11 | 8290.30 | 18421 | {'http-503-wow.command.unknown': 315, 'http-504-wow.wait.timeout': 1057} |
