from array import array
import math

from analyze_browser_pcm import compare


def tone(frequency):
    return array("f", (0.2 * math.sin(2 * math.pi * frequency * i / 48000) for i in range(24000)))


def test_spectral_confirmation_does_not_require_matching_pcm_duration():
    source = tone(700) + tone(1100)
    delayed = array("f", [0]) * 4800 + source + array("f", [0]) * 9600
    assert compare(source, delayed)["signal_confirmed"]


def test_wrong_signal_silence_and_nonfinite_are_not_confirmed():
    source = tone(700)
    for output in (tone(2000), array("f", [0]) * 24000, array("f", [float("nan")])):
        assert not compare(source, output)["signal_confirmed"]
