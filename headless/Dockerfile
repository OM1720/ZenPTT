FROM python:3.12-slim@sha256:57cd7c3a7a273101a6485ba99423ee568157882804b1124b4dd04266317710de

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

WORKDIR /app

RUN apt-get update \
    && apt-get install --yes --no-install-recommends \
        libopus0=1.5.2-2 \
        ca-certificates=20250419 \
    && rm -rf /var/lib/apt/lists/*

COPY pyproject.toml requirements.lock ./
COPY src ./src
RUN pip install --no-cache-dir --require-hashes -r requirements.lock

ENV PYTHONPATH=/app/src

RUN useradd --uid 10001 --no-create-home appuser
USER appuser

CMD ["python", "-m", "zenptt_headless.echo_supervisor"]
