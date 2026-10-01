from app.media_timing import OutboundMediaContext, ServerMediaTimingDiagnostics


def test_media_timing_ignores_intervals_at_or_below_threshold() -> None:
    events: list[str] = []
    diagnostics = ServerMediaTimingDiagnostics("session-123456", emit=events.append)
    diagnostics.uplink_received("burst-111111", 0, 1, 40, 0)
    diagnostics.uplink_received("burst-111111", 1, 1, 40, 100)
    context = OutboundMediaContext("burst-111111", 0, 1, 0, 0, 100)
    diagnostics.downlink_queued(context, 40)
    diagnostics.downlink_sent(context, 100, 100, 0)
    next_context = OutboundMediaContext("burst-111111", 1, 1, 100, 100, 200)
    diagnostics.downlink_sent(next_context, 200, 200, 0)

    assert events == []


def test_media_timing_records_exact_receive_handoff_and_send_fields() -> None:
    events: list[str] = []
    diagnostics = ServerMediaTimingDiagnostics("session-123456", emit=events.append)
    diagnostics.uplink_received("burst-111111", 0, 2, 80, 100)
    diagnostics.uplink_received("burst-111111", 2, 3, 120, 250)
    context = OutboundMediaContext("burst-111111", 2, 3, 100, 250, 390)
    diagnostics.downlink_queued(context, 240)
    diagnostics.downlink_sent(context, 550, 700, 0)
    next_context = OutboundMediaContext("burst-111111", 5, 1, 700, 700, 800)
    diagnostics.downlink_sent(next_context, 800, 900, 0)

    assert events == [
        "media_timing type=server_receive_gap session=123456 burst=111111 "
        "first=2 frames=3 gap=150ms bytes=120",
        "media_timing type=server_handoff_delay session=123456 burst=111111 "
        "first=2 frames=3 oldest_age=290ms newest_age=140ms queue=240B",
        "media_timing type=server_send_delay session=123456 burst=111111 "
        "first=2 frames=3 queue_wait=160ms send_time=150ms queue=0B",
        "media_timing type=server_send_gap session=123456 burst=111111 "
        "first=5 frames=1 gap=200ms queue=0B",
    ]


def test_media_timing_limits_each_type_per_burst_and_resets_for_new_burst() -> None:
    events: list[str] = []
    diagnostics = ServerMediaTimingDiagnostics(
        "session-123456",
        emit=events.append,
        max_events_per_type=2,
    )
    for index in range(4):
        diagnostics.uplink_received("burst-111111", index, 1, 40, index * 101)
    diagnostics.uplink_received("burst-222222", 0, 1, 40, 1_000)
    diagnostics.uplink_received("burst-222222", 1, 1, 40, 1_101)

    assert len(events) == 3
    assert sum("burst=111111" in event for event in events) == 2
    assert sum("burst=222222" in event for event in events) == 1
