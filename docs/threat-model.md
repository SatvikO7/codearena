# Threat model: executing untrusted code

CodeArena compiles and runs programs written by strangers. This document says what that
threat actually is, what protects against it, and — the part most such documents skip — what
does **not**.

Every control listed as **verified** is backed by a test that runs a real malicious program
against a real sandbox and asserts what happened. Controls that are configured but not
verified say so. Limitations are not tucked into a footnote.

---

## 1. The attacker

**Assume submitted source code is fully hostile.** Not "mostly students", not "probably
fine". The design assumes the submitter is trying to attack the judge, and that they can
submit as many times as they like.

An attacker can:

- write arbitrary C++, Java or Python, including inline assembly and raw syscalls
- run arbitrary code at **compile** time (template metaprogramming, preprocessor abuse) as
  well as at run time
- submit repeatedly, observing timing and verdicts to infer what happened
- craft source that is pathological rather than merely wrong — a fork bomb, an allocation
  storm, an infinite loop, a program that writes until the disk is gone

An attacker **cannot**:

- choose the image, the command line, the container flags, the filenames or the limits —
  none of these are derived from a request (see §4)
- reach the API server, the database or the queue, which are on networks the sandbox has no
  interface to
- see another submission, its source, or a problem's hidden test data

### What the attacker wants

| Goal | Why it matters |
|---|---|
| **Read hidden test data** | The answer key. Reading it makes the judge worthless. |
| **Read another user's source** | Other people's work, and a plagiarism vector. |
| **Reach the host** | Container escape: the whole machine. |
| **Reach the database or Redis** | Every account, every submission, every verdict. |
| **Obtain credentials** | Database, Redis, the executor token, cloud metadata. |
| **Deny service** | Exhaust CPU, memory, disk or processes so nobody else is judged. |
| **Influence a verdict** | Make a wrong program pass, or a right one fail. |

---

## 2. Protected assets

1. **Hidden test inputs and expected outputs** — never enter a sandbox at all. Only the
   input crosses the boundary, on stdin; comparison happens in the worker. A program that
   could read the expected output could print it.
2. **Other users' source code** — never on the same machine at the same time as another
   submission's sandbox can see it. Each workspace is a separate volume.
3. **Credentials** — database, Redis, executor token. None are in the sandbox's environment
   (§7) or on a filesystem it can reach.
4. **The host** — kernel, filesystem, Docker daemon, other containers.
5. **Judge availability** — one submission must not deny the judge to everybody else.
6. **Verdict integrity** — a verdict must depend on the program and the tests, not on the
   host's timezone, locale, load or what ran before it.

---

## 3. Trust boundaries

```
  Browser ─────► API server ─────► PostgreSQL / Redis
  (hostile)      (trusted)          (trusted)
                      │
                      ▼
                 Judge worker                 ── holds: DB, Redis, a token
                 (trusted, but handles            does NOT hold: Docker
                  untrusted program output)
                      │  HTTP, one shared secret, private network
                      ▼
              Execution service                ── holds: the Docker socket
              (trusted, minimal)                   does NOT hold: DB, Redis, user data
                      │  Docker API
                      ▼
              Sandbox container                ── holds: nothing
              (FULLY HOSTILE)
```

The boundary that matters most is the bottom one: **everything inside a sandbox container is
assumed to be the attacker.** The boundary that changed in Phase 6 is the one above it.

### Why the worker no longer holds the socket

Before Phase 6 the judge worker held `/var/run/docker.sock`. That made the process which
parses untrusted program output, connects to PostgreSQL and Redis, and runs judging logic
*also* the process that could start a privileged container mounting the host filesystem. Any
remote-code-execution bug anywhere in the worker was a host compromise.

The Docker socket is now held only by the **execution service**, which offers four
operations: prepare a workspace for one of three languages, compile it, run it, discard it.
There is no field in that contract for an image, a mount, a capability, a network, a user or
a command — so a worker compromised completely inherits "can ask for a sandboxed program to
be run", not host control. Limits are clamped server-side, so it cannot even ask for an
unreasonable one. See ADR-028.

**This does not make the socket safe.** Whatever holds it is host-equivalent if *it* is
compromised. The residual risk is §7.1.

---

## 4. Attack surface: what an attacker actually controls

The only attacker-controlled inputs that reach the execution path are:

| Input | Constraint |
|---|---|
| Source code | Bytes in a file with a **fixed name** (`main.cpp`, `Main.java`, `main.py`). Written via `docker cp` reading a tar stream from stdin — never on a command line. Size-capped. |
| Language | An **enum**. An unknown value fails to deserialise before any code selects an image. |
| Test input | Written to the container's **stdin**. Never a filename, never an argument. |

