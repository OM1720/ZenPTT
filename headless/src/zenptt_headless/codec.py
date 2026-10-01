"""Minimal ctypes binding for the system libopus."""

from __future__ import annotations

import ctypes
import ctypes.util

from .protocol import MAX_PACKET_BYTES
from .types import FRAME_BYTES, FRAME_SAMPLES

OPUS_APPLICATION_VOIP = 2048
OPUS_SET_BITRATE = 4002
OPUS_SET_VBR = 4006
OPUS_SET_VBR_CONSTRAINT = 4020
OPUS_SET_DTX = 4016


def _library() -> ctypes.CDLL:
    name = ctypes.util.find_library("opus")
    if not name:
        raise RuntimeError("libopus is required")
    return ctypes.CDLL(name)


class OpusEncoder:
    def __init__(self) -> None:
        self._lib = _library()
        self._lib.opus_encoder_create.restype = ctypes.c_void_p
        self._lib.opus_encoder_create.argtypes = [
            ctypes.c_int,
            ctypes.c_int,
            ctypes.c_int,
            ctypes.POINTER(ctypes.c_int),
        ]
        self._lib.opus_encoder_ctl.argtypes = [ctypes.c_void_p, ctypes.c_int]
        self._lib.opus_encode.argtypes = [
            ctypes.c_void_p,
            ctypes.POINTER(ctypes.c_int16),
            ctypes.c_int,
            ctypes.POINTER(ctypes.c_ubyte),
            ctypes.c_int32,
        ]
        self._lib.opus_encoder_destroy.argtypes = [ctypes.c_void_p]
        error = ctypes.c_int()
        self._state = self._lib.opus_encoder_create(16_000, 1, OPUS_APPLICATION_VOIP, ctypes.byref(error))
        if not self._state or error.value != 0:
            raise RuntimeError(f"Cannot create Opus encoder: {error.value}")
        for request, value in ((OPUS_SET_BITRATE, 16_000), (OPUS_SET_VBR, 1), (OPUS_SET_VBR_CONSTRAINT, 1), (OPUS_SET_DTX, 0)):
            if self._lib.opus_encoder_ctl(self._state, request, value) != 0:
                self.close()
                raise RuntimeError("Cannot configure Opus encoder")

    def encode(self, pcm: bytes) -> bytes:
        if len(pcm) != FRAME_BYTES:
            raise ValueError("Opus input must be one 20 ms PCM frame")
        samples = (ctypes.c_int16 * FRAME_SAMPLES).from_buffer_copy(pcm)
        output = (ctypes.c_ubyte * MAX_PACKET_BYTES)()
        size = self._lib.opus_encode(self._state, samples, FRAME_SAMPLES, output, len(output))
        if size < 0:
            raise RuntimeError(f"Opus encode failed: {size}")
        return bytes(output[:size])

    def close(self) -> None:
        if getattr(self, "_state", None):
            self._lib.opus_encoder_destroy(self._state)
            self._state = None


class OpusDecoder:
    def __init__(self) -> None:
        self._lib = _library()
        self._lib.opus_decoder_create.restype = ctypes.c_void_p
        self._lib.opus_decoder_create.argtypes = [
            ctypes.c_int,
            ctypes.c_int,
            ctypes.POINTER(ctypes.c_int),
        ]
        self._lib.opus_decode.argtypes = [
            ctypes.c_void_p,
            ctypes.POINTER(ctypes.c_ubyte),
            ctypes.c_int32,
            ctypes.POINTER(ctypes.c_int16),
            ctypes.c_int,
            ctypes.c_int,
        ]
        self._lib.opus_decoder_destroy.argtypes = [ctypes.c_void_p]
        error = ctypes.c_int()
        self._state = self._lib.opus_decoder_create(16_000, 1, ctypes.byref(error))
        if not self._state or error.value != 0:
            raise RuntimeError(f"Cannot create Opus decoder: {error.value}")

    def decode(self, packet: bytes) -> bytes:
        output = (ctypes.c_int16 * FRAME_SAMPLES)()
        source = (ctypes.c_ubyte * len(packet)).from_buffer_copy(packet)
        samples = self._lib.opus_decode(self._state, source, len(packet), output, FRAME_SAMPLES, 0)
        if samples != FRAME_SAMPLES:
            raise RuntimeError(f"Opus decode failed: {samples}")
        return bytes(output)

    def close(self) -> None:
        if getattr(self, "_state", None):
            self._lib.opus_decoder_destroy(self._state)
            self._state = None
