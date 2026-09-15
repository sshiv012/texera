<!--
  ~ Licensed to the Apache Software Foundation (ASF) under one
  ~ or more contributor license agreements.  See the NOTICE file
  ~ distributed with this work for additional information
  ~ regarding copyright ownership.  The ASF licenses this file
  ~ to you under the Apache License, Version 2.0 (the
  ~ "License"); you may not use this file except in compliance
  ~ with the License.  You may obtain a copy of the License at
  ~
  ~   http://www.apache.org/licenses/LICENSE-2.0
  ~
  ~ Unless required by applicable law or agreed to in writing,
  ~ software distributed under the License is distributed on an
  ~ "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
  ~ KIND, either express or implied.  See the License for the
  ~ specific language governing permissions and limitations
  ~ under the License.
-->

# Running an offloaded operator end to end

From a fresh checkout to a workflow where one operator runs by itself on a rented
container, driven from the browser.

## Why this works without a routable IP

An offloaded worker and the coordinator have to reach each other. The default
topology sidesteps address discovery entirely:

```
offload.docker-network = "host"     # the default
```

The container shares the host's network namespace, so **both directions resolve
over `localhost`**. Nothing needs an IP address. That is why this runs on a
laptop.

The same code covers the other case. Pointing `docker-network` at a user-defined
bridge network publishes the worker's Pekko port and has each side advertise a
DNS-resolvable name — which is what you want when the coordinator itself runs in
a container or sits on a machine with a real address.

| | Laptop (default) | Coordinator with an IP, or containerised |
| --- | --- | --- |
| `OFFLOAD_DOCKER_NETWORK` | `host` | your bridge network, e.g. `texera-single-node` |
| `OFFLOAD_COORDINATOR_ADVERTISED_HOSTNAME` | unused | `host.docker.internal` (macOS/Win), `172.17.0.1` (Linux), or the service name |
| `TEXERA_CLUSTER_ADVERTISED_HOSTNAME` | unset | the name workers dial the coordinator at |
| Worker Pekko port | fixed, not published | fixed **and** published |

Both roles always bind `0.0.0.0`; only the advertised name changes.

---

## Prerequisites

| Need | Check |
| --- | --- |
| Docker **signed in** | `docker run --rm alpine echo ok` |
| Host networking | Docker Desktop 4.34+, Settings → Resources → Network → **Enable host networking**. Native on Linux. |
| JDK 17, sbt, Node 24, Python 3.12 | `source env.sh` |
| Postgres with the Texera schema | `pg_start`, then load `sql/texera_ddl.sql` |

> **Signed in, not just installed.** An organisation policy can require a Docker
> login before *any* container runs, and the failure names sign-in rather than
> networking:
>
> ```
> docker: Error response from daemon: Sign in to continue using Docker Desktop.
> ```
>
> Sign in from the Docker Desktop window, then confirm with the check above.
> Nothing below works until it prints `ok`.

