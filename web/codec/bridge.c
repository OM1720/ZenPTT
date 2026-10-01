/* Exposes fixed-frame libopus encoding and decoding to the browser WASM worklet. */
#include <opus.h>

/* Fixed buffers and resettable codec instances avoid allocation per audio frame. */
static OpusEncoder *encoder;
static OpusDecoder *decoder;
static float input[320];
static float output[960];
static unsigned char packet[1275];

int zen_init(void) {
    int error;
    encoder = opus_encoder_create(16000, 1, OPUS_APPLICATION_VOIP, &error);
    if (!encoder || error) return -1;
    decoder = opus_decoder_create(48000, 1, &error);
    if (!decoder || error) return -1;
    if (opus_encoder_ctl(encoder, OPUS_SET_BITRATE(16000)) ||
        opus_encoder_ctl(encoder, OPUS_SET_VBR(1)) ||
        opus_encoder_ctl(encoder, OPUS_SET_VBR_CONSTRAINT(1)) ||
        opus_encoder_ctl(encoder, OPUS_SET_DTX(0)) ||
        opus_encoder_ctl(encoder, OPUS_SET_SIGNAL(OPUS_SIGNAL_VOICE))) return -1;
    return 0;
}
float *zen_input(void) { return input; }
float *zen_output(void) { return output; }
unsigned char *zen_packet(void) { return packet; }
int zen_encoder_reset(void) { return opus_encoder_ctl(encoder, OPUS_RESET_STATE); }
int zen_decoder_reset(void) { return opus_decoder_ctl(decoder, OPUS_RESET_STATE); }
int zen_encode(void) { return opus_encode_float(encoder, input, 320, packet, 1275); }
int zen_decode(int size) {
    if (size < 0 || size > 1275) return OPUS_BAD_ARG;
    if (size && (opus_packet_get_nb_channels(packet) != 1 ||
        opus_packet_get_nb_samples(packet, size, 48000) != 960)) return OPUS_INVALID_PACKET;
    return opus_decode_float(decoder, size ? packet : 0, size, output, 960, 0);
}
