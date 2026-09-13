# =============================================================================
# CodeArena Python sandbox image
#
# Base pinned by digest; see the note in cpp.Dockerfile for why a tag is not enough.
# pip is removed outright: a sandbox has no network, so the only thing an installer
# could do here is surprise somebody.
# =============================================================================
FROM python@sha256:b64631e04e4920160c50fbe8d8df828f7f35f06f425cb44aa09bca53e708a35a

RUN set -eux; \
    rm -rf /sbin/apk /etc/apk /lib/apk /usr/share/apk /var/cache/apk \
           /usr/bin/wget /usr/bin/curl /usr/bin/nc /usr/bin/ssh; \
    # pip, setuptools and the wheel cache. Nothing is installed at judging time.
    rm -rf /usr/local/bin/pip /usr/local/bin/pip3 /usr/local/bin/pip3.12 \
           /usr/local/lib/python3.12/site-packages/pip \
           /usr/local/lib/python3.12/site-packages/setuptools \
           /usr/local/lib/python3.12/ensurepip \
           /root/.cache /usr/local/lib/python3.12/idlelib; \
    find / -xdev \( -perm -4000 -o -perm -2000 \) -type f -exec chmod -s '{}' + 2>/dev/null || true; \
    remaining="$(find / -xdev \( -perm -4000 -o -perm -2000 \) -type f 2>/dev/null | wc -l)"; \
    if [ "$remaining" -ne 0 ]; then \
        echo "FAIL: $remaining setuid/setgid binaries remain:"; \
        find / -xdev \( -perm -4000 -o -perm -2000 \) -type f 2>/dev/null; \
        exit 1; \
    fi; \
    python3 -c 'import json, math, itertools, collections, heapq, bisect, re, decimal, fractions; print("ok")' | grep -q ok


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
ENV GPG_KEY="" PYTHON_VERSION="" PYTHON_SHA256="" LANGUAGE=""

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

LABEL com.codearena.sandbox.language="python"
