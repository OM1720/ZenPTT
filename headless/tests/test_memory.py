import asyncio
import gc
import json

import pytest

from zenptt_headless.assembler import BurstAssembler
from zenptt_headless.bot import BotConfig, _BotRunner
from zenptt_headless.client import ClientConfig, HeadlessClient
from zenptt_headless.protocol import DOWNLINK, encode_media
from zenptt_headless.types import PcmAudio, ReceivedBurst


class Decoder:
    def decode(self, packet):
        return packet * 640

    def close(self):
        pass


async def receive_messages(client, messages):
    class Socket:
        async def send(self, _message):
            pass

        async def __aiter__(self):
            for message in messages:
                yield message if isinstance(message, bytes) else json.dumps(message)

    context = client._attach_transport(Socket())
    client._ready.set()
    await client._receive_loop(context)


@pytest.mark.asyncio
@pytest.mark.parametrize("first_sequence", [2, 3], ids=["nonempty-tail", "empty-tail"])
@pytest.mark.parametrize("new_session", [False, True], ids=["next-burst", "new-epoch"])
@pytest.mark.parametrize("stale_position", ["none", "before_reset", "after_reset"])
async def test_memory_rejected_burst_stays_discarded_through_listen_reset(
    first_sequence, new_session, stale_position,
):
    client = HeadlessClient(ClientConfig("ws://test"))
    client._pcm_budget.set_limit(1280)
    client._assembler = BurstAssembler(Decoder, pcm_budget=client._pcm_budget)
    burst_id = "11111111-1111-4111-8111-111111111111"
    next_id = "22222222-2222-4222-8222-222222222222"
    old_id = "33333333-3333-4333-8333-333333333333"
    old_messages = [
        {"type": "burst_started", "burst_id": old_id, "burst_index": 0},
        encode_media(DOWNLINK, old_id, 0, (b"o",)),
        {"type": "burst_sealed", "burst_id": old_id, "burst_index": 0,
         "final_next_sequence": 1, "reason": "complete"},
    ]
    await receive_messages(client, old_messages)
    assert isinstance(client._events.get_nowait(), ReceivedBurst)
    assert client._pcm_budget.used_bytes == 0
    start = {"type": "burst_started", "burst_id": burst_id, "burst_index": 1}
    reset = {"type": "listen_reset", "burst_index": 1,
             "next_sequence": first_sequence, "reason": "expired"}
    seal = {"type": "burst_sealed", "burst_id": burst_id, "burst_index": 1,
            "final_next_sequence": 3, "reason": "complete"}
    await receive_messages(client, [
        start, encode_media(DOWNLINK, burst_id, 0, (b"x",)),
        {"type": "burst_gaps", "burst_id": burst_id, "burst_index": 1,
         "ranges": [{"first_sequence": 1, "count": 2}]},
        *(old_messages if stale_position == "before_reset" else []),
        reset, reset,
        *(old_messages if stale_position == "after_reset" else []),
        start,
        *([encode_media(DOWNLINK, burst_id, 2, (b"y",))] if first_sequence == 2 else []),
        seal, start, seal,
    ])
    interrupted = client._events.get_nowait()
    assert interrupted.reason == "pcm_budget_exceeded"
    assert client._events.empty()
    assert client._pcm_budget.used_bytes == 0
    assert client._listen_cursor == (2, 0)

    next_index = 2
    if new_session:
        client._lose_session("resume_rejected")
        assert client._events.get_nowait().session_epoch == 1
        next_id, next_index = burst_id, 0
    await receive_messages(client, [
        {"type": "listen_reset", "burst_index": next_index, "next_sequence": 0, "reason": "expired"},
        {"type": "burst_started", "burst_id": next_id, "burst_index": next_index},
        encode_media(DOWNLINK, next_id, 0, (b"z",)),
        {"type": "burst_sealed", "burst_id": next_id, "burst_index": next_index,
         "final_next_sequence": 1, "reason": "complete"},
    ])
    received = client._events.get_nowait()
    assert received.burst_id == next_id
    assert received.session_epoch == int(new_session)
    assert received.audio.data == b"z" * 640
    assert client._events.empty()