Everything else — image, entrypoint, argv, flags, mounts, limits, filenames, working
directory — is a compile-time constant in `LanguageSpec` and `SandboxPolicy`.

**There is no shell anywhere in the execution path.** Commands are `List<String>` argv arrays
handed to a process builder. A source file containing `"; rm -rf /"` is a file whose name
never appears on a command line and whose contents are only ever read by a compiler inside a
throwaway container.

---

## 5. Controls, and the evidence for each

Tests named below are in `executor/src/test/java/com/codearena/executor/sandbox/`.

### 5.1 Network — VERIFIED

| Control | Evidence |
|---|---|
| `--network none`: no interface but loopback | `deniesNetworkAccess`, `cannotReachOtherContainersOnTheHostNetwork` |
| No DNS resolution | `cannotResolveDns` |
| No UDP | `cannotSendUdp` |
| Cannot reach PostgreSQL, Redis, the executor, or the Docker daemon over TCP | `cannotReachOtherContainersOnTheHostNetwork` |
| No registry access while judging (`--pull never`) | `SandboxPolicyTest.neverReachesARegistryWhileJudging` |

Compiler and runtime dependencies are baked into the sandbox images. Judging needs no
internet access, and cannot obtain any.

### 5.2 Privilege — VERIFIED

| Control | Evidence |
|---|---|
| Runs as uid 65534 (`nobody`), never root | `runsUnprivilegedAndCannotEscalate` asserts `uid 65534` |
| `--cap-drop ALL`: no capabilities held or obtainable | same test asserts `CapEff`, `CapPrm` **and** `CapBnd` are all zero |
| `no-new-privileges`: setuid cannot raise privilege | same test asserts `NoNewPrivs: 1` |
| `setuid(0)` fails | same test |
| No setuid/setgid binaries in any sandbox image | asserted **at image build time**; the build fails if one appears |
| `--privileged` is never used | `SandboxPolicyTest.runsAsNobodyRatherThanRoot` |

### 5.3 Syscalls — VERIFIED

A custom seccomp profile (`sandbox/seccomp/codearena.json`) replaces Docker's default with a
**narrower allowlist**: anything not named returns `EPERM`.

Built from observed requirements, not guesswork — the allowlist was derived by running real
C++, Java and Python workloads under it and is kept honest by the language tests, which fail
if a needed syscall is removed.

Denied and worth naming: `ptrace`, `process_vm_readv/writev`, `kcmp`, `mount`, `umount2`,
`pivot_root`, `chroot`, `move_mount`, `open_tree`, `name_to_handle_at`,
`open_by_handle_at`, `unshare`, `setns`, `bpf`, `perf_event_open`, `userfaultfd`,
`io_uring_*`, `capset`, `pidfd_getfd`, `init_module`, `kexec_load`, `keyctl`, `swapon`,
`quotactl`, `syslog`, `settimeofday`, `ioperm`, `iopl`.

**Evidence it is applied and stricter than the default**: `seccompDeniesSyscallsTheDefaultProfileAllows`
asserts `ptrace` returns `EPERM`. Under Docker's default profile the same call succeeds
(measured: `rc=0`), so this test fails if the profile is ever silently not applied.

**Networking syscalls are deliberately allowed.** `--network none` gives the container no
interface, which is a stronger and far less brittle control than filtering `socket()` — and
both the JVM and CPython create sockets during ordinary startup.

**Fallback:** if the profile file cannot be read, the sandbox uses the daemon's default
profile — weaker than ours, but still an allowlist, never "no filtering". The condition is
logged at WARN and reported on the health endpoint, rather than degrading silently.

### 5.4 Filesystem — VERIFIED

| Control | Evidence |
|---|---|
| `--read-only` root filesystem | `cannotWriteOutsideTmp`, `givesTheProgramAReadOnlyRootFilesystem` |
| Workspace mounted **read-only during runs** | `cannotWriteOutsideTmp` (even `/work` refuses writes) |
| Only `/tmp` is writable, and it is `noexec,nosuid,nodev` and size-capped | `SandboxPolicyTest.makesTheWritableDirectoryNonExecutable` |
| No host mount of any kind | `SandboxPolicyTest.mountsNothingFromTheHost` |
| Cannot read host, executor or database files | `cannotReachHostOrServiceFilesystems` |
| Path traversal and symlinks cannot escape | `cannotEscapeTheWorkspaceByTraversalOrSymlink` |
| Cannot see the Docker socket, or a client for it | `cannotSeeTheDockerSocketOrAClientForIt` |

