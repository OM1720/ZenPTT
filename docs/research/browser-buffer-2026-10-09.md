# Browser capture completion and playback startup buffer

Status: study completed on 9 October 2026; the human accepted 150 ms afterward.

The study ended with a 100 ms working default; the subsequent human decision
sets the browser default to 150 ms as recorded below. This investigation
concerns the browser client only. It does not change Android, the wire protocol, ACKs, lease,
bitrate, recovery horizon, release metadata, or the installed server package.
No physical listening is included, so acoustic quality remains unverified.

## Reproducibility and scope

The initial checkout was clean at
`c6d78f15e19d73b8323989b2adc2d9363ff9c4a1` (`v0.10.0`). The test host's installed
ZIP was independently verified as
`cbbb27e8e1c075371fc90ce44d6f2c299c9eb065d26e24c6b665aa279f5c39fe`.
Experimental browser assets have separate source and file manifests; they
are not claimed to be the browser assets in that installed ZIP.

Raw evidence, experimental sources/builds, original `web/dist`, and the stage
handoff are kept under ignored
`acceptance/artifacts/poor-link/browser-buffer-20261008T104706Z/`.
Operator connection settings remain private. Reproduction commands and the
measurement contract are documented in [TESTING.md](../TESTING.md).
The earlier short-series context is in the
[7 October poor-link report](poor-link-2026-10-07.md).

The signal is the deterministic browser fake-microphone fixture, through real
capture, Opus, the server, decoding, and AudioWorklet rendering. These results
must not be combined with the earlier LibriSpeech speech matrix.

## Capture completion investigation

The observed lifecycle is PTT release, `stopCapture`, worklet `stop`, `stopped`,
`captureEnded`, pending media flush, `burst_end`, sender terminal reply, and
resource cleanup. Journals distinguish request and burst IDs, transport
generation, worklet instance and playback epoch. Render timestamps are kept
separately from main-thread receipt times.

Twenty historical repeats retained the earlier short-scenario method and
heavy Playwright tracing:

| Scenario | Passed | Failed | Maximum packet callback delay in failed attempts |
|---|---:|---:|---:|
| unstable, seed 2017, extra prebuffer 0 ms | 6/10 | 4/10 | 17.8–30.9 s |
| unstable, seed 3037, extra prebuffer 100 ms | 10/10 | 0/10 | Not applicable |

Passed here means the browser scenario completed successfully, not that
acoustic quality or all PCM quality criteria were established.

The extra prebuffer in these historical cases precedes worklet commands; it
is not the real startup-buffer variable used in the new comparison. One
separate short 2017 repeat with PCM retained and Playwright tracing disabled
passed, with maximum packet callback delays of 69.3 ms and 24.0 ms on the two
pages. This supports an observer-interference explanation for reproduced
failures, but does not establish the causes of both original failed attempts.

The original `unstable-2017-pre0` has no saved browser lifecycle journal.
Shared Playwright trace paths mentioned in old stdout are not preserved as
attempt-owned trace archives. The missing original event chain remains an
explicit evidence gap; successful retries do not remove it.

In the saved original `unstable-3037-pre100` reverse-direction trace, 970 played
positions consist of 849 received frames and 121 concealed positions. They
are not duplicate playback: played sequence positions are unique and ordered.
The sender recorded 944 unique sent positions. The exact transition that
preceded the old Disconnect timeout remains unproven. A missing Disconnect
button after entering an error state alone does not demonstrate a UI hang.

No production stop-timeout increase is justified by this evidence. Local
regressions cover delayed, absent, duplicate and stale acknowledgements,
release before capture starts, context errors, disconnect, transport changes,
and late callbacks after cleanup.

## Confirmed measurement defects

An early release in the new timed test left an old scheduled stop able to
release the following capture. A real-browser regression in an ordinary
channel reproduced a 618.667 ms second capture against a 1000 ms requirement.
It passed after cancellation was tied to matching stop/expiry, superseding
capture, and context closure. The timer also rechecks the render-clock deadline.
This is a harness correction, not a production-client timeout change.

The first long baseline pilot delivered all six bursts, but transferring
large structured journal arrays through Playwright inflated inter-send
pauses. It is preserved under `matrix/` and excluded from comparison.
Checkpoints now transfer packed JSON strings, no journal copy is awaited
between sends, and the two-second pause is measured from the previous sender
terminal event. Actual pauses remain part of the evidence. Revised candidates
and attempts use a separate directory.

