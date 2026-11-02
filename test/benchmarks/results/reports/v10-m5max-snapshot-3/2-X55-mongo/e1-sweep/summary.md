# Closed-loop Saturation: E1 (uniform)

- **Peak Throughput**: 11886.2/s
- **Peak Concurrency**: 256

| Concurrency | Throughput (/s) | p50 (ms) | p99 (ms) | OK | Errors |
| ---: | ---: | ---: | ---: | ---: | :--- |
| 1 | 326.3 | 2.94 | 6.50 | 9790 | {} |
| 4 | 1276.5 | 2.62 | 7.46 | 38294 | {} |
| 16 | 3250.7 | 4.45 | 11.58 | 97522 | {} |
| 64 | 6957.7 | 8.26 | 20.86 | 208732 | {} |
| 256 | 11886.2 | 20.09 | 48.38 | 356586 | {} |
| 1024 | 3281.4 | 19.07 | 5406.72 | 98442 | {'http-503-wow.command.unknown': 2318, 'http-504-wow.wait.timeout': 947, 'http-409-wow.command.contended': 1} |
