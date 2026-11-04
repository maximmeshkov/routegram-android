FROM ubuntu:24.04@sha256:fe81560990fae86dc4743202b2cb4d3df98d0bcf949173cecef15ce1127ef852
RUN apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
    build-essential pkg-config python3 python3-venv curl ca-certificates unzip \
    libssl-dev libffi-dev zlib1g-dev libbz2-dev liblzma-dev libsqlite3-dev \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /work
ENTRYPOINT ["python3", "/module/tools/build_runtime.py"]