## Comparison contract

P100, P150 and P200 differ only in the worklet startup-delay constant. The
separate 100 ms grant-cue capture delay is unchanged. Startup is measured by
the rendering clock once per burst; a temporary underrun does not add another
startup delay. Each direction plans three 20-second captures (1000 frames
each), followed by up to 30 seconds to drain after its final send.

The main matrix plans 45 runs, 90 directional series and 270 bursts. Six
additional runs use PCM observation and remain separate. All cases run
sequentially. Proxy A retains the configured impairment in both directions.
Proxy egress shapes connections on a TCP proxy path; it is not a direct model
of a bot's outgoing link.

Frame completeness uses created, sent, uniquely received and rendered
identities plus sender and playback terminal evidence. Padding, capture
shortfall and excess are separate. Missing events and process failure cannot
become successful delivery. Only complete bursts enter complete-burst delay
and stall aggregates; partial attempts retain their planned denominators.

Internal queue stalls use gaps in AudioWorklet render frames. Cross-context
delays use same-host performance time origins and per-event audio-clock
mapping, with at least one render block (2.667 ms) of nominal uncertainty and
recorded clock-offset variation. Server-clock offset estimates are separate.
PCM spectrum checks only confirm the test signal; PCM duration or natural
silence is not evidence of missing network frames.

## Findings and decision

Among the fixed candidates, **150 ms is the recommended option for human
review**. Across 18 complete unstable-profile bursts, total internal queue
stalls fell from 5541.3 ms to 4438.7 ms (19.9%; 1102.7 ms less), and stall count
fell from 86 to 67. All three seeds had lower total stalls with P150. The
baseline matched median start-delay cost was 46.5 ms; under unstable conditions
it was 72.4 ms, with individual paired differences from -104.2 to +245.1 ms.
The unstable median paired end-delay difference was -2.25 ms.

P150 does not eliminate long stalls: its maximum unstable stall was 293.3 ms
versus 272.0 ms for P100. Its first-to-third end-delay growth was also less
favourable: median +86.3 ms, range +8.1 to +315.7 ms, compared with -23.7 ms,
range -116.9 to +191.7 ms for P100. This small sample does not establish that a
larger fixed buffer prevents lag growth.

P200 is not preferred: unstable stall time was 4862.7 ms (12.2% less than
P100, but more than P150), with a 410.7 ms maximum stall. Under egress-48 it
produced 744.0 ms of stalls versus 260.0 ms for P100 and 90.0 ms for P150.
Observed effects are not monotonic, and three seeds cannot establish a
universal improvement or speech-intelligibility benefit.

The options presented for the human decision were:

- Keep 100 ms if the permanent startup-delay cost is not acceptable.
- Choose fixed 150 ms for the best measured compromise among these candidates.
- Choose fixed 200 ms only with an explicit reason to accept its extra delay;
  this study does not show it is generally better than 150 ms.
- Keep 100 ms and separately investigate adaptive buffering if the measured
  pause reduction is useful but a permanent delay increase is undesirable.

**At the close of the study, no choice had been applied and the default was 100 ms.**

## Human decision applied on 9 October 2026

The user accepted the recommendation to use a fixed 150 ms initial browser
playback delay. `PLAYBACK_START_DELAY_MS` in `web/src/audio/processor.ts` is
now 150. The experimental builder still produces P100/P150/P200 from a common
snapshot regardless of the working default. Its regression checks cover
starting from each of those defaults and preserve unrelated source values.

This is the playback startup delay inside the AudioWorklet. The grant cue's
100 ms capture delay, stop timeout, protocol and Android settings are unchanged.
The measured tables, original source manifests and raw evidence retain their
original conditions. Applying the decision to source does not publish a release
or deploy a server package.

Routine audio-gate checks now retain lightweight per-test journals and enable
heavy Playwright tracing only when explicitly requested. Two validation attempts
with tracing enabled were preserved: an ECHO second-capture recovery failure
and a two-way audio test timeout during cleanup. Browser state reads in the ECHO
trace took up to 6071.4 ms. These observations are consistent with the earlier
observer-interference findings, but do not establish both failures' exact causes.

