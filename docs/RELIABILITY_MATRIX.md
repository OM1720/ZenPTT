# ZenPTT reliability evidence matrix

Status: current

Purpose: map reliability risks to executable regressions and physical confirmation
without mixing traceability into the testing runbook.

Audience: developers, testers, release owners, and AI coding assistants.

Authority: executable tests are the evidence source; this document is their maintained
traceability index.

Code anchors: `server/tests`, `headless/tests`, `android/app/src/test`,
`android/app/src/androidTest`, and `scripts/test-headless-live.ps1`.

Update when: a listed behavior, regression test, or corresponding manual scenario is
added, renamed, replaced, or removed.

Android `Class.method` entries are under `android/app/src/test/java/app/zenptt` or
`android/app/src/androidTest/java/app/zenptt`. Python entries use `file::test` notation.
Commands and prerequisites are in [`TESTING.md`](TESTING.md); numbered physical cases
are in [`MANUAL_TESTING.md`](MANUAL_TESTING.md).

| Risk or invariant | Automated evidence | Physical confirmation |
|---|---|---|
| Fresh uplink grouping and final tail | `ChannelViewModelTest.freshAudioGroupsThreeFramesAndFlushesTheTailAtFortyMillisecondsOrStop`, `ReliableAudioStateTest.freshRangeLimitKeepsTheFourthFrameForTheNextMessage`, `headless/tests/test_client.py::test_fresh_audio_batches_and_flushes_short_tail`, `web/src/ZenPttClient.test.ts` fresh-group test | Listen to a short phrase and a normal phrase across sender reconnect. |
| Truncated speech reported as interrupted | `ChannelViewModelTest.sealedShorterPrefixReportsInterruptedAfterTheOutgoingFramesWereCleared`, `web/src/ZenPttClient.test.ts` sealed-shorter-prefix test, `headless/tests/test_client.py::test_implicit_final_after_lease_expiry_reports_interruption`, `scripts/poor-link/test_analyze.py::test_complete_reason_does_not_hide_truncated_tail` | End a burst during a sender blackout; confirm the client does not report a complete transmission. |
| Poor-link evidence integrity and browser playback stalls | `scripts/poor-link/test_analyze.py`, `scripts/poor-link/test_analyze_browser_stall.py`, `web/tests/browser/audio.spec.ts` | Listen on a real device under jitter; compare worklet blocks, played-frame identities, and heard gaps. |
| Hardware PTT and setup isolation | `ButtonLearningTest`, `ButtonInputTest`, `HeadsetSettingsTest`, `HeadsetControllerTest`, `PttButtonSetupUiTest`, settings cases in `ChannelViewModelTest` and `SharedPreferencesConnectionPreferencesTest` | `MANUAL_TESTING.md` headset checks: BM008 without hints, other accessories, locked screen, disconnect, terminal reset, and diagnostics. |
| Mixed expired and recoverable uplink envelope | `server/tests/test_domain.py::test_mixed_expired_envelope_keeps_every_recoverable_frame`, `server/tests/test_session_races.py::test_mixed_expired_audio_acks_and_delivers_recoverable_suffix`, `ChannelViewModelTest.cumulativeAckRemovesOnlyAcceptedPrefixBeforeReconnectReplay` | Shape one envelope across `H`; verify one gap and the complete recoverable suffix. |
| Independent and interrupted Opus streams | `ChannelViewModelTest.playbackReceivesBurstIdentityForFramesAndLeadingLoss`, `OpusCodecTest.playbackDecoderResetsOnlyAtTheConsumedBurstBoundary`, connected `PlaybackQueueInstrumentationTest` burst-boundary scenarios | Alternate adjacent phrases and listen for decoder-state leakage, truncation, or unnecessary AudioTrack restart. |
| Degraded uplink or lost cumulative ACK | `ControlProtocolContractTest.recoveryPolicyMatchesServerContractVectors`, `ReliableAudioStateTest.cumulativeAckRemovesOnlyResolvedPrefix`, `ChannelViewModelTest.uplinkAckWatchdogWaitsThreeSecondsAndReplaysAfterReconnect` | Degraded-network cases 1-3. |
| Degraded downlink and playback backpressure | `PlaybackDrainTest`, `SourceSendDiagnosticsTest`, `ReceivePathDiagnosticsTest`, `server/tests/test_media_timing.py`, receive-order and playback-capacity cases in `ReliableAudioStateTest` and `ChannelViewModelTest` | Two-phone regression and degraded-network cases 6-7. |
| Full transport loss while PTT is held | `server/tests/test_domain.py::test_transport_loss_keeps_floor_until_lease_boundary`, floor-timer cases in `server/tests/test_session_races.py`, offline-recovery and absolute-duration cases in `ChannelViewModelTest` and `PttSessionTest` | Degraded-network cases 4-5 and 9; record the lease-release boundary. |
| Lost grant, request recovery, and offline release | Lost-grant and request-correlation cases in `PttSessionTest` and `ChannelViewModelTest` | Degraded-network case 13. |
| Stop-capture and terminal enqueue barrier | Terminal-ordering and scheduler-retry cases in `ChannelViewModelTest` | Degraded-network case 14. |
| Missed or conflicting terminal ACK | `server/tests/test_session_races.py::test_conflicting_terminal_watermark_repeats_authoritative_end`, `server/tests/test_session_races.py::test_invalid_open_watermark_does_not_release_floor`, `ReliableAudioStateTest.emptyInvalidRangeBurstIsRemovedAfterAuthoritativeTerminal` | Degraded-network case 14; verify an old terminal cannot stop a new burst. |
| Explicit Disconnect and graceful-close fallback | `server/tests/test_session_races.py::test_app_disconnect_control_closes_and_seals_owned_floor`, graceful-disconnect races in `ZenWebSocketClientTest` | Disconnect while owning PTT and record time to the next grant. |
| Member-scoped request replay | `server/tests/test_domain.py::test_registry_busy_capacity_cancel_expiry_and_leave_paths`, `server/tests/test_session_races.py::test_request_ids_are_idempotent_per_member` | Degraded-network case 13; verify no phantom transmission. |
| Busy PTT hold across reconnect | `PttSessionTest.busyHoldDoesNotBecomeRequestAfterReconnect`, `ChannelViewModelTest.busyHoldDoesNotRequestFloorAfterReconnect` | Hold while another member owns the floor; verify release/new press is required. |
| Deferred PTT queue and cue | Deferred-request cases in `ChannelViewModelTest`, `QueuedCueScheduleTest`, `AudioIndicatorTest`, and queued-cue cases in `AudioPipelineLifecycleTest` | Two-phone cases 10-11; verify timing and route-dependent sound. |
| Interruptible local playback tail | `ChannelViewModelTest.pttInterruptsOrdinaryDrainingTailAndSuppressesOldDrainCallback`, `ChannelViewModelTest.pttInterruptsEchoDrainingTailButRejectsActiveEchoBurst` | Repeat ordinary/ECHO tail interruption using touch, BM008, and TalkBack. |
| WebSocket queue and ping backpressure | Queue-size, rejected-ping, inbound-activity, and watchdog cases in `ZenWebSocketClientTest` | During degraded-network cases 1-2, verify the queue remains within 8192 bytes. |
| Explicit loss and burst-aware diagnostics | `ReliableAudioStateTest.finalGapBecomesExplicitLossThenBurstEnds`, `DiagnosticsTest.playbackLossUsesItsExactRangeAndSwitchesOnlyWhenPlaybackAdvances`, `LinkMetricsLifecycleTest.reconnectResetsPublishedMetricsAndEarlyBurstStartKeepsCurrentPlaybackMetrics` | Degraded-network case 8; compare declared and reported ranges. |
| PTT release with retained tail | `server/tests/test_domain.py::test_floor_releases_before_late_tail_arrives`, `server/tests/test_domain.py::test_resume_fences_transport_then_replayed_end_releases_floor` | Degraded-network cases 4 and 14. |
| Personal ECHO isolation and response | Assignment, ticket, replacement, and isolation cases in `server/tests/test_echo.py`, `headless/tests/test_echo_supervisor.py`, and the live Echo scenario | Run three simultaneous ECHO users; verify six correlated headless responses, distinct incarnations, no cross-talk, and recovery after component restarts. |
| Next speaker during previous drain | `ChannelViewModelTest.nextBurstDuringPreviousDrainKeepsReceivingAndSuppressesOldChannelFree` | Two-phone test followed by degraded-network case 5. |
| Repeated reconnect | Socket-factory/reconnect cases in `ZenWebSocketClientTest`, retained-playback cases in `ChannelViewModelTest`, connected `PlaybackQueueInstrumentationTest.reconnectRetainsQueuedPcmDecoderTrackAndGeneration` | Degraded-network cases 2, 5, and 6. |
| Server restart or new incarnation | Incarnation/reset cases in `ReliableAudioStateTest` and `ChannelViewModelTest` | Restart the stack, then repeat a normal baseline exchange. |
| Slow listener and fast history delivery | `server/tests/test_outbound.py`, listener-pump and history-budget cases in `server/tests/test_session_races.py`, `DiagnosticsTest.reportsBoundedRecoveryEventsWithExactServerRanges` | Shape one listener; verify another remains live. |
| Conflicting retained payload | `server/tests/test_domain.py::test_conflicting_duplicate_rejects_the_whole_envelope`, `ChannelViewModelTest.payloadMismatchStopsAutomaticReplayUntilManualReconnect` | Degraded-network case 11. |
| Independent v4 binary framing | `server/tests/test_protocol.py::test_fixed_wire_vectors`, `AudioFrameCodecTest.fixedWireVectorsAreIndependentOfTheEncoder` | None; literal vectors cover encoder and decoder independently. |
| Atomic admission and slot recovery | `server/tests/test_admission.py` | None; synchronized concurrent connections cover global/per-client limits and accept failure. |
| ECHO ticket expiry and secret redaction | `server/tests/test_echo.py::test_echo_ticket_expires_through_cleanup_without_logging_secrets`, `test_echo_failed_assignment_is_reassigned` | None; production cleanup runs against an injected clock. |
| Complete audio across receiver reconnect | `AudioRecoveryEndToEndTest`, `web/tests/browser/recovery.spec.ts` complete rendered-sequence check | Repeat a two-phone exchange with a short receiver outage and listen for gaps/repeats. |
| Correct signal rather than merely nonzero output | `AudioSignalOracleTest`, `EchoEndToEndTest`, `web/src/audio/SignalOracle.test.ts`, `web/tests/browser/audio.spec.ts` | Physical microphone/speaker audibility and Bluetooth routing remain manual. |
| Browser audio failure and resource ownership | `web/src/audio/BrowserAudio.test.ts` real-adapter/client checks | Unplug/reconnect a physical microphone and repeat a transmission. |
| Browser server restart | `web/tests/restart/restart.spec.ts`, required by `scripts/test-web.ps1` | None; isolated production stack restart discards stale held PTT. |
| Diagnostics during active audio | `ChannelViewModelTest.debugReportSerializesWithUplinkAcknowledgement`, `ForegroundServiceLifecycleTest` | Share diagnostics during a physical transmission; reporting must not interrupt audio. |

Every row must retain at least one executable regression. Physical Bluetooth, SCO,
acoustic, screen-off, public TLS, and reboot behavior remains required where automated
tests cannot establish the real environment.
