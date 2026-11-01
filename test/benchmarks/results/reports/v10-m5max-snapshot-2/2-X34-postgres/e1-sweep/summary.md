# Closed-loop Saturation: E1 (uniform)

- **Peak Throughput**: 16679.2/s
- **Peak Concurrency**: 256

| Concurrency | Throughput (/s) | p50 (ms) | p99 (ms) | OK | Errors |
| ---: | ---: | ---: | ---: | ---: | :--- |
| 1 | 808.5 | 1.14 | 2.21 | 24254 | {} |
| 4 | 2313.1 | 1.68 | 3.07 | 69392 | {} |
| 16 | 4764.2 | 3.21 | 6.75 | 142925 | {} |
| 64 | 9086.2 | 6.17 | 14.08 | 272586 | {} |
| 256 | 16679.2 | 14.97 | 28.80 | 500375 | {} |
| 1024 | 7814.6 | 40.70 | 1146.88 | 234438 | {'http-503-wow.command.unknown': 4536, 'http-504-wow.wait.timeout': 408, 'http-409-wow.command.contended': 2} |
