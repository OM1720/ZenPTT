/* Provides an independent native Opus path for browser codec interoperability checks. */
#include <opus.h>
#include <stdio.h>
#include <string.h>

/* Independent native API path; files contain little-endian uint16 lengths and packets. */
int main(int argc, char **argv) {
    if (argc != 4) return 1;
    FILE *source = fopen(argv[2], "rb"), *target = fopen(argv[3], "wb");
    if (!source || !target) return 2;
    int error;
    unsigned char packet[1275];
    if (!strcmp(argv[1], "encode")) {
        OpusEncoder *encoder = opus_encoder_create(16000, 1, OPUS_APPLICATION_VOIP, &error);
        if (!encoder || error) return 3;
        opus_encoder_ctl(encoder, OPUS_SET_BITRATE(16000));
        opus_encoder_ctl(encoder, OPUS_SET_VBR_CONSTRAINT(1));
        opus_encoder_ctl(encoder, OPUS_SET_SIGNAL(OPUS_SIGNAL_VOICE));
        float pcm[320];
        while (fread(pcm, sizeof(float), 320, source) == 320) {
            int size = opus_encode_float(encoder, pcm, 320, packet, sizeof(packet));
            if (size <= 0) return 4;
            fputc(size & 255, target); fputc(size >> 8, target);
            if (fwrite(packet, 1, size, target) != (size_t)size) return 5;
        }
        opus_encoder_destroy(encoder);
    } else if (!strcmp(argv[1], "decode")) {
        OpusDecoder *decoder = opus_decoder_create(48000, 1, &error);
        if (!decoder || error) return 3;
        int low;
        while ((low = fgetc(source)) != EOF) {
            int high = fgetc(source), size = low + (high << 8);
            if (high < 0 || size < 1 || size > 1275 || fread(packet, 1, size, source) != (size_t)size) return 4;
            float pcm[960];
            if (opus_decode_float(decoder, packet, size, pcm, 960, 0) != 960) return 5;
            if (fwrite(pcm, sizeof(float), 960, target) != 960) return 6;
        }
        opus_decoder_destroy(decoder);
    } else return 7;
    fclose(source);
    return fclose(target) ? 8 : 0;
}
