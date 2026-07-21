# Kafka Consumer Lag Runbook

## Symptoms

Event processing falls behind producers and Kafka consumer-group lag rises across consecutive polling intervals. Downstream projections may become stale even though producers continue publishing.

## Evidence

Inspect lag by partition, consumer rebalances, poll duration, broker health, and dead-letter volume. Determine whether one hot partition, slow handler, or repeated poison message explains the backlog.

## Mitigation

Quarantine poison messages, restore healthy consumers, or add consumers when partition count permits useful parallelism. Confirm offsets advance and lag declines before declaring recovery.
