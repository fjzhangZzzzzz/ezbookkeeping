# ezBookkeeping homelab deployment candidate

These files are reference deployment inputs. The reviewed integration is separately committed locally in `homelab-infra` at `564cc4f737aed1248c5d006f99ec31ae409f8162` (version `2.4.149`), with its Ansible component and runbook. It has not been pushed, released or deployed. These samples do not replace that component or prove a production backup/restore; do not copy them over the managed infrastructure files.

## Candidate topology

- One ezBookkeeping container on `control-01`, using SQLite and local attachment storage.
- Candidate backend bind: `10.144.144.2:18882` to container port `8080`. The port, live host identity, listener availability, firewall/Docker enforcement, and source address must be checked before any infrastructure change.
- Existing Caddy on `edge-01` would serve `books.fjzhang233.top` over HTTPS and proxy to that private backend. Pocket ID is a native OIDC login option; it must not be placed in front of the whole application as forward-auth, so password login remains available during an identity-provider outage.
- The domain, callback, port and required firewall/Caddy changes are proposals, not active settings or approvals.

## Inputs and configuration

`compose.yml` uses the locked local Docker image ID (`sha256:<64 hex>`) in `HOMELAB_EZBOOKKEEPING_IMAGE`, after loading and verifying the exact offline `docker save` archive. This config/image ID is distinct from a registry manifest digest; the current candidate has not been pushed to a registry. `pull_policy: never` requires that exact image to exist locally. The frozen source, image ID and archive SHA are recorded in [the build validation record](../../docs/family-review/linux-build-validation.md).

Render `ezbookkeeping.ini.template` to `/etc/homelab/ezbookkeeping/ezbookkeeping.ini`, root-owned and readable only by the service UID (1000). Replace every `REPLACE_...` marker using the approved secret/configuration process. Do not commit the rendered file, OIDC secret or application signing key. The default server entrypoint reads `EBK_CONF_PATH` and passes it as `--conf-path`; custom CLI commands must pass `--conf-path` explicitly because the entrypoint executes those commands unchanged.

The selected INI keys were checked in `conf/ezbookkeeping.ini` and `pkg/settings/setting.go`. It keeps password and OIDC login enabled, disables public registration and OIDC auto-registration, sets both scheduled-transaction controls false, disables custom icons, MCP, AI transaction recognition/coding assistant, and map rendering. Normal manual templates remain an application feature; verify their behavior on the chosen source revision. Expired-token cleanup remains enabled. This file is a complete configuration input, not an overlay on the shipped INI: keep the explicit UUID generator, duplicate checker, Amap validation method even with maps disabled, and exchange-rate source. Personal import/export are explicitly enabled; family statistics still exclude foreign-currency amounts.

SQLite lives at `/ezbookkeeping/data/ezbookkeeping.db`, with attachments under `/ezbookkeeping/storage/`; these are separate persistent mounts. Backrest preparation must stop writes before the SQLite file is copied so WAL state is checkpointed/consistent. Do not add a separate cron/timer or a second backup scheduler.

The `trusted_proxy_ips` value is deliberately a render-time placeholder. Capture the source address that the app actually sees through Caddy and the private network, then allow only that exact proxy address/CIDR. Do not use broad RFC1918 ranges. The proposed `18882` bind and host address are not live-verified.

The infrastructure component checks `/healthz.json` externally and requires `success=true`, `result.status=ok` and the locked full `result.commit`. That endpoint proves process availability, so SQLite writes, login and CSV import/export are separately verified. This sample does not add a second container healthcheck.

## Integration required in homelab-infra

When implementation is ready, review a concrete change in the infrastructure repository. The current repository's conventions indicate that the change will need to:

1. Register `ezbookkeeping` in `inventory/services.yml`, including `control-01` runtime/container identity, checks, dependencies, configuration/state/log/release paths, OIDC issuer/launch/callback/PKCE expectations and complete backup scope.
2. Add a sourced, immutable amd64 image to `inventory/artifacts.lock.json` and ensure the same image ID plus recoverable image/archive SHA, frozen source tar/manifest and Git bundle are captured in the same recovery point.
3. Add the component to the unique `backups.platform_services` list and extend `ansible/backup-prepare.yml`, `backup-finish.yml`, restore required-path assertions, host firewall/Docker rules, monitoring and release packaging as required by the existing role. Review Backrest's current plan through its product UI; do not replace its schedule, exclusions or hooks from Git.
4. Add the Caddy host and upstream only after its file-set and domain/identity change has exact approval. Keep Caddy logs redacted and avoid caching authenticated/dynamic application responses.
5. Integrate account operations into a reviewed runbook without ever recording passwords, access tokens or OIDC secrets in shell history, process logs, Git or chat.

Backrest remains the only schedule and execution owner. Its product plan takes the snapshot; Ansible `prepare`/`finish` is the consistency guard. The expected sequence is prepare (record prior state and stop writes), snapshot, finish (restore prior runtime state and check the snapshot). Verify failure recovery and the exact application entry in the prepare/finish state logic before calling the integration complete.

## Account CLI

This fork supports `userdata user-add --password-stdin` and `user-modify-password --password-stdin`. Supply the password through a protected standard-input channel, not a literal command argument, shell history, Git, or chat. Other required new-user fields remain username/email/nickname/default currency. Do not record credential-bearing stdin or raw test sessions.

User disabling and token revocation now commit together in the single SQLite store. Follow the offboarding runbook and verify both login methods and existing sessions are rejected; disabling Pocket ID alone does not revoke the application session. CLI session list/revoke/clear remain available for lost devices. The active family limit is ten users, enforced on creation and re-enabling.

## Approval boundary and evidence

Before any infrastructure deployment, request approval against a reviewable diff containing the exact source/digest, selected domain and callback, resolved private listener/port, exact ingress source and target, Caddy file set, Pocket ID client changes, Backrest scope/hooks, and restore path. This candidate does not authorize DNS, firewall/network ACL, Pocket ID, Caddy, SOPS, host or production changes.

The frozen-source candidate has passed local tests and independent review, a Linux runtime image with verified Linux backend and Windows-built static resources, non-root/read-only SQLite persistence, corrected-template login and CSV import/export, and real Chrome desktop/mobile UI checks including mobile save and return to the family page. See the build record for exact platform and artifact evidence; Linux Vite exceeded the isolated build memory limit and is not reported as passing. Real Pocket ID login/binding, public TLS and ingress, physical phone/PWA use, and a real Backrest snapshot/product restore remain pending. These sample files and the delivery documentation may have a newer Git HEAD than runtime source `462ff2b3f66659daa92ca2d19255924d1e2a3b70`; deploy/docs are excluded from its Docker context.
