# Closed-loop Saturation: E3 (uniform)

- **Peak Throughput**: 5621.7/s
- **Peak Concurrency**: 256

| Concurrency | Throughput (/s) | p50 (ms) | p99 (ms) | OK | Errors |
| ---: | ---: | ---: | ---: | ---: | :--- |
| 1 | 313.5 | 3.17 | 5.41 | 9406 | {} |
| 4 | 844.1 | 4.42 | 9.21 | 25322 | {} |
| 16 | 1655.6 | 8.70 | 21.50 | 49667 | {} |
| 64 | 3453.0 | 17.79 | 37.38 | 103591 | {} |
| 256 | 5621.7 | 40.45 | 79.87 | 168652 | {'http-503-wow.command.unknown': 58, 'http-504-wow.wait.timeout': 63} |
| 1024 | 2619.4 | 71.68 | 1130.49 | 78583 | {'http-503-wow.command.unknown': 2734, 'http-504-wow.wait.timeout': 1467, 'http-409-wow.command.contended': 9} |
