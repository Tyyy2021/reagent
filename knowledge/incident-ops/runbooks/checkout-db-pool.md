# Checkout Database Pool Exhaustion Runbook

## Trigger and Symptoms

Use this runbook when the checkout service error rate is greater than 10% for five minutes. Confirm that the database connection pool reports active 40/40 and pending requests above 20. Customers may see checkout failures or stalled payment confirmation.

## Required Evidence

Corroborate the metrics with application logs containing connection acquisition timeout errors. Check pool active, pool pending, checkout error rate, database health, and recent deployment or batch activity. Create a P1 incident only when the metrics and timeout logs corroborate each other; a single noisy signal is not sufficient.

## Immediate Mitigation

Stop nonessential batch traffic that competes for checkout database connections. Do not blindly raise the pool limit. Preserve evidence, page the checkout owner, and monitor active and pending connections while traffic drains.

## Recovery

Confirm pending requests return below 20, pool utilization has headroom below 40/40, timeout logs stop, and the checkout error rate recovers. Record the causal batch workload and follow up on connection lifetime and query latency.