@pytest.mark.asyncio
async def test_incoming_pcm_and_active_send_share_response_budget(caplog):
    client = HeadlessClient(ClientConfig("ws://test"))
    sending = asyncio.Event()
    release = asyncio.Event()
    invoked = asyncio.Event()

    async def send(audio, **kwargs):
        sending.set()
        await release.wait()

    async def handler(burst):
        invoked.set()
        return PcmAudio(b"b" * 640)

    client.send_audio = send
    runner = _BotRunner(BotConfig(client.config, send_queue_bytes=1280), handler, client)
    first = ReceivedBurst("first", 0, PcmAudio(b"a" * 640), (), (), "complete")
    client._offer_event(first)
    collect = asyncio.create_task(runner._collect())
    handle = asyncio.create_task(runner._handle())
    sender = asyncio.create_task(runner._send())
    try:
        await asyncio.wait_for(sending.wait(), 1)
        # The caller retains the first input; the active send owns another 640 bytes.
        invoked.clear()
        client._offer_event(ReceivedBurst("overflow", 1, PcmAudio(b"c" * 640), (), (), "complete"))
        await asyncio.sleep(0.03)
        assert not invoked.is_set()
        assert "pcm_budget_exceeded" in caplog.text
    finally:
        release.set()
        for task in (collect, handle, sender):
            task.cancel()
        await asyncio.gather(collect, handle, sender, return_exceptions=True)


@pytest.mark.asyncio
async def test_shared_response_is_counted_once_and_released_after_queue_clear():
    from zenptt_headless.memory import PcmBudget

    budget = PcmBudget(1280)
    audio = PcmAudio(bytes(640))
    alias = PcmAudio(audio.data)
    assert budget.admit(audio)
    assert budget.admit(alias)
    assert budget.used_bytes == 640
    del audio
    gc.collect()
    assert budget.used_bytes == 640
    del alias
    gc.collect()
    assert budget.used_bytes == 0


@pytest.mark.asyncio
async def test_memory_rejected_receive_is_not_reassembled_after_resume():
    client = HeadlessClient(ClientConfig("ws://test"))
    client._pcm_budget.set_limit(1280)
    client._assembler = BurstAssembler(Decoder, pcm_budget=client._pcm_budget)
    start = {"type": "burst_started", "burst_id": "burst", "burst_index": 0}
    await client._handle_control(start)
    client._assembler.media("burst", 0, (b"x",))
    await client._handle_control({"type": "burst_gaps", "burst_id": "burst", "burst_index": 0,
                                  "ranges": [{"first_sequence": 1, "count": 2}]})
    event = await client.receive()
    assert event.reason == "pcm_budget_exceeded"
    assert client._pcm_budget.used_bytes == 0
    await client._handle_control(start)
    assert client._assembler._burst is None
    await client._handle_control({"type": "burst_sealed", "burst_id": "burst", "burst_index": 0,
                                  "final_next_sequence": 3, "reason": "complete"})
    assert client._events.empty()
    assert client._listen_cursor == (1, 0)


def test_assembly_reserves_copy_space_and_releases_on_interrupt():
    from zenptt_headless.memory import PcmBudget, PcmBudgetExceeded

    budget = PcmBudget(1280)
    assembler = BurstAssembler(Decoder, pcm_budget=budget)
    assembler.start("burst", 0)
    assembler.media("burst", 0, (b"x",))
    assert budget.used_bytes == 640
    with pytest.raises(PcmBudgetExceeded):
        assembler.media("burst", 1, (b"y",))
    assert assembler.cursor == (0, 1)
    assembler.interrupt("test")
    assert budget.used_bytes == 0
    assembler.start("next", 1)
    assembler.media("next", 0, (b"z",))
    result = assembler.seal("next", 1, "complete")
    assert result.audio.data == b"z" * 640
    assert budget.used_bytes == 640
    del result
    gc.collect()
    assert budget.used_bytes == 0


@pytest.mark.asyncio
@pytest.mark.parametrize("fails", [False, True])
async def test_completed_handler_releases_input_even_on_failure(fails):
    client = HeadlessClient(ClientConfig("ws://test"))

    async def handler(_burst):
        if fails:
            raise RuntimeError("handler failure")
        return None

    runner = _BotRunner(BotConfig(client.config), handler, client)
    await runner._handle_burst(ReceivedBurst("source", 0, PcmAudio(bytes(640)), (), (), "complete"))
    # asyncio releases the failed task's traceback in its queued completion callback.
    await asyncio.sleep(0)
    gc.collect()
    assert runner._pcm_budget.used_bytes == 0


@pytest.mark.asyncio
async def test_client_stop_releases_assembly_and_queued_pcm():
    client = HeadlessClient(ClientConfig("ws://test"))
    client._assembler = BurstAssembler(Decoder, pcm_budget=client._pcm_budget)
    client._assembler.start("partial", 1)
    client._assembler.media("partial", 0, (b"x",))
    client._offer_event(ReceivedBurst("queued", 0, PcmAudio(bytes(640)), (), (), "complete"))
    assert client._pcm_budget.used_bytes == 1280
    await client.stop()
    gc.collect()
    assert client._pcm_budget.used_bytes == 0
