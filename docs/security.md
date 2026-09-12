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
others. Each is verified by a test in `DockerExecutionIT`.

| Control | Flag | Stops |
|---|---|---|
| No network | `--network none` | Reaching PostgreSQL, Redis, the internet, other containers |
| Memory ceiling | `--memory`, `--memory-swap` equal | Exhausting host RAM; swap cannot be used to evade it |
| CPU quota | `--cpus` | A busy loop starving the host |
| Process ceiling | `--pids-limit` | Fork bombs, which no timeout catches |
| No capabilities | `--cap-drop ALL` | Raw sockets, mounts, ptrace, module loading |
| No re-escalation | `--security-opt no-new-privileges` | Regaining privileges through setuid |
| Unprivileged user | `--user 65534:65534` | Acting as root even inside the sandbox |
| Read-only root | `--read-only` + small `tmpfs` | Persisting anything; filling a disk |
| Wall-clock kill | worker-enforced timeout | Infinite loops |
| Output ceiling | bounded stream reader | Flooding the worker's heap with stdout |
| Disposal | volume and containers removed in `finally` | Accumulating artefacts across runs |

**No shell is ever involved.** Commands are fixed `List<String>` argv values in
`LanguageSpec`, handed to a `ProcessBuilder`. Nothing from a request, a database row or a
queue message contributes an element to any of them, so there is no string for an injection
to travel in. The source file name is fixed per language, so path traversal and malicious
filenames are impossible rather than filtered.

**The client cannot name a command.** It sends a `Language` enum constant. A value that
does not match a constant fails deserialisation before any code runs.

---

## What is *not* contained — read this before deploying

### 1. The worker holds the Docker socket

This is the single largest piece of un-hardened surface in the system.

The worker must talk to a container runtime in order to create sandboxes, and it does so
through `/var/run/docker.sock`, mounted into the worker container by
`docker-compose.yml`. **Access to that socket is equivalent to root on the host**: anything
holding it can start a privileged container that mounts the host filesystem.

What limits the damage today:

- The socket is **never** passed into a sandbox. Containers running user code get no socket,
  no host volume and no network. A submitted program has no path to the daemon — verified by
  `deniesAccessToTheDockerSocket`.
- The worker runs only code from this repository and never evaluates user input as a command.
- The backend and frontend containers do not get the socket.

What that does *not* cover: if the worker process itself is compromised — a deserialisation
bug, a dependency with a supply-chain problem — the attacker has the host. "The process is
trusted" is a weaker guarantee than "the process cannot".

**Fix (Phase 12):** a rootless or remote daemon dedicated to judging, so the socket grants
control over an isolated runtime rather than the host.

### 2. Containers share the host kernel

`--cap-drop ALL` and a non-root user raise the bar considerably, but a container is a
namespace, not a virtual machine. A kernel privilege-escalation vulnerability is a sandbox
escape.

**Fix (Phase 12):** a runtime with its own kernel boundary — gVisor, Kata, or Firecracker —
so that a container escape is not a host escape.

### 3. No seccomp or AppArmor profile beyond the defaults

Docker's default seccomp profile is applied, which blocks a useful set of syscalls. No
custom, tighter profile has been written.

### 4. Submission rate limiting is not implemented

A single authenticated user can submit as fast as they can issue HTTP requests, and each
submission costs a container. The source size limit and the per-sandbox ceilings bound the
cost of *one* submission; nothing yet bounds the *rate*. This is a real gap, stated here
rather than filed under future improvements. Phase 9 brings rate limiting, where Redis is
already the counter store.

### 5. Disk is bounded only indirectly

A per-submission volume plus a `tmpfs` for scratch limits what one execution can write, and
volumes are removed in a `finally`. There is no global quota on judge storage, so a
sustained failure of the cleanup path would eventually fill the disk.

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
| Can submitted code reach the Docker socket? | No. Tested. |
| Can submitted code use the network? | No — `--network none`. Tested. |
| Can submitted code exhaust memory? | No — killed by the kernel. Tested. |
| Can submitted code exhaust CPU or run forever? | No — CPU quota plus wall-clock kill. Tested. |
| Can submitted code produce unlimited output? | No — bounded reader terminates it. Tested. |
| Can two workers judge the same submission at once? | No — a single atomic `UPDATE … WHERE status = 'QUEUED'` decides the winner. Tested with 8 concurrent claimers. |
| Can a worker crash lose a submission? | No — the row is the outbox; the sweeper republishes or fails it. Tested. |
| Can hidden test data leak through the API? | No. The user-facing types have no field capable of holding it, and tests assert the raw response bodies. |
| Can hidden test data leak through logs? | No. `ProblemTestCase.toString()` and `JudgeTestCase.toString()` both omit input and expected output, and the worker logs only ids, statuses and counts. |

---

## Data handling

- **Passwords** — BCrypt cost 12 behind a `DelegatingPasswordEncoder`. Never logged, never returned.
- **Sessions** — HttpOnly, SameSite=Lax cookies backed by Redis. Logout destroys the session server-side.
- **Source code** — stored as `TEXT`, never interpreted, never logged, never placed on a command
  line. Returned only from the submission detail endpoint, only to its author. Retained for the
  lifetime of the submission; there is no expiry or purge job yet.
- **Hidden test cases** — readable only by the admin API and the worker. The expected output never
  enters a sandbox: only the input is written to stdin, and comparison happens in the worker.
