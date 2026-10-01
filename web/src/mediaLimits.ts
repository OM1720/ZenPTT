// Defines shared browser audio frame, burst, and Opus packet limits.
export const FRAME_DURATION_MS = 20
export const MAX_BURST_FRAMES = 3000
export const MAX_BURST_DURATION_MS = MAX_BURST_FRAMES * FRAME_DURATION_MS
export const MAX_PACKET_BYTES = 1275
