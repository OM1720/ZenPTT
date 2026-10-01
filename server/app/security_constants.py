"""Centralizes reviewed security and capacity ceilings for server configuration."""

# Environment configuration may lower these ceilings. Raising them requires
# reviewing this file and rebuilding the server image.

# Runtime capacity and WebSocket traffic limits are the values most likely to be tuned.

# Maximum simultaneous WebSocket sessions accepted by one server process.
# The transport keeps a small connection reserve for health checks and downloads.
MAX_ACTIVE_SESSIONS = 180

# Maximum simultaneous WebSocket sessions accepted from one client IP.
MAX_ACTIVE_SESSIONS_PER_CLIENT_IP = 30

# Maximum control messages accepted from one client during one second.
MAX_CONTROL_MESSAGES_PER_SECOND = 50

# Maximum audio media envelopes accepted from one client during one second.
# Frame pacing and burst bounds independently prevent synthetic future audio.
MAX_AUDIO_MESSAGES_PER_SECOND = 200

# Diagnostics intake and concurrency limits are adjusted less often than traffic capacity.

# Maximum diagnostics uploads accepted globally during one minute.
DIAGNOSTICS_MAX_UPLOADS_PER_MINUTE = 30

# Maximum diagnostics uploads accepted from one client IP during one minute.
DIAGNOSTICS_MAX_UPLOADS_PER_CLIENT_PER_MINUTE = 10

# Maximum diagnostics filesystem writes run concurrently.
DIAGNOSTICS_MAX_CONCURRENT_SAVES = 2

# Diagnostics storage and retention limits are adjusted less often than intake capacity.

# Maximum accepted diagnostics request body.
DIAGNOSTICS_MAX_BODY_BYTES = 131_072

# Maximum diagnostics reports retained on disk.
DIAGNOSTICS_MAX_REPORTS = 500

# Maximum diagnostics report retention period.
DIAGNOSTICS_RETENTION_SECONDS = 604_800

# User-facing product limits change only with a product decision.

# Maximum participants accepted in one channel.
MAX_CHANNEL_PARTICIPANTS = 12

# Protocol compatibility limits are expected to change least often.

# Maximum accepted channel-code length.
MAX_CHANNEL_CODE_LENGTH = 256
