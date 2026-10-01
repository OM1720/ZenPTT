FROM emscripten/emsdk:4.0.23@sha256:835a0e39f40a84d0b754d946d70c2da62239dad2c9cd5d9316f246a12f399f42 AS codec
WORKDIR /codec
COPY codec/ ./
RUN emcmake cmake -S . -B wasm -DCMAKE_BUILD_TYPE=Release \
    && cmake --build wasm -j 4 \
    && cmake -S . -B native -DCMAKE_BUILD_TYPE=Release -DFETCHCONTENT_SOURCE_DIR_OPUS=/codec/wasm/_deps/opus-src \
    && cmake --build native -j 4 \
    && mkdir /generated \
    && cp wasm/opus.wasm /generated/opus.wasm \
    && cp wasm/_deps/opus-src/COPYING /generated/OPUS-LICENSE.txt

FROM node:24-bookworm-slim@sha256:2fe369e969550cde8e867afc3fe370b260140cab4a23d467074295b42163d553

WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY . .
COPY --from=codec /generated/ ./src/generated/
COPY --from=codec /generated/OPUS-LICENSE.txt ./public/licenses/OPUS-LICENSE.txt
COPY --from=codec /codec/native/native-reference /codec/native-reference
ENV NATIVE_OPUS=/codec/native-reference
RUN npm run check && npm run test:codec

EXPOSE 5173
CMD ["npm", "run", "dev", "--", "--host", "0.0.0.0"]