If host networking is unavailable, use [bridge network](#bridge-network) instead —
no code changes, different variables.

---

## 1. Build the worker image

The image whose entry point joins the Pekko cluster. A full `sbt dist` inside
Docker; 10–20 minutes on a first build.

```bash
cd ~/research/texera
docker build -f bin/dockerfiles/computing-unit-worker.dockerfile \
  -t texera-computing-unit-worker:latest .
docker image inspect texera-computing-unit-worker:latest >/dev/null && echo OK
```

## 2. Start Postgres and load the schema

```bash
source ~/research/env.sh
pg_start
python3 ~/research/tools/load_ddl.py sql/texera_ddl.sql texera_db
sbt DAO/jooqGenerate          # once, or after a schema change
```

## 3. Turn offloading on

Off by default, because it launches billable resources on a real cloud backend.

```bash
export OFFLOAD_ENABLED=true
export OFFLOAD_DOCKER_NETWORK=host                        # the default; explicit here
export OFFLOAD_DOCKER_IMAGE=texera-computing-unit-worker:latest
export OFFLOAD_JOIN_TIMEOUT_SECONDS=120                   # fail sooner than the 300s default
```

## 4. Bring up the stack

`local-dev.sh` runs infra in Docker and the JVM/Angular services natively,
including the computing-unit master — the coordinator that rents containers.
Export the variables above **in the same shell**, so it inherits them.

```bash
bin/local-dev.sh up
bin/local-dev.sh status
```

Wait for the frontend and `computing-unit-master` to report healthy, then open
**http://localhost:4200**.

## 5. Build a workflow

1. Drag a **CSV file scan** onto the canvas.
2. Drag a **Filter** and connect the source to it.
3. Click the Filter to open the property panel.

> If dragging an operator does nothing and the canvas stays empty, the operator
> schemas are not compiling. The frontend runs every schema through Ajv before it
> can place *any* operator, so one malformed schema disables the whole palette
> rather than one operator. `OperatorMetadataGeneratorSpec` guards the shape that
> caused this.

## 6. Mark the operator to run on a rented machine

In the property panel, under **Run on a rented instance**:

| Field | Set to |
| --- | --- |
| **Enabled** | on — the size fields appear only once this is checked |
| **How to size it** | `Manual` (the only mode until the memory advisor lands) |
| **Machine size** | e.g. `local-1g — 1 GiB · free` |
| **Container image** | leave blank to use `OFFLOAD_DOCKER_IMAGE` |

Each option states its memory and cost, read from the instance catalog in
`offload.conf`. Memory is the constraint that decides whether the run survives;
the local entries are free, so they cost nothing to experiment with.

Start with `local-1g`: a 1 GiB cap, small enough to provoke an out-of-memory
failure deliberately later.

> Leaving **Machine size** empty with the toggle on is a per-operator error in the
> editor, before anything is rented.

### Bringing your own image

**Container image** overrides `OFFLOAD_DOCKER_IMAGE` for this one operator, so it
can carry tooling the platform image does not. Blank means the platform default —
a box you type in and then clear is the same as one you never touched.

The override is not a free hand. The image's entry point must still launch the
Texera worker, because a rented instance is usable only once it joins the Pekko
cluster; an image that starts and never joins is torn down at the join timeout and
the run fails. Build on the worker image from [step 1](#1-build-the-worker-image):

```dockerfile
FROM texera-computing-unit-worker:latest
RUN pip install --no-cache-dir pandas-profiling
```

```bash
docker build -f my-operator.dockerfile -t ghcr.io/me/texera-qc:1.0.0 .
```

Then set **Container image** to `ghcr.io/me/texera-qc:1.0.0`. The daemon must be
able to pull it — for a private registry, `docker login` on the machine running the
coordinator, since the rental shells out to that host's `docker`.

> The image is a positional argument to `docker run`, so one starting with `-`
> would be read as a flag. The editor rejects that before anything is rented.

## 7. Run it, and watch the container

```bash
# another terminal, while the workflow runs
watch -n1 'docker ps --filter label=texera.offload.operator \
  --format "table {{.Names}}\t{{.Status}}\t{{.Label \"texera.offload.instanceType\"}}"'
```

Expected order:

1. A container named `texera-offload-<executionId>-<operatorId>` appears.
2. The coordinator logs `Offloading 1 operator(s) ... -> local-1g`.
3. It joins the cluster; the workflow runs to completion.
4. The log shows `Releasing 1 offload instance(s)` and the container disappears.

Confirm the cap was applied — this is the mechanism the research depends on:

```bash
docker inspect <container> --format '{{.HostConfig.Memory}}'   # 1073741824 for local-1g
```

## 8. Confirm it was genuinely offloaded

Coordinator log, in order:

```
Offloading 1 operator(s) for execution <id>: <operatorId> -> local-1g
Cluster node pekko://Amber@... left as expected (offload release)
Releasing 1 offload instance(s) for execution <id>
```

The middle line matters. It means the departure was **announced** — without it,
removing the container looks like a node crash and force-stops other executions.

---

## Bridge network

For a coordinator that runs in a container, or on a machine with a routable IP.
No code changes.

```bash
export OFFLOAD_ENABLED=true
export OFFLOAD_DOCKER_NETWORK=texera-offload
export OFFLOAD_DOCKER_IMAGE=texera-computing-unit-worker:latest

# what worker containers dial to reach the coordinator:
#   macOS / Windows, coordinator native : host.docker.internal
#   Linux, coordinator native           : 172.17.0.1
#   coordinator in a container          : its service name
export OFFLOAD_COORDINATOR_ADVERTISED_HOSTNAME=host.docker.internal
export TEXERA_CLUSTER_ADVERTISED_HOSTNAME=host.docker.internal

docker network create texera-offload      # if it does not exist
```

---

## Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| `Sign in to continue using Docker Desktop` | Org policy requires a Docker login | Sign in from the Docker Desktop window; verify `docker run --rm alpine echo ok` |
| Dragging any operator does nothing | An operator schema fails Ajv, disabling the whole palette | Browser console for the compile error; run `sbt "WorkflowOperator/testOnly *OperatorMetadataGeneratorSpec"` |
| `did not join the cluster within Ns` | Worker cannot reach the coordinator, or advertises an unreachable name | `docker logs <container>`. On host networking confirm it is enabled; on a bridge confirm reachability *from inside* a container: `docker run --rm alpine ping -c1 host.docker.internal` |
| `offload.provider='ec2' is not implemented` | Provider other than `docker` | `export OFFLOAD_PROVIDER=docker` |
| `Unknown instance type 'x'` at compile | Name not in the catalog | Pick from `offload.conf`'s `instances` |
| Operator runs, no container appears | Offloading disabled | `export OFFLOAD_ENABLED=true` **before** `local-dev.sh up` |
| Container appears then vanishes, run fails | Worker crashed at startup | `docker logs <container>`; a cap too small to start a JVM is the usual cause — try `local-4g` |
| `Ambiguous cluster join` | Another node joined mid-rent | Rent sequentially; avoid starting unrelated workers during a run |
| Port already in use | Base port collides | `export OFFLOAD_WORKER_PEKKO_BASE_PORT=2600` |

## Teardown

```bash
bin/local-dev.sh down
docker ps -aq --filter label=texera.offload.operator | xargs -r docker rm -f   # strays
pg_stop
```

## Known limits

- **`Advised` sizing is not implemented.** Only `Manual` provisions, and the panel
  offers only `Manual` so the choice cannot dead-end. The memory advisor is next.
- **EC2 is not implemented**; a non-`docker` provider fails at execution start
  rather than silently not offloading.
- A container that joins *just after* its join timeout is torn down before its
  address is known, so that departure still reads as a crash.
- **Any image an operator names is run as-is.** There is no registry allowlist, so
  with offloading on, anyone who can edit a workflow can have the coordinator's
  docker daemon pull and run an image of their choosing. Acceptable while this is
  a research prototype that is off by default; a shared deployment wants the
  override restricted to trusted registries first.
