# One stage: the binary is already built, and copying it in is the only job.
#
# The base is alpine (about 3 MB) rather than a Go SDK image (about 800 MB)
# because nothing here compiles. The build happens on the developer's machine
# with `pwsh -File tools/tuping.ps1 -Tasks dist`, which is what makes the NAS not
# need a Go toolchain at all (docs/tuping-design.md §11.5).
FROM alpine:3.20

# ca-certificates so an outbound HTTPS call would work if one is ever added;
# tzdata so TZ below can mean something. Both are small and both are the kind of
# thing whose absence is diagnosed slowly.
RUN apk add --no-cache ca-certificates tzdata && \
    adduser -D -H -u 10001 tuping && \
    mkdir -p /data

# TARGETARCH is set by BuildKit to the platform being built (amd64, arm64, …), which is how one
# Dockerfile serves both published architectures. It also removes the rename step the README used
# to require: the deployer uploads the binary that matches their NAS and nothing else (§17.22).
#
# A builder without BuildKit leaves TARGETARCH empty and the COPY fails loudly, naming the file it
# wanted — which is the right outcome: silently picking amd64 for an ARM NAS would produce
# `exec format error` in the container log instead.
ARG TARGETARCH
COPY tupingd-linux-${TARGETARCH} /usr/local/bin/tupingd
RUN chmod +x /usr/local/bin/tupingd && chown -R tuping:tuping /data

# The database and the thumbnails both live here and both must survive a rebuild.
VOLUME ["/data"]

# NO `USER tuping` HERE, and that is the point (docs/tuping-design.md §17.21).
#
# The image starts as root so that the binary can take ownership of /data and then drop
# root for good before it opens the database: a bind-mounted directory is owned by
# whoever the HOST says, which is never the uid in this image, and asking every deployer
# to chown it is asking them to know what a uid is. The process that serves HTTP is
# still uid 10001 — it says so in the log ("running unprivileged"), and the tupingd data
# directory ends up owned by it either way.
#
# The chown above still matters: a named or anonymous volume is initialised from this
# layer, ownership included, so that case works without any privileged step at all.
EXPOSE 8080

# TZ is why the log timestamps read 17:42 and not 09:42: tzdata alone only means the zone
# *can* be resolved, and Go asks for it through this variable. Overridable from the
# deployment (`TZ:` in compose) for anybody outside this time zone. Fixed on 2026-10-11,
# when a real deployment's first log line came back stamped UTC and had to be subtracted
# by hand to line up with the NAS's own clock.
ENV TZ="Asia/Shanghai" \
    TUPING_ADDR=":8080" \
    TUPING_DB="/data/tuping.db" \
    TUPING_THUMBS="/data/thumbs"

# The image tag is the version; -ldflags -X main.version carries it into the
# binary, so /healthz can say which build answered.
HEALTHCHECK --interval=60s --timeout=5s --start-period=5s \
    CMD wget -q -O - http://127.0.0.1:8080/healthz >/dev/null 2>&1 || exit 1

ENTRYPOINT ["/usr/local/bin/tupingd"]
