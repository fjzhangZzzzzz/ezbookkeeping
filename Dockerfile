# Build backend binary file
FROM golang:1.27.4-alpine3.24@sha256:ea30e25680a5470bac02b08bb0e0a735fb75bd0ab7d431b9b95ee0abef362586 AS be-builder
ARG RELEASE_BUILD
ARG BUILD_PIPELINE
ARG BUILD_COMMIT
ARG BUILD_UNIXTIME
ARG BUILD_DATE
ARG CHECK_3RD_API
ARG SKIP_TESTS
ENV RELEASE_BUILD=$RELEASE_BUILD
ENV BUILD_PIPELINE=$BUILD_PIPELINE
ENV BUILD_COMMIT=$BUILD_COMMIT
ENV BUILD_UNIXTIME=$BUILD_UNIXTIME
ENV BUILD_DATE=$BUILD_DATE
ENV CHECK_3RD_API=$CHECK_3RD_API
ENV SKIP_TESTS=$SKIP_TESTS
WORKDIR /go/src/github.com/mayswind/ezbookkeeping
COPY . .
RUN docker/backend-build-pre-setup.sh
RUN apk add git gcc g++ libc-dev
RUN ./build.sh backend

# Build frontend files
FROM --platform=$BUILDPLATFORM node:26.13.0-alpine3.24@sha256:b2646ba6d774dc22c24d89fc37103fcf4c4467e870c78b9519aa403300b2835a AS fe-builder
ARG RELEASE_BUILD
ARG BUILD_PIPELINE
ARG BUILD_COMMIT
ARG BUILD_UNIXTIME
ARG BUILD_DATE
ARG BUILD_NODE_OPTIONS
ENV RELEASE_BUILD=$RELEASE_BUILD
ENV BUILD_PIPELINE=$BUILD_PIPELINE
ENV BUILD_COMMIT=$BUILD_COMMIT
ENV BUILD_UNIXTIME=$BUILD_UNIXTIME
ENV BUILD_DATE=$BUILD_DATE
ENV NODE_OPTIONS=$BUILD_NODE_OPTIONS
WORKDIR /go/src/github.com/mayswind/ezbookkeeping
COPY . .
RUN docker/frontend-build-pre-setup.sh
RUN apk add git
RUN ./build.sh frontend

# Package docker image
FROM alpine:3.24.3@sha256:c26612752c78b18faacd63c7d50f4592c2e7b0f25e0e20e82bd74e465c57ab0d
LABEL maintainer="MaysWind <i@mayswind.net>"
RUN addgroup -S -g 1000 ezbookkeeping && adduser -S -G ezbookkeeping -u 1000 ezbookkeeping
RUN apk --no-cache add tzdata
COPY docker/docker-entrypoint.sh /docker-entrypoint.sh
RUN chmod +x /docker-entrypoint.sh
RUN mkdir -p /ezbookkeeping && chown 1000:1000 /ezbookkeeping \
  && mkdir -p /ezbookkeeping/data && chown 1000:1000 /ezbookkeeping/data \
  && mkdir -p /ezbookkeeping/log && chown 1000:1000 /ezbookkeeping/log \
  && mkdir -p /ezbookkeeping/storage && chown 1000:1000 /ezbookkeeping/storage
RUN mkdir -p /ezbookkeeping/public/android && chown 1000:1000 /ezbookkeeping/public/android
WORKDIR /ezbookkeeping
COPY --from=be-builder --chown=1000:1000 /go/src/github.com/mayswind/ezbookkeeping/ezbookkeeping /ezbookkeeping/ezbookkeeping
COPY --from=fe-builder --chown=1000:1000 /go/src/github.com/mayswind/ezbookkeeping/dist /ezbookkeeping/public
COPY --chown=1000:1000 conf /ezbookkeeping/conf
COPY --chown=1000:1000 templates /ezbookkeeping/templates
COPY --chown=1000:1000 LICENSE /ezbookkeeping/LICENSE
USER 1000:1000
EXPOSE 8080
ENTRYPOINT ["/docker-entrypoint.sh"]
