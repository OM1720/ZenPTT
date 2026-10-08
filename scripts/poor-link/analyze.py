"""Analyze observed bursts without mistaking a short sealed burst for full delivery."""

from __future__ import annotations

from collections import defaultdict
import statistics


FRAME_SECONDS = 0.02


def percentile(values: list[float], fraction: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    return round(ordered[round((len(ordered) - 1) * fraction)], 3)


def analyze_pair(sender: list[dict], receiver: list[dict], planned_frames: int = 1000) -> dict:
    """Return per-burst measurements and evidence-quality errors."""
    grants = {event["ordinal"]: event for event in sender if event.get("event") == "grant"}
    sends = {event["ordinal"]: event for event in sender if event.get("event") == "send_result"}
    produced = {event["ordinal"]: event for event in sender if event.get("event") == "produced"}
    acknowledgements: dict[str, list[dict]] = defaultdict(list)
    for event in sender:
        if event.get("event") == "control" and event.get("type") == "uplink_ack" and event.get("burst_id"):
            acknowledgements[event["burst_id"]].append(event)
    sender_transports = [event for event in sender if event.get("event") == "transport"]
    receiver_transports = [event for event in receiver if event.get("event") == "transport"]
    receiver_finished = next((event for event in reversed(receiver)
                              if event.get("event") == "receiver_finished"), None)
    received: dict[str, list[dict]] = defaultdict(list)
    media: dict[str, list[dict]] = defaultdict(list)
    release: dict[str, str] = {}
    seal: dict[str, str] = {}
    for event in receiver:
        kind = event.get("event")
        burst_id = event.get("burst_id")
        if kind == "received" and burst_id:
            received[burst_id].append(event)
        elif kind == "media" and burst_id:
            media[burst_id].append(event)
        elif kind == "control" and burst_id and event.get("type") == "burst_released":
            release[burst_id] = event["reason"]
        elif kind == "control" and burst_id and event.get("type") == "burst_sealed":
            seal[burst_id] = event["reason"]

    problems: list[str] = []
    for event in sender + receiver:
        if event.get("event") in {"probe_error", "send_error"}:
            problems.append(f"process_event:{event['event']}")
    expected_ids = {event["burst_id"] for event in grants.values() if event.get("burst_id")}
    for burst_id in set(received) | set(media):
        if burst_id not in expected_ids:
            problems.append(f"unexpected_burst:{burst_id}")

    rows = []
    for ordinal in range(1, 4):
        grant = grants.get(ordinal)
        send = sends.get(ordinal)
        burst_id = (grant or {}).get("burst_id") or (send or {}).get("burst_id")
        observations = received.get(burst_id, []) if burst_id else []
        if len(observations) > 1:
            problems.append(f"duplicate_completed_burst:{ordinal}")
        observed = observations[0] if observations else None
        arrivals = sorted(media.get(burst_id, []), key=lambda item: item["at"]) if burst_id else []
        seen: set[int] = set()
        duplicates = 0
        lags = []
        first_media_at = None
        last_media_at = None
        gaps = []
        lag_trace = {}
        previous_at = None
        for item in arrivals:
            at = item["at"]
            first_media_at = at if first_media_at is None else min(first_media_at, at)
            last_media_at = at if last_media_at is None else max(last_media_at, at)
            if previous_at is not None:
                gaps.append((at - previous_at) * 1000)
            previous_at = at
            for sequence in range(item["first_sequence"], item["first_sequence"] + item["count"]):
                if sequence in seen:
                    duplicates += 1
                seen.add(sequence)
                if grant:
                    lag = at - (grant["at"] + sequence * FRAME_SECONDS)
                    lags.append(lag)
                    bucket = sequence // 250
                    lag_trace[bucket] = round(lag, 3)

        first_sequence = observed.get("first_sequence", 0) if observed else None
        span = observed.get("frames", 0) if observed else None
        lost = sum(item[1] for item in observed.get("loss_ranges", [])) if observed else None
        decode_errors = len(observed.get("decode_errors", [])) if observed else None
        delivered = max(0, span - lost - decode_errors) if observed else 0
        truncated = max(0, planned_frames - first_sequence - span) if observed else None
        if send is None:
            problems.append(f"missing_send_result:{ordinal}")
        if grant is None:
            problems.append(f"missing_grant:{ordinal}")
        if send and burst_id and send.get("burst_id") not in (None, burst_id):
            problems.append(f"sender_burst_mismatch:{ordinal}")
        if produced.get(ordinal) is None:
            problems.append(f"missing_produced:{ordinal}")
        if observed is None:
            problems.append(f"missing_receive:{ordinal}")
        acks = acknowledgements.get(burst_id, [])
        rows.append({
            "ordinal": ordinal,
            "burst_id": burst_id,
            "planned_frames": planned_frames,
            "produced_frames": (produced.get(ordinal) or {}).get("frames"),
            "send_status": send.get("status") if send else None,
            "send_reason": send.get("reason") if send else None,
            "received": bool(observed),
            "receive_reason": observed.get("reason") if observed else
            "not_received_by_deadline" if receiver_finished else "receiver_result_missing",
            "release_reason": release.get(burst_id),
            "seal_reason": seal.get(burst_id),
            "first_sequence": first_sequence,
            "received_span_frames": span,
            "unique_media_frames": len(seen),
            "duplicate_media_frames": duplicates,
            "authoritative_loss_frames": lost,
            "decode_errors": decode_errors,
            "delivered_frames": delivered,
            "truncated_tail_frames": truncated,
            "missing_from_plan_frames": planned_frames - delivered,
            "grant_at": grant.get("at") if grant else None,
            "first_media_at": first_media_at,
            "last_media_at": last_media_at,
            "first_media_delay_s": round(first_media_at - grant["at"], 3)
            if first_media_at is not None and grant else None,
            "last_frame_lag_s": round(last_media_at - (grant["at"] + planned_frames * FRAME_SECONDS), 3)
            if last_media_at is not None and grant else None,
            "max_arrival_gap_ms": round(max(gaps), 1) if gaps else 0,
            "p95_frame_lag_s": percentile(lags, 0.95),
            "max_frame_lag_s": round(max(lags), 3) if lags else None,
            "lag_trace_5s": [lag_trace.get(bucket) for bucket in range(4)],
            "ack_count": len(acks),
            "ack_max_next_sequence": max((item["next_sequence"] for item in acks), default=None),
            "ack_last_at": acks[-1]["at"] if acks else None,
            "send_result_at": send.get("at") if send else None,
            "sender_session_epoch": send.get("session_epoch") if send else None,
            "receiver_session_epoch": observed.get("session_epoch") if observed else None,
        })
    return {"bursts": rows, "problems": problems,
            "sender_transports": len(sender_transports), "receiver_transports": len(receiver_transports),
            "sender_session_epochs": sorted({event.get("session_epoch") for event in sender_transports}),
            "receiver_session_epochs": sorted({event.get("session_epoch") for event in receiver_transports})}


def summarize_runs(runs: list[dict]) -> dict:
    """Summarize observed variation without imposing a product quality threshold."""
    grouped = defaultdict(list)
    for run in runs:
        for burst in run["analysis"]["bursts"]:
            grouped[(run["profile"], run["direction"], burst["ordinal"])].append(burst)
    result = []
    for (profile, direction, ordinal), bursts in sorted(grouped.items()):
        delivered = [item["delivered_frames"] for item in bursts]
        lags = [item["last_frame_lag_s"] for item in bursts if item["last_frame_lag_s"] is not None]
        result.append({
            "profile": profile, "direction": direction, "ordinal": ordinal,
            "repeats": len(bursts),
            "delivered_min": min(delivered), "delivered_median": statistics.median(delivered),
            "delivered_max": max(delivered),
            "last_lag_min_s": min(lags) if lags else None,
            "last_lag_median_s": round(statistics.median(lags), 3) if lags else None,
            "last_lag_max_s": max(lags) if lags else None,
        })
    return {"groups": result}
