# November 2025 Checkout Connection Starvation

## Impact

Checkout requests failed and queued while order creation waited for a database session. The customer-facing failure ratio stayed above one tenth for roughly seven minutes.

## Root Cause

A reconciliation workload opened too many concurrent transactions against the same database used by checkout. All forty available connections were occupied and more than twenty request threads waited. Application logs repeatedly reported that acquiring a connection had timed out.

## Response and Learning

Operators paused the optional reconciliation workload, which released database capacity and allowed online orders to recover. The incident was escalated only after dashboard saturation and timeout log evidence agreed. Follow-up work isolated background-job concurrency and added alerts for waiting borrowers.
