---
# iron-trainer-troh
title: 'backend-v2: batch metrics_daily writes (apply is 28s over the pooler)'
status: in-progress
type: bug
priority: high
created_at: 2026-07-18T16:30:24Z
updated_at: 2026-07-18T16:32:47Z
---

recomputeAndRebuild/storeMetrics inserts one metrics_daily row per calendar day (1000+ over multi-year history) with NO Hibernate batching → ~1000 INSERT round-trips over the Supabase pooler → 28s apply, client 499s. Observed in prod after the gb30 write-flip via the request-log filter (POST /api/tests/result/{id}/apply -> 200 (28146ms)). Fix: enable quarkus.hibernate-orm.jdbc.statement-batch-size + order-inserts/updates + reWriteBatchedInserts on the PG driver (MetricDaily has a composite natural key, so inserts ARE batchable). Config-only, parity-safe (identical rows). Matches FastAPI's s.add_all bulk write.

## Fix

application.properties: quarkus.hibernate-orm.jdbc.statement-batch-size=500 +
quarkus.datasource.jdbc.additional-jdbc-properties.reWriteBatchedInserts=true.
Turns the ~1000 per-day INSERTs (and per-activity TSS UPDATEs) in the apply/
rebuild cascade into a handful of batched multi-row statements. Config-only;
output identical (pmc_parity compares the full rebuilt series → still byte-equal).
98 backend-v2 + 57 parity green. Perf win is prod-only (pooler latency); will
verify via the request-log filter apply timing after deploy.
