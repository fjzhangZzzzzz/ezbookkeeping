# Family and search performance baseline

This benchmark measures the real Go service paths used by the Android client:

- `services.Family.Overview` for the family overview page.
- `services.Transactions.GetTransactionSearchSummary` for the search summary.

The harness lives at `scripts/perf`. Each run creates a temporary SQLite
database, seeds synthetic CNY-only data, prints JSON, and removes the database
when the process exits. It never reads the configured application database.

## Acceptance gates

These are the initial gates from the family review and remain targets to be
confirmed by measurement:

- ordinary interaction p95: at most 2 seconds;
- large time-span statistics p95: at most 5 seconds;
- zero operation errors and no timeout under the selected concurrency test;
- Android first render and cached-return timings must be measured separately on
  a device; server timings alone do not prove Android UI performance.

## Matrix

Run the following data points before changing query or index code:

| Members | Transactions/member | Span | Cache | Concurrency |
| ---: | ---: | ---: | --- | ---: |
| 2, 5, 10 | 10,000 | 31 days | cold/warm | 1 |
| 2, 5, 10 | 10,000 | 366 days | cold/warm | 1 |
| 10 | 100,000 | 31 days | cold/warm | 1 and 10 |
| 10 | 100,000 | 366 days | cold/warm | 1 and 10 |

The harness warmup represents a warm process/database path. A new process and
temporary database represent the cold setup; database creation/seeding is not
included in operation latency. The Android layer still requires a cold-start
and warm-return trace on a QA account with representative data.

## Reproduction

The repository's Windows environment requires CGO for SQLite. From Git Bash or
another shell with LLVM available:

```bash
export CGO_ENABLED=1
export CC=clang
export GOCACHE=/tmp/ezbookkeeping-perf-gocache
go run ./scripts/perf \
  -members=10 \
  -transactions-per-member=10000 \
  -span=31 \
  -runs=10 \
  -warmup=1 \
  -concurrency=1 \
  -out=docs/family-review/perf-10x10k-31d.json
```

Use `-mode=family` or `-mode=search` to isolate one path. Use
`-concurrency=10` for the concurrent-read check. `-keyword` measures the
keyword-filtered search path; the empty default measures the full search scope.

The JSON report records p50/p95/p99/max in milliseconds, error count, total
allocation delta, dataset size, span, runtime, operating system, architecture,
and CPU count. It is a comparison artifact, not a production capacity claim.

## Latest isolated result

Measured on Windows amd64, Go 1.27.4, 16 logical CPUs, temporary SQLite, after
the family projection query and covering index were added:

| Dataset | Mode | Family overview p95 | Search summary p95 | Errors |
| --- | --- | ---: | ---: | ---: |
| 10 × 10,000, 31 days | single request | 53 ms | 20 ms | 0 |
| 10 × 10,000, 31 days | 10 concurrent | 1,972 ms | 226 ms | 0 |
| 10 × 100,000, 31 days | single request | 940 ms | 641 ms | 0 |
| 10 × 100,000, 31 days | 10 concurrent | 7,605 ms | 6,570 ms | 0 |
| 10 × 100,000, 366 days | single request | 626 ms | 486 ms | 0 |

The single-request interaction and large-span gates pass in this isolated
SQLite run. The concurrent million-row run is stable with zero errors, but its
latency is above the single-request gates because the SQLite deployment is
intentionally configured with one open connection. This is a concurrency
stability result, not a claim that ten simultaneous million-row requests are
interactive. SQLite data files and connection settings matching the release
environment, plus Android emulator traces, remain required before release
capacity is declared. MySQL/PostgreSQL are not support or acceptance targets
for this project.

SQLite `EXPLAIN QUERY PLAN` confirms the family query uses
`COVERING INDEX IDX_transaction_uid_deleted_time_family`; grouping still uses a
temporary B-tree. The same plan must be captured against the release SQLite
configuration before declaring release capacity.

## Interpretation

Do not optimize from one result. Compare p95 across the full matrix and capture
the SQL query plan for any candidate index or aggregation change. For every
optimization, rerun the same matrix, rerun `go test ./...` and `go vet ./...`,
then verify that returned totals and search semantics are unchanged.
