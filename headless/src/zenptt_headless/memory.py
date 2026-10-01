"""One PCM budget spanning assembly, queues, handlers, and active sends."""

from __future__ import annotations

import threading
import weakref

from .types import PcmAudio

MAX_PCM_BYTES = 64 * 1024 * 1024


class PcmBudgetExceeded(RuntimeError):
    pass


class PcmBudget:
    def __init__(self, limit: int = MAX_PCM_BYTES) -> None:
        self._lock = threading.RLock()
        self.used_bytes = 0
        self._audio: dict[int, weakref.ReferenceType[PcmAudio]] = {}
        self._buffers: dict[int, tuple[int, int]] = {}
        self.set_limit(limit)

    def set_limit(self, limit: int) -> None:
        with self._lock:
            if not 0 < limit <= MAX_PCM_BYTES or self.used_bytes > limit:
                raise ValueError("PCM budget must cover existing audio and be at most 64 MiB")
            self.limit = limit

    def reserve(self, size: int) -> None:
        with self._lock:
            if self.used_bytes + size > self.limit:
                raise PcmBudgetExceeded("pcm_budget_exceeded")
            self.used_bytes += size

    def release(self, size: int) -> None:
        with self._lock:
            self.used_bytes -= size

    def admit(self, audio: PcmAudio) -> bool:
        # Weak lifetime tracking follows ownership transfers without queue-specific
        # release paths, including tasks that suppress cancellation. Shared bytes
        # count once even when several PcmAudio wrappers refer to them.
        with self._lock:
            key, buffer_key = id(audio), id(audio.data)
            if key in self._audio:
                return True
            size, references = self._buffers.get(buffer_key, (len(audio.data), 0))
            if not references:
                try:
                    self.reserve(size)
                except PcmBudgetExceeded:
                    return False

            def released(_ref) -> None:
                with self._lock:
                    self._audio.pop(key)
                    buffer_size, count = self._buffers[buffer_key]
                    if count == 1:
                        self._buffers.pop(buffer_key)
                        self.release(buffer_size)
                    else:
                        self._buffers[buffer_key] = (buffer_size, count - 1)

            self._audio[key] = weakref.ref(audio, released)
            self._buffers[buffer_key] = (size, references + 1)
            return True
