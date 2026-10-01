Target `localhost:9090`, 30s per RPC, concurrency 50, 10 connections

| RPC | Requests | RPS | p50 (ms) | p99 (ms) | Non-OK |
|---|---:|---:|---:|---:|---:|
| SubmitScore | 237,664 | 7,922 | 5.90 | 13.43 | 50 |
| GetTopN | 555,566 | 18,519 | 2.28 | 8.25 | 50 |
| GetPlayerRank | 525,517 | 17,517 | 2.47 | 8.00 | 50 |
| GetPlayerStats | 613,579 | 20,453 | 1.97 | 8.38 | 50 |
