# Security model

This document is deliberately blunt about what is and is not protected. A judge runs code
written by strangers; overstating its containment would be worse than having none, because
it would stop somebody asking the right questions before deploying it.

---

## The threat

Submitted code is **hostile until proven otherwise**. Assume every submission is an attempt
to read the database, reach the network, exhaust the host, or escape onto it. Nothing about
the platform may depend on a submission behaving.

---

## What actually contains it

Untrusted code runs only inside a container created per execution, with these flags and no
others. Every control is assembled in one place — `SandboxPolicy` — and verified by a test
that runs a real malicious program and asserts what happened, in `SandboxSecurityIT` and
`SandboxIsolationIT`.

| Control | Flag | Stops |
|---|---|---|
| No network | `--network none` | Reaching PostgreSQL, Redis, the internet, other containers, DNS |
| Memory ceiling | `--memory`, `--memory-swap` equal | Exhausting host RAM; swap cannot be used to evade it |
| CPU quota | `--cpus` | A busy loop starving the host; not bypassable by forking |
| Process ceiling | `--pids-limit` | Fork bombs and thread storms, which no timeout catches |
| No capabilities | `--cap-drop ALL` | Raw sockets, mounts, module loading. `CapBnd` is empty too, so none can be acquired |
| No re-escalation | `--security-opt no-new-privileges` | Regaining privileges through setuid |
| Syscall allowlist | `--security-opt seccomp=…` | `ptrace`, `mount`, `unshare`, `bpf`, `io_uring`, `capset`, kernel-module loading — see ADR-029 |
| Unprivileged user | `--user 65534:65534` | Acting as root even inside the sandbox |
| Read-only root | `--read-only` | Modifying the image; persisting anything |
| Bounded scratch | `--tmpfs /tmp:noexec,nosuid,nodev,size=…` | Filling a disk; writing a payload and executing it |
| Read-only workspace at run time | `-v …:/work:ro` | Writing anything at all during a run |
| File-size ceiling | `--ulimit fsize` | Writing one enormous file during compilation |
| No core dumps | `--ulimit core=0` | A crash writing a file the size of the address space |
| Private namespaces | `--ipc none`, `--cgroupns private` | Shared IPC; seeing the host's cgroup tree |
| No registry access | `--pull never` | Judging depending on, or reaching, a network registry |
| Zombie reaping | `--init` | Processes accumulating inside a run |
| Fixed identity | `--hostname sandbox` | Learning the container id |
| Fixed environment | explicit `--env` set | Inheriting credentials; locale- or timezone-dependent verdicts |
| Wall-clock kill | container removed at the deadline | Infinite loops, and children that outlive their parent |
| Output ceiling | bounded stream reader | Flooding the executor's heap with stdout |
| Disposal | containers and volumes removed in `finally`, plus a reaper | Accumulating artefacts across runs and across crashes |

A fuller account, including the evidence for each control and what is deliberately **not**
covered, is in [threat-model.md](threat-model.md).

**No shell is ever involved.** Commands are fixed `List<String>` argv values in
`LanguageSpec`, handed to a `ProcessBuilder`. Nothing from a request, a database row or a
queue message contributes an element to any of them, so there is no string for an injection
to travel in. The source file name is fixed per language, so path traversal and malicious
filenames are impossible rather than filtered.

**The client cannot name a command.** It sends a `Language` enum constant. A value that
does not match a constant fails deserialisation before any code runs.

---

## What is *not* contained — read this before deploying

### 1. Something still holds the Docker socket — now the execution service

Creating containers requires talking to a container runtime, and **a Docker socket is
equivalent to root on the host**: anything holding one can start a privileged container that
mounts the host filesystem. Something has to hold it. The question is what else that
something does.

**What changed in Phase 6.** The socket moved off the judge worker and onto a dedicated
execution service. The worker — which parses untrusted program output and connects to
PostgreSQL and Redis — now holds only a shared secret for a contract with four operations:
prepare a workspace for one of three languages, compile it, run it, discard it. That contract
has no field for an image, a mount, a capability, a network, a user or a command, and limits
are clamped server-side. The worker image no longer even contains a Docker client.

So a compromise of the worker now yields "can ask for a sandboxed program to be run" rather
than host control. That is a large reduction in blast radius from a previously
host-equivalent process.

