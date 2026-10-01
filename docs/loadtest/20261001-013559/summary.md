Target `localhost:9090`, 30s per RPC, concurrency 50, 10 connections

| RPC | Requests | RPS | p50 (ms) | p99 (ms) | Non-OK |
|---|---:|---:|---:|---:|---:|
| SubmitScore | 243,438 | 8,115 | 5.77 | 12.92 | 45 |
| GetTopN | 561,711 | 18,724 | 2.24 | 8.39 | 46 |
| GetPlayerRank | 533,481 | 17,783 | 2.41 | 8.08 | 49 |
| GetPlayerStats | 610,166 | 20,339 | 1.98 | 8.36 | 49 |
