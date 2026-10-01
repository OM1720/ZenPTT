// Bridges Kotlin encoder/decoder calls to the pinned mono libopus implementation.
#include <jni.h>
#include <opus.h>

#include <cstdint>
#include <cstdio>
#include <cstring>
#include <vector>

namespace {

constexpr opus_int32 kEncoderSampleRate = 16000;
constexpr opus_int32 kDecoderSampleRate = 48000;
constexpr int kChannels = 1;
constexpr int kEncoderFrameSamples = 320;
constexpr int kDecoderFrameSamples = 960;
constexpr int kMaxPacketBytes = 1275;

void throw_illegal_state(JNIEnv* env, const char* operation, int error) {
    char message[128];
    std::snprintf(message, sizeof(message), "%s failed: %s", operation, opus_strerror(error));
    jclass type = env->FindClass("java/lang/IllegalStateException");
    if (type != nullptr) {
        env->ThrowNew(type, message);
    }
}

template <typename T>
T* handle(jlong value) {
    return reinterpret_cast<T*>(static_cast<intptr_t>(value));
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_app_zenptt_NativeOpus_encoderCreate(JNIEnv* env, jobject) {
    int error = OPUS_OK;
    OpusEncoder* encoder = opus_encoder_create(
        kEncoderSampleRate,
        kChannels,
        OPUS_APPLICATION_VOIP,
        &error
    );
    if (encoder == nullptr || error != OPUS_OK) {
        throw_illegal_state(env, "opus_encoder_create", error);
        return 0;
    }
    opus_encoder_ctl(encoder, OPUS_SET_DTX(0));
    opus_encoder_ctl(encoder, OPUS_SET_SIGNAL(OPUS_SIGNAL_VOICE));
    return static_cast<jlong>(reinterpret_cast<intptr_t>(encoder));
}

extern "C" JNIEXPORT void JNICALL
Java_app_zenptt_NativeOpus_encoderConfigure(
    JNIEnv* env,
    jobject,
    jlong encoder_handle,
    jint bitrate,
    jboolean constrained_vbr
) {
    OpusEncoder* encoder = handle<OpusEncoder>(encoder_handle);
    if (encoder == nullptr) {
        throw_illegal_state(env, "encoder handle", OPUS_BAD_ARG);
        return;
    }
    int result = opus_encoder_ctl(encoder, OPUS_SET_BITRATE(bitrate));
    if (result == OPUS_OK) result = opus_encoder_ctl(encoder, OPUS_SET_VBR(1));
    if (result == OPUS_OK) {
        result = opus_encoder_ctl(
            encoder,
            OPUS_SET_VBR_CONSTRAINT(constrained_vbr == JNI_TRUE ? 1 : 0)
        );
    }
    if (result != OPUS_OK) throw_illegal_state(env, "opus_encoder_ctl", result);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_app_zenptt_NativeOpus_encoderEncode(
    JNIEnv* env,
    jobject,
    jlong encoder_handle,
    jbyteArray pcm_array
) {
    OpusEncoder* encoder = handle<OpusEncoder>(encoder_handle);
    if (encoder == nullptr || env->GetArrayLength(pcm_array) != kEncoderFrameSamples * 2) {
        throw_illegal_state(env, "opus_encode input", OPUS_BAD_ARG);
        return nullptr;
    }
    std::vector<opus_int16> pcm(kEncoderFrameSamples);
    env->GetByteArrayRegion(
        pcm_array,
        0,
        kEncoderFrameSamples * 2,
        reinterpret_cast<jbyte*>(pcm.data())
    );
    unsigned char packet[kMaxPacketBytes];
    int size = opus_encode(encoder, pcm.data(), kEncoderFrameSamples, packet, kMaxPacketBytes);
    if (size < 0) {
        throw_illegal_state(env, "opus_encode", size);
        return nullptr;
    }
    jbyteArray result = env->NewByteArray(size);
    if (result != nullptr) {
        env->SetByteArrayRegion(result, 0, size, reinterpret_cast<const jbyte*>(packet));
    }
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_app_zenptt_NativeOpus_encoderDestroy(JNIEnv*, jobject, jlong encoder_handle) {
    OpusEncoder* encoder = handle<OpusEncoder>(encoder_handle);
    if (encoder != nullptr) opus_encoder_destroy(encoder);
}

extern "C" JNIEXPORT jlong JNICALL
Java_app_zenptt_NativeOpus_decoderCreate(JNIEnv* env, jobject) {
    int error = OPUS_OK;
    OpusDecoder* decoder = opus_decoder_create(kDecoderSampleRate, kChannels, &error);
    if (decoder == nullptr || error != OPUS_OK) {
        throw_illegal_state(env, "opus_decoder_create", error);
        return 0;
    }
    return static_cast<jlong>(reinterpret_cast<intptr_t>(decoder));
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_app_zenptt_NativeOpus_decoderDecode(
    JNIEnv* env,
    jobject,
    jlong decoder_handle,
    jbyteArray packet_array
) {
    OpusDecoder* decoder = handle<OpusDecoder>(decoder_handle);
    if (decoder == nullptr) {
        throw_illegal_state(env, "decoder handle", OPUS_BAD_ARG);
        return nullptr;
    }
    std::vector<unsigned char> packet;
    const unsigned char* data = nullptr;
    int size = 0;
    if (packet_array != nullptr) {
        size = env->GetArrayLength(packet_array);
        if (size <= 0 || size > kMaxPacketBytes) {
            throw_illegal_state(env, "opus_decode input", OPUS_BAD_ARG);
            return nullptr;
        }
        packet.resize(size);
        env->GetByteArrayRegion(
            packet_array,
            0,
            size,
            reinterpret_cast<jbyte*>(packet.data())
        );
        data = packet.data();
    }
    std::vector<opus_int16> pcm(kDecoderFrameSamples);
    int samples = opus_decode(decoder, data, size, pcm.data(), kDecoderFrameSamples, 0);
    if (samples < 0) {
        throw_illegal_state(env, "opus_decode", samples);
        return nullptr;
    }
    const int byte_count = samples * static_cast<int>(sizeof(opus_int16));
    jbyteArray result = env->NewByteArray(byte_count);
    if (result != nullptr) {
        env->SetByteArrayRegion(
            result,
            0,
            byte_count,
            reinterpret_cast<const jbyte*>(pcm.data())
        );
    }
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_app_zenptt_NativeOpus_decoderReset(JNIEnv* env, jobject, jlong decoder_handle) {
    OpusDecoder* decoder = handle<OpusDecoder>(decoder_handle);
    if (decoder == nullptr) {
        throw_illegal_state(env, "decoder handle", OPUS_BAD_ARG);
        return;
    }
    int result = opus_decoder_ctl(decoder, OPUS_RESET_STATE);
    if (result != OPUS_OK) throw_illegal_state(env, "opus_decoder_ctl", result);
}

extern "C" JNIEXPORT void JNICALL
Java_app_zenptt_NativeOpus_decoderDestroy(JNIEnv*, jobject, jlong decoder_handle) {
    OpusDecoder* decoder = handle<OpusDecoder>(decoder_handle);
    if (decoder != nullptr) opus_decoder_destroy(decoder);
}
