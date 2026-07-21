# 0048 — Native HealthKit ingestion: authorization & entitlements foundation (2026-07-21)

Date: 2026-07-21
Beans: iron-trainer-pql8 (this task) · epic iron-trainer-2f2c (retire Health Auto Export)
Related: ADR 0045/0046 (HAE server + metric mapping — the pipeline this replaces) ·
docs/research/native-healthkit-ingestion.md (the research this implements)

## Context

Recovery data (sleep stages, HRV, resting HR, …) currently reaches the backend
through the third-party **Health Auto Export** (HAE) app: the athlete configures a
REST automation that POSTs to `/api/health/ingest`. HAE costs $25 (Premium
paywall), needs manual per-metric setup, and delivers on its own schedule with no
guarantee. Research (docs/research/native-healthkit-ingestion.md) concluded we
should read HealthKit directly from our own now-shipping iOS app: ~500–800 LOC of
Swift, **zero backend changes** (emit the same HAE-shaped payload), equal-or-better
delivery. Epic `2f2c` decomposes that into v1 modules; this ADR covers the first
task — the authorization + entitlements foundation everything else builds on.

## Decision

**1. Reuse the existing HealthKit entitlement; add only background-delivery.**
The app already holds `com.apple.developer.healthkit` and links `HealthKit.framework`
(for WorkoutKit scheduling). We add `com.apple.developer.healthkit.background-delivery`
now — even though the observer/background work lands in a later task (`n1zt`) — so
automatic provisioning registers the capability once rather than re-provisioning
mid-epic (the widget PR showed automatic signing with `-allowProvisioningUpdates`
handles new entitlements cleanly).

**2. One `HealthKitAuthorizer`, read-only, requesting all recovery types up front.**
A single `requestAuthorization(toShare: [], read:)` for the whole v1 read set
(sleepAnalysis, HRV SDNN, restingHeartRate, respiratoryRate, sleeping wrist
temperature, bodyMass, VO₂ max). One sheet, no drip-feed of prompts. The app writes
nothing to Health (workout scheduling uses WorkoutKit, not HK writes), so the share
set is empty.

**3. Never gate on authorization status.** iOS read authorization is opaque —
`authorizationStatus` reflects only *write* grants, so a denied read is
indistinguishable from "no data yet". We therefore treat status as unknowable: the
authorizer exposes only whether we've *asked* (a `UserDefaults` flag driving the
button label), and later readers surface an empty state pointing at
**Health → Sharing** rather than testing status. This is the single most important
constraint from the research and the most common way HealthKit integrations get it
wrong.

**4. In-context entry point in Settings, alongside HAE during migration.** A new
"Apple Health" card requests access on tap; the existing HAE token section stays
(re-labelled "Health Auto Export (legacy)") so the two run in parallel through the
2–4 week overlap — the backend upserts dedupe double-ingest. HAE is removed in a
later task (`ltfs`) once native parity is verified.

**5. Broadened the read usage string.** `NSHealthShareUsageDescription` now names the
recovery metrics + readiness purpose (it previously described only workout building),
as App Review requires the string to match actual reads.

## Scope of this task (pql8)

Foundation only — this PR grants access and provisions the capability; it does **not**
yet read or ingest anything. Reading (anchored queries, anchors, HKDeletedObjects),
night assembly, and delivery are the following tasks (`aw8t`, `90iu`, `n1zt`). So the
"Connect Apple Health" button is a real permission grant with no data flow yet;
recovery data continues to arrive via HAE until `n1zt` ships.

## Consequences

- **Device-only verification.** The permission sheet renders in the simulator, but
  real data + background delivery need a device — verified via TestFlight, like the
  widget. Simulator build is the CI gate (`BUILD SUCCEEDED`).
- **App Review prerequisites now due** (before ingestion ships, not this PR): a
  privacy policy covering health data (Guideline 5.1.3) and an App Privacy label
  (Health & Fitness, linked to identity, no tracking). Tracked on the epic.
- **HRV stays empty without an Apple Watch** — Garmin doesn't write HRV to Health.
  The card says so; not a regression vs HAE (same underlying source data).

## Alternatives considered

- *Request per-metric, lazily* — rejected: multiple sheets, worse UX, no benefit
  since all types feed the same readiness call.
- *Gate the card on `authorizationStatus`* — rejected: status is write-only and would
  make a granted-read look denied. The whole design leans on never gating.
- *Drop HAE immediately* — rejected: no native reader exists yet; parallel run with
  backend dedupe is the safe migration.
