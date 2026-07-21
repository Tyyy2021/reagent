# Product Search Latency Runbook

## Symptoms

Product search responses exceed the latency objective while catalog browsing remains available. Inspect search request percentiles, query complexity, cache hit rate, and shard fan-out.

## Evidence

Compare slow traces with search-cluster CPU, heap pressure, rejected queries, and cache misses. Validate whether a recent synonym update or index refresh changed query execution.

## Mitigation

Disable an expensive search feature flag, reduce broad wildcard queries, or roll back a faulty synonym configuration. Escalate to the search owner when multiple signals identify the search cluster as the bottleneck.