**What has not changed.** The executor is host-equivalent if *it* is compromised. It is built
to be as small a target as possible — no database driver, no Redis client, no user model, one
caller, one shared secret, four typed endpoints, no inherited environment — but small is not
zero, and nobody should read this section as saying the problem is solved.

The socket is **never** passed into a sandbox, and neither is a Docker client. A submitted
program has no path to the daemon — verified by `cannotSeeTheDockerSocketOrAClientForIt`.
That is a different question from this one, and the two should not be confused.

**Real fixes, none of them done:** rootless Docker, so the daemon's authority is a user's
rather than root's; or a runtime with its own kernel boundary (gVisor, Kata, Firecracker), so
a container escape is not a host escape; or a dedicated judging host, so "host-equivalent"
means a machine that does nothing else. See ADR-028 and threat-model.md §7.1.

### 2. Containers share the host kernel

`--cap-drop ALL` and a non-root user raise the bar considerably, but a container is a
namespace, not a virtual machine. A kernel privilege-escalation vulnerability is a sandbox
escape.

**Fix (Phase 12):** a runtime with its own kernel boundary — gVisor, Kata, or Firecracker —
so that a container escape is not a host escape.

### 3. AppArmor and SELinux are not applied

**Seccomp is** — a custom allowlist narrower than Docker's default, described in ADR-029 and
verified by a test asserting that `ptrace` returns `EPERM` where the daemon default allows
it. If the profile file cannot be read the sandbox falls back to the daemon default, logs a
warning, and reports it on the health endpoint rather than degrading silently.

**Mandatory access control is not applied.** This daemon reports only `seccomp` and
`cgroupns` as security options; Docker Desktop on WSL2 has no AppArmor at all. Naming a
profile the host does not have makes every container **fail to start**, so a profile is
applied only when `SANDBOX_APPARMOR_PROFILE` names one that genuinely exists. It is empty by
default: the configuration is absent rather than present-and-unenforced.

### 4. Submission rate limiting is not implemented

A single authenticated user can submit as fast as they can issue HTTP requests, and each
submission costs a container. The source size limit and the per-sandbox ceilings bound the
cost of *one* submission; nothing yet bounds the *rate*. This is a real gap, stated here
rather than filed under future improvements. Phase 9 brings rate limiting, where Redis is
already the counter store.

### 5. Per-execution disk is not hard-bounded during compilation

At run time this is fully contained: the workspace is mounted read-only and the only writable
filesystem is a size-capped tmpfs charged to the memory cgroup, so filling it is an OOM kill
and the host disk is never touched.

During **compilation** the workspace must be writable. The bound there is `RLIMIT_FSIZE` per
file plus the compile timeout — not a quota on total bytes.

`--storage-opt size=` would be the right control and is **deliberately not used**: this
daemon accepts it and silently does not enforce it. Measured — a container capped at 64 MB
wrote a 100 MB file successfully, because overlayfs here has no project quota. Configuring it
would look like a disk limit in every future review while being none. A filesystem with
project quotas (XFS `prjquota`, btrfs, ZFS) would allow a real one; that is a deployment
property, not a code change. See ADR-031.

### 6. Live streams are capped, but the cap is global

Each open SSE stream pins a servlet container thread for its lifetime, so an unbounded number
of them stops the API answering anything — a denial of service needing no more privilege than
an account. `SubmissionStreamRegistry` caps concurrent streams (default 500,
`codearena.events.max-sse-connections`) and refuses beyond it.

The cap is **global, not per user**: one account opening 500 streams denies the rest. Making
it per-principal is the right fix and belongs with the rest of the rate limiting in Phase 9.
A refused stream is not an error the user sees — it still receives its snapshot, then closes,
and the client falls back to polling.

### 7. There is no global quota on judge storage

Individual executions are bounded as described above, and containers and volumes are removed
in a `finally` with `SandboxReaper` as a backstop for crashes. But nothing caps the *total*
storage judging may occupy, so a sustained failure of both cleanup paths would eventually
fill the disk. The reaper's sweep count is exposed as a metric precisely so that this shows
up as a number climbing rather than as a full disk.

---

## Answers to the review questions

