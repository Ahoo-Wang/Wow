# Closed-loop Saturation: E3 (uniform)

- **Peak Throughput**: 663.1/s
- **Peak Concurrency**: 16

| Concurrency | Throughput (/s) | p50 (ms) | p99 (ms) | OK | Errors |
| ---: | ---: | ---: | ---: | ---: | :--- |
| 1 | 71.2 | 11.39 | 26.62 | 2137 | {} |
| 4 | 353.6 | 10.43 | 29.31 | 10609 | {} |
| 16 | 663.1 | 16.13 | 105.47 | 19892 | {} |
| 64 | 464.6 | 44.54 | 696.32 | 13939 | {} |
| 256 | 421.8 | 344.06 | 3375.10 | 12655 | {'http-503-wow.command.unknown': 26, 'http-504-wow.wait.timeout': 74} |
| 1024 | 347.6 | 290.81 | 9371.65 | 10427 | {'http-503-wow.command.unknown': 199, 'http-504-wow.wait.timeout': 1388, 'http-409-wow.command.contended': 3} |
