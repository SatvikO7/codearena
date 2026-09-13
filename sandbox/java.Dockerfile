# =============================================================================
# CodeArena Java sandbox image
#
# Base pinned by digest; see the note in cpp.Dockerfile for why a tag is not enough.
# The Alpine JDK ships no setuid binaries, so this image mostly removes tooling and
# then asserts that the absence still holds.
# =============================================================================
FROM eclipse-temurin@sha256:6ea5548706b60ac0a602eaf48af74792cbab012d90e811ca8db6184b16b5c3d6

RUN set -eux; \
    # apk is the package manager; wget is BusyBox's downloader. Neither can reach
    # anything under --network none, and neither has any business in a sandbox.
    rm -rf /sbin/apk /etc/apk /lib/apk /usr/share/apk /var/cache/apk \
           /usr/bin/wget /usr/bin/curl /usr/bin/nc /usr/bin/ssh; \
    find / -xdev \( -perm -4000 -o -perm -2000 \) -type f -exec chmod -s '{}' + 2>/dev/null || true; \
    remaining="$(find / -xdev \( -perm -4000 -o -perm -2000 \) -type f 2>/dev/null | wc -l)"; \
    if [ "$remaining" -ne 0 ]; then \
        echo "FAIL: $remaining setuid/setgid binaries remain:"; \
        find / -xdev \( -perm -4000 -o -perm -2000 \) -type f 2>/dev/null; \
        exit 1; \
    fi; \
    # The toolchain must still compile and run after the removals.
    mkdir -p /tmp/probe; \
    printf 'public class Probe { public static void main(String[] a){ System.out.print("ok"); } }\n' > /tmp/probe/Probe.java; \
    javac -d /tmp/probe /tmp/probe/Probe.java; \
    java -cp /tmp/probe Probe | grep -q ok; \
    rm -rf /tmp/probe


# -----------------------------------------------------------------------------
# Blank the build metadata the base image sets.
#
# `docker run --env` ADDS to an image's environment, it does not replace it, so
# anything the base declares reaches the sandbox however carefully the run command is
# constructed. None of these are secret -- they are public build constants -- but a
# sandbox environment that varies with the base image is one more thing a verdict can
# depend on, and one more thing to re-check every time the base is updated.
#
# A Dockerfile cannot delete an inherited variable, only blank its value. The name
# survives; nothing useful remains in it.
# -----------------------------------------------------------------------------
# JAVA_HOME is kept: the JDK genuinely needs it.
ENV JAVA_VERSION="" LANGUAGE=""

# -----------------------------------------------------------------------------
# The workspace directory must exist in the image.
#
# A sandbox mounts a per-submission volume at /work, and the source is staged with
# `docker cp` into the container while it is still *created* rather than started -- at
# which point the volume is not yet mounted and the path resolves inside the image. If
# /work does not exist there, `docker cp` intermittently fails with "destination must be
# a directory" under concurrent load, and the submission dies as a SYSTEM_ERROR.
#
# Creating it here removes the race rather than retrying it: there is always a real
# directory to copy into, whatever the mount timing.
# -----------------------------------------------------------------------------
RUN mkdir -p /work && chmod 755 /work

LABEL com.codearena.sandbox.language="java"
