# ADR 0067 — Race predictor: FTP as an intensity correction on the bike leg

**Status:** Accepted · 2026-08-16 · bean `m4vq`

## Context

`RaceReadiness` projected the bike split from `recentBikeSpeed()` alone — the mean
`avg_speed` over rides of ≥ 1 h in the last 84 days. `ftp` was never read. Applying a
20-minute FTP test therefore could not move the projected bike split or finish time
(it moved `current_ctl` only, through the TSS recompute), which is what the user
reported: *"I enter FTP data into the test tab … it doesn't do anything with the race
predictor."*

Two ways to close that, with very different risk:

- **Model speed from FTP.** Needs CdA, Crr, mass, gradient. CdA alone varies 25–30%
  between riders on position, bike, helmet and clothing. That is a bet on constants we
  do not have, and it would *replace* a measurement the athlete actually produced with
  a guess.
- **Correct the measurement.** Observed speed already carries this athlete's CdA, Crr,
  mass and terrain, correctly, for free. Its real flaw is different: those rides were
  ridden at *training* intensity (typically 0.60–0.68 IF), while a 70.3 bike leg is
  ridden at ~0.78. Projecting from them raw reads systematically slow — and FTP is
  exactly the instrument that measures how much harder race day is.

The second framing is the one that uses FTP for what it actually knows.

## Decisions

1. **Speed stays the anchor; FTP only scales intensity.**

   ```
   v_race = v_observed × (P_race / P_observed)^(1/3)
   ```

   The **ratio form is the whole point**: CdA, Crr and mass cancel, so no physical
   constant is assumed anywhere. Only three numbers are needed, all already stored —
   `avg_speed`, `avg_power` on the qualifying rides, and `ftp`.

2. **Cube root, deliberately conservative.** Cube root is the pure-aerodynamic
   relationship. Real power also carries a rolling term linear in `v`, so a given power
   increase buys slightly *more* speed than this predicts. The projection therefore errs
   slow, never fast — the right direction for a cut-off check.

3. **Race intensity by distance:** 0.78 × FTP for 70.3, 0.70 × FTP for 140.6.

4. **`P_observed` is AVERAGE power, never normalized.** An earlier draft preferred
   `weightedPower`; that was wrong and Copilot caught it on #117. NP is a
   *physiological load* metric, not the mean mechanical power that produced
   `avg_speed`, and `NP ≥ AP` always. The relation `P = a·v³ + b·v` is defined on
   means, so pairing `avg_speed` with NP understates `P_race / P_observed` — and on
   a variable ride (160 W average, 220 W normalized) it can **flip the correction**
   from scaling up to scaling down. Coasting dilutes `avg_speed` and `avg_power`
   alike, so they remain a consistent pair. A ride carrying only NP sits out the
   correction rather than being mis-scaled.

   The mirror-image assumption is on the race side and is worth stating plainly:
   `RACE_IF` is quoted as *normalized* power (IF ≡ NP/FTP) but compared against an
   average. That holds only because a well-paced race bike leg is ridden near-steady
   (VI ≈ 1.0). It is an assumption about **pacing**, and it degrades in one
   direction — a rider surging over a hilly course has VI > 1, making their true
   average lower than `RACE_IF × FTP` and the projection optimistic.

5. **The correction is computed over the power-bearing subset only.** Mixing a
   powerless ride's speed into `v_observed` would pair it with a power it never had.
   Rides without power still count toward the uncorrected fallback mean.

6. **Graceful degradation, no FTP-only fallback.** No FTP, or no ride carries power →
   the previous uncorrected mean, unchanged. No qualifying rides at all → the leg stays
   missing. We deliberately did **not** add an absolute FTP→speed model for the
   no-history case: that is precisely the case that needs the guessed constants, and
   also the case where we know least about the rider.

7. **The projection states what it stands on.** The bike leg carries
   `basis: "measured_speed" | "measured_speed_ftp_scaled"`, the `note` names the race
   intensity used, and the card renders a caption. An unscaled split is a *training-pace*
   projection; the user should know that before planning a race around it.

8. **Scale clamped to [0.85, 1.25].** Named ceiling, not a hidden magic number: the
   cube-root scaling is only sound while race power is near the observed range, and a
   projection claiming a 30%-faster ride than anything ever recorded is not credible
   whatever the arithmetic says. Clamped rather than dropped, so a rider who genuinely
   only trains easy still gets a capped correction. Widen it if calibration against real
   race files shows the cap biting on honest data.

## Consequences

- Applying an FTP test now visibly moves the bike split, the projected finish and the
  cut-off margins — the behaviour the user expected.
- Athletes who train easy will see their projected finish get **faster**; this is a
  correction of a known pessimistic bias, not an optimism knob. Anyone whose long rides
  are harder than race intensity sees the split slow down (covered by test).
- Needs power on the long rides. The dedup source-preference already selects for it:
  `Dedup.scoreComparator` ranks Bike as `isBikeComputer > hasPower > hasHr > length`, so
  the Garmin Edge copy wins any same-event cluster, and `RaceReadinessResource` reads
  only non-duplicates. A *solo* Apple Watch ride (no Edge copy, no cluster) can still
  lack power and simply sits out the correction. **No change to workout capture.**
- `recentBikeSpeed` changed signature (`List<Activity>` → `+ Thresholds, distance`) and
  now returns a `BikeSpeed` record rather than a `Double`. Package-private, one caller.
- The `RACE_IF` constants and the clamp are the calibration knobs. They are physical
  assumptions about a real athlete on real roads, and they should be tuned against
  actual race files rather than left at their coaching-convention defaults forever.

## Alternatives rejected

- **Absolute FTP→speed model** — needs CdA/Crr guesses; would override a real
  measurement with a modelled one. Rejected (see Context).
- **Applying the same treatment to the run leg** — `threshold_pace_run × 1.10` is
  already a measured pace with an empirical off-the-bike penalty. Power adds no
  information there.
- **Auto-seeding `Athlete.ftp` from `daily_recovery.cycling_ftp_w`** (Apple's own FTP
  estimate, already ingested) — out of scope and still correctly parked at
  `HealthResource.ingest` / bean `30m8`. (An earlier revision of this ADR cited
  `mg1n`, copying a stale code comment; `mg1n` shipped the capture — `30m8` is the
  open auto-seed follow-up split out of it.) Note that if it lands, three sources write
  `Athlete.ftp` (test-applied, manual edit, Apple estimate) with no precedence rule;
  that policy should be settled before adding the third writer.

## Verification

`RaceReadinessTest` — 10 new cases: raw mean without FTP; exact cube-root scaling;
harder-than-race rides scale **down** (guards an inverted-ratio slip); normalized power
is ignored in favour of average and must still scale **up** (guards the decision-4
regression directly); a ride carrying only NP sits out the correction; powerless rides
excluded; 140.6 uses 0.70; absurd ratios clamp; no qualifying rides → null; bike leg
reports its basis and the note names the intensity.

Full backend suite **265 passed / 0 failed**. Frontend typechecks and builds.
