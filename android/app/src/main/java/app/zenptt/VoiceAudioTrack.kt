// Provides the shared voice-communication AudioTrack configuration.
package app.zenptt

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

internal fun voiceAudioTrackBuilder(): AudioTrack.Builder = AudioTrack.Builder()
    .setAudioAttributes(
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build(),
    )
    .setAudioFormat(
        AudioFormat.Builder()
            .setSampleRate(AudioConstants.PLAYBACK_SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build(),
    )
    .setTransferMode(AudioTrack.MODE_STREAM)
