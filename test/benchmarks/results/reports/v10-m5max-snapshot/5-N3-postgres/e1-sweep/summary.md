# Closed-loop Saturation: E1 (uniform)

- **Peak Throughput**: 15039.4/s
- **Peak Concurrency**: 256

| Concurrency | Throughput (/s) | p50 (ms) | p99 (ms) | OK | Errors |
| ---: | ---: | ---: | ---: | ---: | :--- |
| 1 | 898.3 | 1.09 | 1.81 | 26949 | {} |
| 4 | 2268.9 | 1.69 | 3.42 | 68067 | {} |
| 16 | 4552.5 | 3.36 | 7.26 | 136575 | {} |
| 64 | 7863.8 | 7.04 | 18.30 | 235915 | {} |
| 256 | 15039.4 | 16.51 | 33.79 | 451182 | {} |
| 1024 | 5864.3 | 28.41 | 2146.30 | 175929 | {'http-503-wow.command.unknown': 3028, 'http-504-wow.wait.timeout': 667, 'http-409-wow.command.contended': 7} |