| Question | Answer |
|---|---|
| Can an unauthenticated user submit? | No — 401. Tested. |
| Can a user submit to a draft or archived problem? | No — 404, which also hides that it exists. Tested. |
| Can a user modify another user's submission? | There is no endpoint that modifies a submission at all. Reading one returns 404. Tested. |
| Can a user set status, verdict, runtime or worker fields? | No. `SubmissionRequest` has two fields; the rest do not exist on the type, so they cannot be overposted. Tested. |
| Can a user supply an arbitrary command? | No. The language is an enum; commands are compile-time constants. |
| Can submitted code read the host filesystem? | No host path is mounted into a sandbox. |
| Can submitted code reach the Docker socket? | No — and there is no Docker client in the sandbox either. Tested. |
| Can submitted code resolve DNS? | No. Tested. |
| Can submitted code acquire a capability? | No. `CapEff`, `CapPrm` and `CapBnd` are all empty, and `capset` is denied by seccomp. Tested. |
| Can submitted code call `ptrace`? | No — denied by the custom seccomp profile, which the daemon default allows. Tested. |
| Can submitted code open a host device? | No. Tested. |
| Can submitted code write anywhere but `/tmp`? | No. Even its own workspace is read-only during a run. Tested. |
| Can submitted code execute something it wrote? | No — `/tmp` is `noexec`. |
| Can a child process outlive a timeout? | No. Removing the container kills the whole cgroup. Tested with a deliberately orphaned child. |
| Can extra processes buy extra CPU? | No — the quota is per-cgroup. Tested by comparing one unit of work against four. |
| Can two concurrent submissions see each other? | No. Tested with six at once, each checking for the others' markers. |
| Can a compromised *worker* reach the Docker socket? | No — it holds a token for a four-operation contract, and its image has no Docker client. |
| Can a compromised *execution service* reach the host? | **Yes.** That is the residual risk of this design; see item 1 above. |
| Can submitted code use the network? | No — `--network none`. Tested. |
| Can submitted code exhaust memory? | No — killed by the kernel. Tested. |
| Can submitted code exhaust CPU or run forever? | No — CPU quota plus wall-clock kill. Tested. |
| Can submitted code produce unlimited output? | No — bounded reader terminates it. Tested. |
| Can two workers judge the same submission at once? | No — a single atomic `UPDATE … WHERE status = 'QUEUED'` decides the winner. Tested with 8 concurrent claimers. |
| Can a worker crash lose a submission? | No — the row is the outbox; the sweeper republishes or fails it. Tested. |
| Can hidden test data leak through the API? | No. The user-facing types have no field capable of holding it, and tests assert the raw response bodies. |
| Can a user open a stream for another user's submission? | No. `SubmissionStreamController` runs the same authorisation check as the REST endpoint *before* the first byte, and answers a bodiless 404 — the same answer, so the stream discloses no more than the endpoint beside it. Tested. |
| Can the event stream disclose more than the REST endpoint? | No. The subscriber re-reads through the same service method and serialises the same DTO; there is no separate stream-only payload that could drift. |
| Can a user learn a submission exists by watching for events? | No. Events are fanned out per submission id to emitters already registered, and registering requires passing the authorisation check. |
| Can the client set verdict, runtime or test results? | No. Every field on the stream and on both read endpoints is server-derived; no write endpoint accepts any of them. Tested. |
| Can hidden test data leak through per-test results? | No. `submission_test_results` has no column able to hold it, and `TestResultResponse` has no such field. The guarantee is structural, not a filter. |
| Can hidden test data leak through logs? | No. `ProblemTestCase.toString()` and `JudgeTestCase.toString()` both omit input and expected output, and the worker logs only ids, statuses and counts. |

---

## Data handling

- **Passwords** — BCrypt cost 12 behind a `DelegatingPasswordEncoder`. Never logged, never returned.
- **Sessions** — HttpOnly, SameSite=Lax cookies backed by Redis. Logout destroys the session server-side.
- **Source code** — stored as `TEXT`, never interpreted, never logged, never placed on a command
  line. Returned only from the submission detail endpoint, only to its author. Retained for the
  lifetime of the submission; there is no expiry or purge job yet.
- **Submission events** — the Redis channel carries a submission id, a status and a timestamp.
  No source code, no test data, no user identity. Any process able to read the channel learns
  that *some* submission changed status, which is why the payload is never forwarded to a
  browser: the authorisation decision is made by the API re-reading the row, not by whoever
  published the event.
- **Hidden test cases** — readable only by the admin API and the worker. The expected output never
  enters a sandbox: only the input is written to stdin, and comparison happens in the worker.