Validation of the applied decision passed: the complete pinned Linux/web gate
(159 unit tests, codec check, 18 browser cases, four audio cases and restart)
and the headless gate (386 tests, Ruff and metadata validation). The new worklet
matches the measured P150 bytes exactly, SHA-256
`f6a06fb26175bb7b881e2c69daa9a96ffb7cec001af866d245a66f27d845c06d`.
Thus the production playback implementation reuses the measured candidate;
the measurement tables were not rerun or rewritten for this decision.

## Completion, limitations and evidence

All 45 main runs and six separate PCM runs have retained results. The main
matrix planned 270 bursts / 270000 frames: 253 bursts were captured, 243 were
complete, ten were truncated, and 17 were not started after their directional
series timed out. Created frames totalled 253211 (including padded final
frames), uniquely received frames 248941, and rendered positions 248984.
The 43 additional rendered positions were concealment, not delivered speech.
There were 615 duplicate receives, all in egress-32, and no duplicate playback
identities or playback-order violations. Egress-32 also accounted for all 11
sender reconnections. Created captures lasted 20000–20032 ms and contained
1000–1002 frames; padding and capture excess are retained per burst.
The 187 actual inter-send pauses had median 2029.0 ms and range 2007.5–3035.5 ms;
the two-second deadline is followed by browser automation scheduling overhead.
Do not combine these totals into an apparent quality improvement. All
deviations were in egress-32. The separate PCM matrix delivered all 36031
created frames across 36 planned bursts / 36000 planned frames.

In egress-32, the ten attempted A-to-B bursts captured the full 20-second
plan, but only 534–614 unique frames per burst arrived. Worklet stop was
acknowledged; the observed failure was later in transmission completion.
Sender journals contain lease-expiry terminals, nine sealed `expired`
outcomes and one `draining` outcome without a later sender terminal. The
scenario's 35-second wait for sender idle expired while it displayed
`Finishing…`; subsequent planned A-to-B captures were not attempted. This
35-second per-send guard is an additional measurement limitation inside the
360-second scenario limit. The study does not measure three completed forward
bursts at 32 kbit/s. All nine reverse series completed their three bursts.
Neither a larger startup buffer nor concealed playback restores this missing
speech. No protocol, lease or recovery changes were made.

Every run used the same frozen collection-method hashes. `matrix-r2/` contains
the accepted candidate sources, patches, image IDs, build logs, manifests,
loaded-asset hashes, original collection-time analyses and final analyses.
The offline analyzer was then tightened uniformly to preserve explicit series
timeouts even if a late end marker arrives during the reverse series; its
collection-time source is retained separately. No raw attempt was overwritten.
`comparison.json` retains all per-burst distributions and matched differences;
`pcm-analysis.json` retains the separate signal checks. Historical and excluded
pilot evidence remain outside that comparison.

The deterministic microphone WAV SHA-256 is
`d742765de9e189f22c158b643376eb39998e9a7e708606a67a5a682a6aa9d10e`.
All 51 runs used Chrome 154.0.8037.98 and confirmed healthy host probes before
and after the run. Observed within-burst render-to-wall clock-offset ranges
were 3.9–37.5 ms (median 11.0 ms), in addition to the nominal render-block
resolution. HTTP server-clock uncertainty estimates were 183–412 ms; server
time was not used for the browser delay figures. Small delay differences
should be interpreted with these limitations.

## Main matrix: completeness

Counts include all observed bursts, including truncated bursts. Each group plans 18 bursts and 18000 frames. Played positions can include concealment and are not equivalent to received speech.

