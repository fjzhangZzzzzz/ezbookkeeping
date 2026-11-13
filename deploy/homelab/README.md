# ezBookkeeping homelab deployment candidate

These files are candidate inputs for a future, reviewed `homelab-infra` change. They are not an Ansible component, a production deployment, or evidence of a successful backup or restore. Do not copy them into the infrastructure repository as-is.

## Candidate topology

- One ezBookkeeping container on `control-01`, using SQLite and local attachment storage.
- Candidate backend bind: `10.144.144.2:18882` to container port `8080`. The port, live host identity, listener availability, firewall/Docker enforcement, and source address must be checked before any infrastructure change.
- Existing Caddy on `edge-01` would serve `books.fjzhang233.top` over HTTPS and proxy to that private backend. Pocket ID is a native OIDC login option; it must not be placed in front of the whole application as forward-auth, so password login remains available during an identity-provider outage.
- The domain, callback, port and required firewall/Caddy changes are proposals, not active settings or approvals.

## Inputs and configuration

`compose.yml` expects an immutable image reference in `HOMELAB_EZBOOKKEEPING_IMAGE`. Supply only the exact released image reference with `@sha256:<64 hex>` after a clean build and artifact review. The variable is required, but Compose does not validate the digest syntax; release review must reject tags without a digest. `pull_policy: never` means the exact image must already be provisioned on the host. No image digest is asserted by this candidate.

Render `ezbookkeeping.ini.template` to `/etc/homelab/ezbookkeeping/ezbookkeeping.ini`, root-owned and readable only by the service UID (1000). Replace every `REPLACE_...` marker using the approved secret/configuration process. Do not commit the rendered file, OIDC secret or application signing key. The container entrypoint reads `EBK_CONF_PATH` and passes it as `--conf-path`; this follows the checked-in upstream `docker/docker-entrypoint.sh`.

The selected INI keys were checked in `conf/ezbookkeeping.ini` and `pkg/settings/setting.go`. It keeps password and OIDC login enabled, disables public registration and OIDC auto-registration, sets both scheduled-transaction controls false, disables custom icons, MCP, AI transaction recognition/coding assistant, and map rendering. Normal manual templates remain an application feature; verify their behavior on the chosen source revision. Expired-token cleanup remains enabled.

SQLite lives at `/ezbookkeeping/data/ezbookkeeping.db`, with attachments under `/ezbookkeeping/storage/`; these are separate persistent mounts. Backrest preparation must stop writes before the SQLite file is copied so WAL state is checkpointed/consistent. Do not add a separate cron/timer or a second backup scheduler.

The `trusted_proxy_ips` value is deliberately a render-time placeholder. Capture the source address that the app actually sees through Caddy and the private network, then allow only that exact proxy address/CIDR. Do not use broad RFC1918 ranges. The proposed `18882` bind and host address are not live-verified.

This Compose deliberately has no container healthcheck: the image's Alpine runtime does not establish a curl/wget health utility, and a health endpoint has not been validated as a suitable operational check. The infrastructure service declaration should use an externally performed HTTP check against the actual app route after the image is built.

## Integration required in homelab-infra

When implementation is ready, review a concrete change in the infrastructure repository. The current repository's conventions indicate that the change will need to:

1. Register `ezbookkeeping` in `inventory/services.yml`, including `control-01` runtime/container identity, checks, dependencies, configuration/state/log/release paths, OIDC issuer/launch/callback/PKCE expectations and complete backup scope.
2. Add a sourced, immutable amd64 image to `inventory/artifacts.lock.json` and ensure the same digest plus recoverable image/archive and source SHA are captured in the same recovery point.
3. Add the component to the unique `backups.platform_services` list and extend `ansible/backup-prepare.yml`, `backup-finish.yml`, restore required-path assertions, host firewall/Docker rules, monitoring and release packaging as required by the existing role. Review Backrest's current plan through its product UI; do not replace its schedule, exclusions or hooks from Git.
4. Add the Caddy host and upstream only after its file-set and domain/identity change has exact approval. Keep Caddy logs redacted and avoid caching authenticated/dynamic application responses.
5. Integrate account operations into a reviewed runbook without ever recording passwords, access tokens or OIDC secrets in shell history, process logs, Git or chat.

Backrest remains the only schedule and execution owner. Its product plan takes the snapshot; Ansible `prepare`/`finish` is the consistency guard. The expected sequence is prepare (record prior state and stop writes), snapshot, finish (restore prior runtime state and check the snapshot). Verify failure recovery and the exact application entry in the prepare/finish state logic before calling the integration complete.

## Account CLI

This fork supports `userdata user-add --password-stdin` and `user-modify-password --password-stdin`. Supply the password through a protected standard-input channel, not a literal command argument, shell history, Git, or chat. Other required new-user fields remain username/email/nickname/default currency. Do not record credential-bearing stdin or raw test sessions.

User disabling and token revocation now commit together in the single SQLite store. Follow the offboarding runbook and verify both login methods and existing sessions are rejected; disabling Pocket ID alone does not revoke the application session. CLI session list/revoke/clear remain available for lost devices. The active family limit is ten users, enforced on creation and re-enabling.

## Approval boundary and evidence

Before any infrastructure deployment, request approval against a reviewable diff containing the exact source/digest, selected domain and callback, resolved private listener/port, exact ingress source and target, Caddy file set, Pocket ID client changes, Backrest scope/hooks, and restore path. This candidate does not authorize DNS, firewall/network ACL, Pocket ID, Caddy, SOPS, host or production changes.

Verified from source/repository text: Dockerfile runtime UID/GID 1000 and exposed port 8080; `EBK_CONF_PATH` entrypoint behavior; candidate INI keys; current user CLI command/flags; homelab Compose and Backrest conventions. Not verified: a built image, digest, frozen source revision behavior, Compose validation, current host identities/ports/firewall, actual proxy address, DNS/TLS, Pocket ID client/callback, Backrest plan/path and prepare/finish coverage, monitoring checks, or any deployment/recovery.
