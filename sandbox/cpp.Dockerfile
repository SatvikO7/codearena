# =============================================================================
# CodeArena C++ sandbox image
#
# Built ahead of time and never at judging time: the sandbox runs with --network none
# and --pull never, so anything not baked in here simply is not available.
#
# The base is pinned by DIGEST, not by tag. `gcc:13-bookworm` is a moving target — the
# same tag is rebuilt with new package versions — and a sandbox image that changes
# underneath the judge changes the environment submissions run in without anyone
# deciding to. Updating it is a deliberate edit to this line.
# =============================================================================
FROM gcc@sha256:3617a214e52a25bde5375dc9503b5e67f01b6c7322a30137e2790aa8e6db5d1f

# -----------------------------------------------------------------------------
# Strip everything a compiler does not need.
#
# The Debian-based gcc image ships 13 setuid/setgid binaries, among them su, mount,
# passwd and ssh-keysign. `no-new-privileges` already stops a setuid binary from
# raising privilege, so this is defence in depth rather than the only control — but a
# sandbox has no use for any of them, and the cheapest way to not be exploited through
# a program is to not ship it.
#
# Package managers go too. There is no network in a sandbox, so apt could not fetch
# anything anyway; removing it means a program that finds a way to run it cannot even
# try, and it keeps the image from being a convenient staging post.
# -----------------------------------------------------------------------------
RUN set -eux; \
    # Network and remote-access tooling: useless without a network, useful to an attacker.
    rm -rf /usr/bin/ssh /usr/bin/scp /usr/bin/sftp /usr/bin/ssh-agent \
           /usr/bin/ssh-add /usr/bin/ssh-keygen /usr/bin/ssh-keyscan \
           /usr/lib/openssh /usr/bin/curl /usr/bin/wget /usr/bin/nc \
           /usr/bin/netcat /usr/bin/telnet /usr/bin/ftp /usr/bin/rsync; \
    # Package managers and their caches.
    rm -rf /usr/bin/apt /usr/bin/apt-get /usr/bin/apt-cache /usr/bin/apt-key \
           /usr/bin/dpkg-deb /usr/bin/dpkg-split \
           /var/lib/apt /var/cache/apt /var/cache/debconf /usr/share/doc /usr/share/man; \
    # Account-management tools. Nothing in a sandbox creates or changes a user.
    rm -rf /usr/bin/passwd /usr/bin/chsh /usr/bin/chfn /usr/bin/chage \
           /usr/bin/gpasswd /usr/bin/newgrp /usr/bin/expiry /usr/bin/su \
           /usr/sbin/unix_chkpwd /usr/bin/mount /usr/bin/umount; \
    # Whatever setuid/setgid bits survive the removals above.
    find / -xdev \( -perm -4000 -o -perm -2000 \) -type f -exec chmod -s '{}' + 2>/dev/null || true; \
    # Verified at build time, so a base-image change that reintroduces one fails here
    # rather than shipping quietly.
    remaining="$(find / -xdev \( -perm -4000 -o -perm -2000 \) -type f 2>/dev/null | wc -l)"; \
    if [ "$remaining" -ne 0 ]; then \
        echo "FAIL: $remaining setuid/setgid binaries remain:"; \
        find / -xdev \( -perm -4000 -o -perm -2000 \) -type f 2>/dev/null; \
        exit 1; \
    fi; \
    # The compiler must still work after all of that.
    printf '#include <iostream>\nint main(){std::cout<<"ok";}\n' > /tmp/probe.cpp; \
    g++ -O2 -std=c++17 -static -o /tmp/probe /tmp/probe.cpp; \
    /tmp/probe | grep -q ok; \
    rm -f /tmp/probe /tmp/probe.cpp

# The image carries no credentials, no build cache and no source. Containers are created
# with --user 65534:65534, --read-only and an explicit environment, so nothing below the
# entrypoint depends on what this image sets.

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
ENV GPG_KEYS="" GCC_MIRRORS="" GCC_VERSION="" LANGUAGE=""

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

LABEL com.codearena.sandbox.language="cpp"
