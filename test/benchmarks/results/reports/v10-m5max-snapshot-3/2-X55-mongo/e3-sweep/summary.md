# Closed-loop Saturation: E3 (uniform)

- **Peak Throughput**: 506.0/s
- **Peak Concurrency**: 16

| Concurrency | Throughput (/s) | p50 (ms) | p99 (ms) | OK | Errors |
| ---: | ---: | ---: | ---: | ---: | :--- |
| 1 | 69.8 | 11.52 | 26.50 | 2094 | {} |
| 4 | 296.1 | 12.10 | 36.61 | 8882 | {} |
| 16 | 506.0 | 21.76 | 184.32 | 15181 | {} |
| 64 | 398.5 | 58.88 | 831.49 | 11954 | {} |
| 256 | 341.2 | 403.45 | 3899.39 | 10235 | {'http-503-wow.command.unknown': 22, 'http-504-wow.wait.timeout': 71} |
| 1024 | 289.1 | 1564.67 | 9437.18 | 8673 | {'http-503-wow.command.unknown': 297, 'http-504-wow.wait.timeout': 1161} |