| Profile | Variant | Complete / observed / planned bursts | Created | Unique received | Played |
|---|---|---|---|---|---|
| baseline | P100 | 18 / 18 / 18 | 18016 | 18016 | 18016 |
| baseline | P150 | 18 / 18 / 18 | 18014 | 18014 | 18014 |
| baseline | P200 | 18 / 18 / 18 | 18015 | 18015 | 18015 |
| unstable | P100 | 18 / 18 / 18 | 18014 | 18014 | 18014 |
| unstable | P150 | 18 / 18 / 18 | 18016 | 18016 | 18016 |
| unstable | P200 | 18 / 18 / 18 | 18016 | 18016 | 18016 |
| egress-32 | P100 | 9 / 12 / 18 | 12011 | 10755 | 10767 |
| egress-32 | P150 | 9 / 13 / 18 | 13011 | 11271 | 11290 |
| egress-32 | P200 | 9 / 12 / 18 | 12009 | 10735 | 10747 |
| egress-48 | P100 | 18 / 18 / 18 | 18016 | 18016 | 18016 |
| egress-48 | P150 | 18 / 18 / 18 | 18013 | 18013 | 18013 |
| egress-48 | P200 | 18 / 18 / 18 | 18020 | 18020 | 18020 |
| egress-64 | P100 | 18 / 18 / 18 | 18012 | 18012 | 18012 |
| egress-64 | P150 | 18 / 18 / 18 | 18015 | 18015 | 18015 |
| egress-64 | P200 | 18 / 18 / 18 | 18013 | 18013 | 18013 |

## Main matrix: pauses and delays

Only complete bursts in valid directional series contribute below. For egress-32 this is **B-to-A only**, nine bursts per variant; it does not establish acceptable two-way operation. All other rows have 18 bursts. Durations are milliseconds. Start is capture start to first rendered sample; end is capture end to final rendered frame. Brackets give the observed minimum and maximum, not confidence intervals.

| Profile | Variant | Stalls | Total / maximum stall ms | Start median [min, max] ms | End median [min, max] ms |
|---|---|---|---|---|---|
| baseline | P100 | 6 | 516.0 / 150.7 | 218.0 [200.1, 229.9] | 234.9 [220.2, 375.0] |
| baseline | P150 | 3 | 16.7 / 11.3 | 267.4 [250.0, 291.4] | 277.1 [266.2, 305.6] |
| baseline | P200 | 1 | 182.7 / 182.7 | 317.8 [302.6, 342.8] | 330.5 [307.1, 509.1] |
| unstable | P100 | 86 | 5541.3 / 272.0 | 406.8 [319.9, 651.4] | 725.4 [623.9, 848.8] |
| unstable | P150 | 67 | 4438.7 / 293.3 | 483.1 [384.7, 734.2] | 711.2 [591.7, 907.4] |
| unstable | P200 | 59 | 4862.7 / 410.7 | 520.4 [422.3, 607.5] | 791.4 [593.7, 1007.4] |
| egress-32 | P100 | 907 | 11990.7 / 412.0 | 498.7 [456.2, 1061.9] | 1795.4 [1098.2, 2814.0] |
| egress-32 | P150 | 776 | 10336.0 / 70.7 | 586.9 [514.9, 1633.0] | 1861.8 [839.9, 3131.4] |
| egress-32 | P200 | 697 | 8788.0 / 78.7 | 779.9 [567.8, 4070.4] | 2406.7 [970.2, 4091.8] |
| egress-48 | P100 | 21 | 260.0 / 53.3 | 483.6 [405.2, 515.3] | 501.0 [470.3, 575.8] |
| egress-48 | P150 | 7 | 90.0 / 22.7 | 528.4 [476.9, 574.7] | 543.6 [489.7, 586.3] |
| egress-48 | P200 | 25 | 744.0 / 100.0 | 566.4 [514.4, 1526.5] | 595.8 [522.9, 1532.3] |
| egress-64 | P100 | 9 | 457.3 / 260.0 | 463.3 [404.9, 504.6] | 472.2 [432.1, 775.1] |
| egress-64 | P150 | 2 | 12.7 / 10.7 | 516.2 [482.4, 552.9] | 531.9 [491.2, 555.7] |
| egress-64 | P200 | 1 | 5.3 / 5.3 | 560.7 [502.2, 600.5] | 572.0 [499.0, 619.8] |

## Frame delay and growth across three bursts

Frame delay is the distribution of **per-burst median** capture-to-rendered-frame-end delays, not a pooled frame percentile. The JSON retains each burst's frame-delay min/median/p05/p95/p99/max. Growth is third-burst end delay minus first-burst end delay, over six complete directions per group (three B-to-A directions for egress-32).