`noexec` matters more than it looks: without it a program can write a payload into the only
writable directory it has and then execute it.

### 5.5 Resources — VERIFIED

| Limit | Mechanism | Evidence |
|---|---|---|
| Wall clock | container killed at the deadline | `killsAProgramThatExceedsItsWallClockBudget` |
| Memory | `--memory` with `--memory-swap` **equal** (no swap) | `killsAProgramThatExceedsItsMemoryLimit` |
| CPU | `--cpus` quota | `cpuQuotaIsNotBypassedBySpawningMoreProcesses` |
| Processes | `--pids-limit` (per cgroup) | `containsAForkBomb` |
| Threads | same `--pids-limit` | `boundsThreadCreationAsWellAsProcesses` |
| Output | bounded reader, container killed on overflow | `stopsAProgramThatFloodsItsOutput` |
| File size | `RLIMIT_FSIZE` | mapped to a verdict, see §5.7 |
| Open files | `RLIMIT_NOFILE` | configured |
| Core dumps | `RLIMIT_CORE=0` | configured |
| Source size | capped before a workspace is created | `ExecutionController` |

Every one is enforced **during** execution by the kernel, not measured afterwards. Measuring
afterwards is useless against the programs that matter: an infinite loop never finishes to be
measured, and a runaway allocation takes the host down before any bookkeeping runs.

**CPU semantics.** `--cpus` is a quota over a period, applied to the whole cgroup. Extra
processes and threads share that quota rather than multiplying it — verified by running the
same fixed CPU workload once and then four times in parallel under the same 0.5-CPU quota and
asserting the second takes materially longer.

**Memory semantics.** Swap is set equal to memory, so the ceiling is real rather than
something the kernel pages around. Exceeding it is an OOM kill, which is detected by asking
the daemon (`State.OOMKilled`) rather than by guessing from exit code 137 — 137 is also what
a timeout kill looks like, and reporting MEMORY_LIMIT_EXCEEDED for an infrastructure kill
would blame the submitter for our own behaviour.

**Memory is enforced but not measured.** No endpoint reports how much a program used, and
`memoryKb` was removed from the API rather than shipped as an always-null field (ADR-026).

### 5.6 Process lifecycle — VERIFIED

- A timeout removes the **container**, which kills every process in its cgroup. A child that
  outlived its parent dies with it: `leavesNoProcessOrContainerBehindAfterATimeout` forks a
  detached child and asserts no container survives.
- `--init` makes PID 1 a real reaper, so forked-and-exited children do not accumulate as
  zombies inside a run.
- Containers are removed in a `finally` on **every** path — every verdict, every exception,
  every interruption.
- `SandboxReaper` is the backstop for the case a `finally` cannot cover (the process is
  killed between starting a container and removing it). It sweeps at startup and on a timer,
  matching only its own label, and is carefully constructed so it cannot eat a live
  execution: volumes must be *dangling* **and** older than a grace period; running containers
  are only reaped at startup, when by definition nothing of ours is legitimately running.
- `WorkspaceRegistry` closes workspaces whose caller disappeared, and closes all of them on
  shutdown.

### 5.7 Verdict integrity — VERIFIED

A sandbox failure must never be reported as a fault in the submitted code.

| Situation | Verdict |
|---|---|
| Program exceeded wall clock | `TIME_LIMIT_EXCEEDED` |
| Kernel OOM-killed it (confirmed via `OOMKilled`) | `MEMORY_LIMIT_EXCEEDED` |
| Program wrote too much output | `RUNTIME_ERROR`, with the reason |
| Program hit `RLIMIT_FSIZE` | `RUNTIME_ERROR`, with the reason |
| Compiler failed, timed out, ran out of memory, or hit a file limit | `COMPILATION_ERROR` |
| **Executor unreachable, full, or erroring** | `SYSTEM_ERROR` — never a code verdict |
| Docker unreachable | `SYSTEM_ERROR` |

`RemoteExecutionServiceTest.mapsEveryExecutorFailureToAnInfrastructureFailure` asserts this
for a 500, a 404, a 401, a 503 and a truncated body.

**Determinism.** Locale (`LANG`, `LC_ALL`, `LANGUAGE`), timezone, `PATH`, `HOME`, `TMPDIR`,
hostname and working directory are pinned, so a verdict does not depend on how the host is
configured — `presentsADeterministicEnvironmentRegardlessOfTheHost`.

### 5.8 Isolation between submissions — VERIFIED

