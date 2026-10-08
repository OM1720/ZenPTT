FROM zenptt-headless-test:latest

USER root
RUN apt-get update \
    && apt-get install --yes --no-install-recommends \
        ffmpeg=7:7.1.5-0+deb13u1 \
        iproute2=6.15.0-1 \
    && rm -rf /var/lib/apt/lists/*

CMD ["python", "/probe.py"]