| Profile | Variant | Frame median: median [min, max] ms | First-to-third growth: median [min, max] ms |
|---|---|---|---|
| baseline | P100 | 226.5 [216.5, 371.0] | -9.5 [-88.3, 120.7] |
| baseline | P150 | 269.5 [260.9, 300.5] | -4.4 [-26.6, 7.5] |
| baseline | P200 | 323.5 [311.9, 342.5] | -8.2 [-28.9, 5.1] |
| unstable | P100 | 665.2 [532.9, 852.2] | -23.7 [-116.9, 191.7] |
| unstable | P150 | 671.7 [542.1, 852.9] | 86.3 [8.1, 315.7] |
| unstable | P200 | 663.7 [564.6, 974.2] | 84.7 [-114.7, 266.1] |
| egress-32 | P100 | 1511.9 [715.2, 1716.0] | 1276.4 [875.7, 1715.8] |
| egress-32 | P150 | 1465.4 [840.4, 2029.9] | 1364.7 [597.8, 1908.7] |
| egress-32 | P200 | 1837.4 [750.7, 4072.8] | -86.7 [-2906.0, 1781.1] |
| egress-48 | P100 | 494.6 [419.5, 535.2] | -29.5 [-31.9, 88.1] |
| egress-48 | P150 | 537.7 [493.3, 574.4] | -18.3 [-38.1, 58.4] |
| egress-48 | P200 | 577.6 [520.8, 1532.9] | 11.1 [-294.0, 934.3] |
| egress-64 | P100 | 470.8 [427.1, 774.9] | -14.2 [-137.6, 9.9] |
| egress-64 | P150 | 521.2 [490.8, 554.8] | -23.0 [-37.5, 32.9] |
| egress-64 | P200 | 565.4 [503.3, 601.9] | -23.5 [-63.8, 34.9] |

## Matched differences from P100

Pairs match profile, seed, direction and burst ordinal. Positive delay differences mean the candidate is later. A positive reduction is less total queue-stall time, not better intelligibility. Egress-32 pairs cover only the successful reverse direction.

| Profile | Candidate | Pairs | P100 → candidate stalls ms | Reduction % | Start difference median [min, max] ms | End difference median [min, max] ms |
|---|---|---|---|---|---|---|
| baseline | P150 | 18 | 516.0 → 16.7 | 96.8 | 46.5 [27.9, 69.5] | 41.3 [-106.8, 66.9] |
| baseline | P200 | 18 | 516.0 → 182.7 | 64.6 | 100.9 [83.6, 120.6] | 96.2 [-49.0, 280.6] |
| egress-32 | P150 | 9 | 11990.7 → 10336.0 | 13.8 | 88.2 [-441.3, 752.0] | 120.6 [-683.2, 698.5] |
| egress-32 | P200 | 9 | 11990.7 → 8788.0 | 26.7 | 113.1 [-426.0, 3614.2] | 70.8 [-1628.2, 2993.6] |
| egress-48 | P150 | 18 | 260.0 → 90.0 | 65.4 | 59.0 [-21.8, 138.4] | 50.9 [-20.6, 72.9] |
| egress-48 | P200 | 18 | 260.0 → 744.0 | -186.2 | 81.4 [20.6, 1061.4] | 99.2 [17.9, 1049.9] |
| egress-64 | P150 | 18 | 457.3 → 12.7 | 97.2 | 51.6 [1.5, 136.4] | 49.4 [-263.1, 119.1] |
| egress-64 | P200 | 18 | 457.3 → 5.3 | 98.8 | 94.9 [55.4, 164.5] | 92.7 [-211.0, 156.3] |
| unstable | P150 | 18 | 5541.3 → 4438.7 | 19.9 | 72.4 [-104.2, 245.1] | -2.25 [-190.4, 213.2] |
| unstable | P200 | 18 | 5541.3 → 4862.7 | 12.2 | 106.9 [-77.5, 232.8] | 24.3 [-160.3, 345.9] |

Unstable totals by seed, six complete bursts per cell:

| Seed | P100 stall ms | P150 stall ms | P200 stall ms |
|---|---|---|---|
| 1009 | 2089.3 | 1385.3 | 1674.7 |
| 2017 | 1809.3 | 1706.7 | 1706.7 |
| 3037 | 1642.7 | 1346.7 | 1481.3 |

## Separate PCM observation

These six runs use seed 1009 and are not pooled with the main matrix. Each cell covers six complete bursts. Same-seed repeats still differ in TCP scheduling and host timing, so these differences cannot be attributed exclusively to PCM observation.