`concurrentSandboxesAreIsolatedFromOneAnother` runs six sandboxes at once, each writing a
unique marker, and asserts every one reads back **its own** marker, sees only its own source
in its workspace, and sees no other run's marker. Container and volume names are random
UUIDs; cleanup is by exact name or label.

### 5.9 Secrets — VERIFIED

`environmentContainsNothingButTheFixedSandboxVariables` enumerates the sandbox environment
and asserts:

- every behaviour-affecting variable carries the value the sandbox chose
- **no** variable outside the sandbox's own set carries a non-empty value
- nothing mentions `TOKEN`, `PASSWORD`, `SECRET`, `DATABASE`, `REDIS`, `POSTGRES`,
  `CREDENTIAL`, `DOCKER_HOST` or `AWS_`
- the same holds for PID 1's environment, the obvious next place to look

An honest detail found while writing that test: **`docker run --env` adds to the image's
environment rather than replacing it**, so variables the base image declares still appear.
Those are public build constants (a GCC version, a GPG key id), never secrets, and the
sandbox images blank their values — but "the environment is exactly these six variables"
would have been a false claim, and the test asserts the true one instead.

---

## 6. Supply chain

Sandbox images are built from `sandbox/*.Dockerfile` by `sandbox/build-images.sh`, never
pulled at judging time.

- **Bases pinned by digest**, not tag. `gcc:13-bookworm` is rebuilt under the same tag with
  new packages; an image that changes underneath the judge changes the environment
  submissions run in without anyone deciding to.
- **Package managers removed** (`apt`, `apk`, `pip`). There is no network in a sandbox, so
  they could not fetch anything — removing them means a program that finds a way to run one
  cannot even try.
- **Network and remote-access tooling removed** (`ssh`, `scp`, `curl`, `wget`, `nc`).
- **Account-management tooling removed** (`su`, `passwd`, `chsh`, `mount`, …).
- **Every setuid/setgid bit stripped**, and the build **fails** if any remain. The Debian gcc
  base ships 13 of them, including `su`, `mount` and `ssh-keysign`.
- **The toolchain is exercised at build time**, so an image that has been stripped past the
  point of working fails to build rather than failing every submission.
- No credentials, no build caches, no source.

---

## 7. What is **not** protected

This section is the point of the document.

### 7.1 The execution service is host-equivalent if compromised — **RESIDUAL RISK**

It holds the Docker socket. Anything holding a Docker socket can start a privileged container
that mounts the host filesystem. Phase 6 made this surface much smaller — no database, no
queue, no user model, four typed endpoints, one caller, one shared secret, no inherited
environment — but **smaller is not zero**.

The real fixes, none of which are done:

- **rootless Docker**, so the daemon's authority is a user's rather than root's
- **a runtime with its own kernel boundary** (gVisor, Kata, Firecracker), so a container
  escape is not a host escape
- **a dedicated judging host**, so host-equivalent means "equivalent to a machine that does
  nothing else"

### 7.2 The kernel is shared — **INHERENT**

A container is not a virtual machine. A kernel exploit escapes it. `seccomp` narrows the
reachable syscall surface, capabilities are gone and the user is unprivileged, which makes
exploitation considerably harder — not impossible.

Kernel identity is visible from inside a container and always will be
(`cannotSeeHostProcessesEvenThoughTheKernelIsShared` asserts the sandbox has its own PID
namespace **and** states plainly that the kernel version is readable). Hiding it is not
achievable and pretending otherwise would be worse than saying so.

**CodeArena does not claim VM-level isolation.**

### 7.3 Per-execution disk is not hard-bounded — **PARTIAL**

At run time this is fully contained: the workspace is read-only and the only writable
filesystem is a size-capped tmpfs charged to the memory cgroup, so filling it is an OOM kill
and the host disk is never touched.

During **compilation** the workspace is writable. The bound there is `RLIMIT_FSIZE` per file
plus the compile timeout — not a quota on total bytes written.

`--storage-opt size=` would be the right control and is **not used deliberately**: this
daemon accepts it and silently does not enforce it. Measured — a container capped at 64 MB
wrote a 100 MB file successfully, because overlayfs here has no project quota. Configuring it
would look like a disk limit while being none, which is worse than not configuring it.

A filesystem with project quotas (XFS with `prjquota`, btrfs, ZFS) would allow a real
per-container limit. That is a deployment property, not a code change.

### 7.4 No per-user rate limiting — **DEFERRED TO PHASE 9**

Concurrency is bounded — the execution service refuses work beyond a configured ceiling of
concurrent workspaces, and answers 503 rather than thrashing. That protects the *machine*.

It does not protect *fairness*: one account can still occupy that ceiling by submitting
repeatedly. Per-user quotas need identity, which the execution service deliberately does not
have, and belong with the rest of the rate limiting in Phase 9.

### 7.5 No AppArmor or SELinux — **PLATFORM**

Neither is applied. This daemon reports only `seccomp` and `cgroupns` as security options;
Docker Desktop on WSL2 has no AppArmor. A profile name that the host does not have makes
every container **fail to start**, so naming one unconditionally would break the judge on
most developer machines.

`SANDBOX_APPARMOR_PROFILE` applies one where a deployment genuinely has it. It is empty by
default, and the configuration is absent rather than present-and-unenforced.

### 7.6 The wall-clock limit includes sandbox startup — **MEASURED**

The enforced wall clock covers the whole `docker run`, not just the submitted program.
Creating a container costs about **1.5 s on Docker Desktop/WSL2**; a native Linux daemon is
considerably faster. A problem's time limit therefore buys less compute than its number
suggests, and the shortfall varies by host — which makes a verdict depend on the platform in
a way nothing else here does.

This is not a Phase 6 regression, and the measurement says so: an empty Python program takes
1640 ms/run with no hardening flags and 1525 ms/run fully hardened with seccomp and
`--init`. The hardening is free; container creation is not.

Fixing it properly means timing the program from inside the sandbox, which requires a
supervisor in each image and changes what `runtimeMs` means. Recorded here rather than
absorbed silently.

### 7.7 Timing side channels — **ACCEPTED**

A submitter learns roughly how long their program ran and which test it failed on. In
principle, repeated submissions could infer something about a hidden test's size or shape
from timing. Removing that would mean quantising all timing information, at the cost of the
runtime feedback that makes a judge useful. Accepted deliberately.

### 7.8 Compile-time CPU and memory — **BOUNDED, NOT ELIMINATED**

Compilation is untrusted-code processing and gets its own independent limits. A compiler
bomb is bounded by them and produces `COMPILATION_ERROR`, never a judge failure
(`boundsACompilerBombWithoutFailingTheJudge`). But within those limits it still costs real
CPU and memory, and an attacker submitting many of them consumes capacity — which loops back
to §7.4.

---

## 8. Summary

| Question | Answer | Evidence |
|---|---|---|
| Can submitted code reach the Docker socket? | **No** | `cannotSeeTheDockerSocketOrAClientForIt` |
| Can it reach the host filesystem? | **No** | `cannotReachHostOrServiceFilesystems` |
| Can it use the network? | **No** | `deniesNetworkAccess`, `cannotSendUdp` |
| Can it resolve DNS? | **No** | `cannotResolveDns` |
| Can it run as root? | **No** | `runsUnprivilegedAndCannotEscalate` |
| Can it gain capabilities? | **No** | `CapBnd` is empty; `capset` denied by seccomp |
| Can it access host devices? | **No** | `cannotAccessHostDevices` |
| Can it fork indefinitely? | **No** | `containsAForkBomb`, `boundsThreadCreationAsWellAsProcesses` |
| Can it exhaust worker memory? | **No** | runs in a separate container with its own cap |
| Can it exhaust worker CPU? | **No** | `--cpus`, verified not bypassable by forking |
| Can it exhaust host disk? | **Not at run time.** Partially bounded at compile time | §7.3 |
| Can it produce unlimited output? | **No** | `stopsAProgramThatFloodsItsOutput` |
| Can it survive a timeout? | **No** | `leavesNoProcessOrContainerBehindAfterATimeout` |
| Can it leak environment secrets? | **No** | `environmentContainsNothingButTheFixedSandboxVariables` |
| Can two submissions interfere? | **No** | `concurrentSandboxesAreIsolatedFromOneAnother` |
| Can a crash orphan a sandbox? | **Briefly** — until the reaper's next sweep | §5.6 |
| Can a Docker failure corrupt submission state? | **No** | the row is the outbox; the sweeper recovers |
| Can a system failure be reported as a code failure? | **No** | `mapsEveryExecutorFailureToAnInfrastructureFailure` |
| Can it escape via a kernel exploit? | **Yes, in principle** | §7.2 — inherent to containers |
| Can a compromise of the execution service reach the host? | **Yes** | §7.1 — the residual risk of this design |

---

## Related

- [security.md](security.md) — the operational security posture
- [decisions.md](decisions.md) — ADR-020 (socket), ADR-028 (execution service), ADR-029
  (seccomp), ADR-030 (sandbox images), ADR-031 (disk)
- [architecture.md](architecture.md) — where these components sit