| Profile | Variant | Light → PCM stalls ms | Light → PCM median start ms | Light → PCM median end ms |
|---|---|---|---|---|
| baseline | P100 | 150.7 → 248.0 | 221.2 → 213.5 | 239.2 → 221.9 |
| baseline | P150 | 11.3 → 0.0 | 264.7 → 265.9 | 274.7 → 277.8 |
| baseline | P200 | 0.0 → 0.0 | 318.5 → 311.9 | 335.4 → 328.7 |
| unstable | P100 | 2089.3 → 2024.0 | 365.3 → 423.4 | 719.1 → 760.2 |
| unstable | P150 | 1385.3 → 1504.0 | 483.8 → 434.1 | 695.3 → 673.6 |
| unstable | P200 | 1674.7 → 1261.3 | 520.4 → 556.8 | 751.4 → 847.8 |

All 12 directional PCM files passed sparse spectral confirmation. Source/output spectrum cosine similarity ranged from 0.99438 to 0.99999; output power in the expected carrier neighbourhoods ranged from 92.90% to 93.29%. This does not validate acoustics, intelligibility or frame ordering.

## Every main attempt

Each row plans six bursts. All nine egress-32 rows retain an explicit A-to-B sender-finish timeout; their created/received totals include partial bursts. No attempt is replaced by a successful retry.

| Profile | Seed | Variant | Complete / observed bursts | Created / unique received | Outcome |
|---|---|---|---|---|---|
| baseline | 1009 | P100 | 6 / 6 | 6004 / 6004 | Complete |
| baseline | 1009 | P150 | 6 / 6 | 6004 / 6004 | Complete |
| baseline | 1009 | P200 | 6 / 6 | 6006 / 6006 | Complete |
| baseline | 2017 | P150 | 6 / 6 | 6005 / 6005 | Complete |
| baseline | 2017 | P200 | 6 / 6 | 6005 / 6005 | Complete |
| baseline | 2017 | P100 | 6 / 6 | 6005 / 6005 | Complete |
| baseline | 3037 | P200 | 6 / 6 | 6004 / 6004 | Complete |
| baseline | 3037 | P100 | 6 / 6 | 6007 / 6007 | Complete |
| baseline | 3037 | P150 | 6 / 6 | 6005 / 6005 | Complete |
| unstable | 1009 | P100 | 6 / 6 | 6005 / 6005 | Complete |
| unstable | 1009 | P150 | 6 / 6 | 6004 / 6004 | Complete |
| unstable | 1009 | P200 | 6 / 6 | 6004 / 6004 | Complete |
| unstable | 2017 | P150 | 6 / 6 | 6008 / 6008 | Complete |
| unstable | 2017 | P200 | 6 / 6 | 6006 / 6006 | Complete |
| unstable | 2017 | P100 | 6 / 6 | 6004 / 6004 | Complete |
| unstable | 3037 | P200 | 6 / 6 | 6006 / 6006 | Complete |
| unstable | 3037 | P100 | 6 / 6 | 6005 / 6005 | Complete |
| unstable | 3037 | P150 | 6 / 6 | 6004 / 6004 | Complete |
| egress-32 | 1009 | P100 | 3 / 4 | 4004 / 3579 | A-to-B timeout / lease expiry; B-to-A complete |
| egress-32 | 1009 | P150 | 3 / 4 | 4004 / 3556 | A-to-B timeout / lease expiry; B-to-A complete |
| egress-32 | 1009 | P200 | 3 / 4 | 4003 / 3617 | A-to-B timeout / lease expiry; B-to-A complete |
| egress-32 | 2017 | P150 | 3 / 4 | 4002 / 3576 | A-to-B timeout / lease expiry; B-to-A complete |
| egress-32 | 2017 | P200 | 3 / 4 | 4002 / 3581 | A-to-B timeout / lease expiry; B-to-A complete |
| egress-32 | 2017 | P100 | 3 / 4 | 4005 / 3573 | A-to-B timeout / lease expiry; B-to-A complete |
| egress-32 | 3037 | P200 | 3 / 4 | 4004 / 3537 | A-to-B timeout / lease expiry; B-to-A complete |
| egress-32 | 3037 | P100 | 3 / 4 | 4002 / 3603 | A-to-B timeout / lease expiry; B-to-A complete |
| egress-32 | 3037 | P150 | 3 / 5 | 5005 / 4139 | A-to-B timeout / lease expiry; B-to-A complete |
| egress-48 | 1009 | P100 | 6 / 6 | 6004 / 6004 | Complete |
| egress-48 | 1009 | P150 | 6 / 6 | 6004 / 6004 | Complete |
| egress-48 | 1009 | P200 | 6 / 6 | 6007 / 6007 | Complete |
| egress-48 | 2017 | P150 | 6 / 6 | 6004 / 6004 | Complete |
| egress-48 | 2017 | P200 | 6 / 6 | 6007 / 6007 | Complete |
| egress-48 | 2017 | P100 | 6 / 6 | 6006 / 6006 | Complete |
| egress-48 | 3037 | P200 | 6 / 6 | 6006 / 6006 | Complete |
| egress-48 | 3037 | P100 | 6 / 6 | 6006 / 6006 | Complete |
| egress-48 | 3037 | P150 | 6 / 6 | 6005 / 6005 | Complete |
| egress-64 | 1009 | P100 | 6 / 6 | 6005 / 6005 | Complete |
| egress-64 | 1009 | P150 | 6 / 6 | 6003 / 6003 | Complete |
| egress-64 | 1009 | P200 | 6 / 6 | 6006 / 6006 | Complete |
| egress-64 | 2017 | P150 | 6 / 6 | 6006 / 6006 | Complete |
| egress-64 | 2017 | P200 | 6 / 6 | 6003 / 6003 | Complete |
| egress-64 | 2017 | P100 | 6 / 6 | 6004 / 6004 | Complete |
| egress-64 | 3037 | P200 | 6 / 6 | 6004 / 6004 | Complete |
| egress-64 | 3037 | P100 | 6 / 6 | 6003 / 6003 | Complete |
| egress-64 | 3037 | P150 | 6 / 6 | 6006 / 6006 | Complete |

## Validation at the close of the study

- `scripts/test-web.ps1` passed: pinned Linux lint/build, 159 unit tests,
  the codec check, 18 browser cases, four audio cases and the restart case.
  The manual long scenario is intentionally skipped by that gate and is
  covered by the 51 external runs above. All three candidate builds also
  passed their actual 100/150/200 ms render-clock tests.
- `scripts/test-headless.ps1` passed: 383 tests, Ruff and metadata validation.
  Synthetic analyzer regressions include missing/duplicate/misordered frames,
  burst and generation identity, capture shortfall/expiry, queue stalls,
  missing ends, process failure, explicit series timeout despite late events,
  final-terminal pause timing, and matched-pair denominators.
- `scripts/test-publication.py` passed all 11 isolated publication-control
  tests. Final worktree-publication and whitespace checks are recorded with
  the local evidence.

## Evidence retention after the study

The ordinary web gate now isolates browser/audio/restart artifacts per run,
retains both output streams and saves Compose journals before cleanup.
Launcher regressions cover occupied-stack rejection, retained audio journals,
`KeepRunning`, startup failures and independent collection/cleanup failures.
Proxy results are atomically saved before cleanup and distinguish pending,
complete and failed cleanup; old result files remain readable. Experimental
build restoration retries only confirmed Windows ReadOnly deletion failures
and verifies the complete file-hash set, preserving both failure causes.

These tooling changes do not alter the playback implementation or rewrite the
measurement tables. Detailed operational history and validation manifests
remain in ignored local evidence.

Validation of these changes passed eight isolated launcher scenarios, the
headless gate (401 tests, Ruff and metadata), and the complete pinned web gate
(159 unit tests, codec, 18 browser cases, four audio cases and restart).
Five saved audio page journals remained available after the restart stage.
The worklet still matches the measured P150 bytes; the main bundle differs
from that candidate only by its diagnostic build timestamp.

Two separate P150 validation runs used lightweight observation, seed 1009,
and the original three-by-20-second contract in both directions. Baseline
completed all six bursts with 6002 created/sent/received/played unique frames;
unstable completed all six with 6005. Each run planned 6000 frames; the excess
remains explicit rather than changing that denominator. Both cleanup outcomes
were complete, and the tested local build was restored with an exact hash-set
match. These are tooling acceptance checks, not additional matched buffer
comparisons, and are not merged into the study tables.
