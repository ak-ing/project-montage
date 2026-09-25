# Deployment

One script deploys Project Montage to Google Cloud Run: an interactive
`setup` for the first time, and a one-line deploy for every release after.

```bash
./deploy/deploy.sh setup --env dev    # once: asks questions, creates resources
./deploy/deploy.sh --env dev          # every release: build, deploy, wire
```

> [!NOTE]
> Looking to run the stack **locally**? This script is for Cloud Run only.
> See the Quick Start in the [root README](../README.md).

**Prerequisites:** `bash` (macOS, Linux, or Git Bash on Windows), `git`,
`curl`, and the [gcloud CLI](https://cloud.google.com/sdk/docs/install) with
the `beta` component if you use IAP (`gcloud components install beta`).
No local Docker is needed.

---

## What gets deployed

```mermaid
graph LR
  U["Browser"] -->|"IAP sign-in (optional)"| C["montage-client<br/>ADK web UI"]
  C -->|"ID token<br/>run.invoker"| M["montage-mcp<br/>MCP server (private)"]
  C -.->|"private IP (optional)"| S[("Cloud SQL<br/>ADK sessions")]
  M --> B[("GCS bucket")]
  U -->|"VIEW_ENDPOINT link<br/>IAP sign-in (optional)"| G["montage-sign<br/>(optional)"]
  G -->|"signed URL redirect"| B
```

| Service | Source | Access |
| --- | --- | --- |
| `montage-mcp` | [`mcp_montage/`](../mcp_montage) | Private. Only the client's service account holds `roles/run.invoker`. |
| `montage-client` | [`mcp_client/`](../mcp_client) | IAP allowlist, or public when you explicitly choose it. |
| `montage-sign` | [`sign_server/`](../sign_server) | The same IAP allowlist as the client, or public when IAP is off. Signs objects in the configured bucket only. |

Service names are defaults; `setup` asks for each one, and they can also be
set in the environment's config file (`MCP_SERVICE`, `CLIENT_SERVICE`,
`SIGN_SERVICE`).

---

## First-time setup

```bash
./deploy/deploy.sh setup --env dev
```

`setup` asks for each value, showing the current one as the default, then
creates what is missing and skips what already exists, so re-running it is
always safe. Answers are saved to `deploy/config/<env>.env` (gitignored).

To run setup without prompts, fill in a copy of
[`config/example.env`](./config/example.env) (or export the keys) and pass
`-y`. Setup rewrites the config file, so keep only the documented keys in it.

| Step | Asks for | Creates or verifies |
| --- | --- | --- |
| **1. Project** | `PROJECT_ID`, `REGION`, `GOOGLE_CLOUD_LOCATION`, `MCP_SERVICE`, `CLIENT_SERVICE` | Checks the project exists with billing (never creates it), then enables every required API that is off |
| **1b. KMS** *(optional)* | — | With `KMS_KEY`, lets each Google service agent encrypt with the key before anything is created |
| **2. Registry** | `AR_REPO` | Artifact Registry Docker repo; `cloudbuild.builds.builder` for the build service account (`BUILD_SERVICE_ACCOUNT`, or the Compute Engine default service account new projects use) |
| **3. Identities** | — | Service accounts `montage-mcp`, `montage-client` (and `montage-sign`), or the ones the config names |
| **4. Bucket** | `GCS_BUCKET_NAME` | Bucket with uniform access |
| **5. Cloud SQL** *(optional)* | `SQL_INSTANCE`, `SQL_TIER`, `SQL_DATABASE`, `SQL_USER`, `VPC_NETWORK`, `VPC_SUBNET`, `VPC_SUBNET_RANGE` | VPC and subnet, private services access, private-IP Postgres, database, user, and the connection-string secret |
| **6. Sign server** *(optional)* | `SIGN_SERVICE` | Builds and deploys `montage-sign` (behind IAP when enabled); saves `VIEW_ENDPOINT` |
| **7. Access** | `IAP_MEMBERS`, or explicit consent to a public client | IAP service agent; each deployed service's allowlist is made to match `IAP_MEMBERS` |

Any single step can be re-run on its own with `--only`:

```bash
./deploy/deploy.sh setup --env dev --only iap      # edit who may sign in
./deploy/deploy.sh setup --env dev --only bucket   # project kms registry identities bucket sql sign iap
```

When run interactively, setup ends by offering to run the first deploy.

**Before setup, create the project and link billing yourself.** Setup and
every deploy check both first and stop, with the commands to run, when the
project does not exist (or your account cannot see it) or billing is off:

```bash
gcloud projects create PROJECT_ID --organization=ORGANIZATION_ID   # or --folder=FOLDER_ID
gcloud billing projects link PROJECT_ID --billing-account=BILLING_ACCOUNT_ID
```

Create it inside your organization if you use IAP with Google's OAuth
client. On a fresh project, setup then enables every API Montage needs
(Cloud Run, Cloud Build, Artifact Registry, Vertex AI, IAM, Storage,
Compute, plus SQL, Service Networking and Secret Manager with SQL, IAM
Credentials with the sign server, and IAP with IAP). Later runs enable only
the ones that are off.

Setup stops, instead of creating a resource, when it cannot tell whether that
resource exists (a missing permission, a disabled API, a bucket name owned by
another organization). It shows gcloud's error.

### Identities and roles

Services never run as the default compute service account, which usually
has Editor on the project.

| Service account | Roles |
| --- | --- |
| `montage-mcp` | `aiplatform.user`; `storage.objectAdmin` on the bucket; `storage.objectViewer` on the `GCS_INGREDIENT_IMAGES_FOLDER` bucket (when it differs from the bucket) |
| `montage-client` | `aiplatform.user`; `storage.objectAdmin` on the bucket; `run.invoker` on `montage-mcp`; `secretmanager.secretAccessor` on the session secret (when SQL is enabled) |
| `montage-sign` | `storage.objectViewer` on the bucket; `iam.serviceAccountTokenCreator` on itself (to sign URLs through the IAM API) |

`MCP_SERVICE_ACCOUNT`, `CLIENT_SERVICE_ACCOUNT` and `SIGN_SERVICE_ACCOUNT`
replace these accounts with existing ones, for organizations that forbid
creating service accounts. Setup creates a named account only when it
belongs to `PROJECT_ID`; any other must already exist.

### Cloud SQL (private IP)

Enabling Cloud SQL also creates the network it lives on:

1. a custom-mode VPC and a subnet in `REGION` for Cloud Run Direct VPC egress,
2. a `/16` private services access range (`<VPC_NETWORK>-psa`) and the
   `servicenetworking` peering,
3. a Postgres 16 (Enterprise edition) instance with `--no-assign-ip`, plus a
   database and user,
4. a generated password, stored as part of the whole connection string
   (`postgresql+asyncpg://USER:PASS@PRIVATE_IP:5432/DB`) in the Secret Manager
   secret `montage-session-uri-<env>`.

The client reads that secret with `--set-secrets SESSION_SERVICE_URI=…` and
reaches the instance with `--vpc-egress=private-ranges-only`, so calls to
montage still go out over Google's network. The password is sent to Secret
Manager on stdin and never printed or written to a config file. If the
secret already exists, setup keeps it and does not reset the password.

> [!CAUTION]
> Creating the instance takes 10–15 minutes and bills continuously. A deleted
> instance name stays reserved for about a week.

### Media links (sign server)

`mcp_montage` builds viewable links as `VIEW_ENDPOINT + <bucket>/<object>`.

| Setup choice | `VIEW_ENDPOINT` | Who can open a link |
| --- | --- | --- |
| Sign server, IAP on | `https://<montage-sign-url>/view?uri=` | `IAP_MEMBERS` only, after Google sign-in; it redirects to a short-lived signed URL |
| Sign server, IAP off | `https://<montage-sign-url>/view?uri=` | Anyone holding the link |
| Skip | unset (`https://storage.cloud.google.com/`) | Signed-in Google users with read access to the bucket |

The sign server refuses objects outside `GCS_BUCKET_NAME`, and refuses
everything when that variable is unset.

> [!NOTE]
> The signed URL it redirects to is itself a bearer link: anyone it is
> forwarded to can open the object until the URL expires
> (`SIGNED_URL_EXPIRATION_MINUTES`, 60 by default). IAP protects the
> `montage-sign` link, not the signed URL.

### Identity-Aware Proxy

A browser sends no `Authorization` header, so `--no-allow-unauthenticated`
on its own just returns 403. IAP puts a Google sign-in page in front and
admits only the allowlist. `IAP_ENABLED=1` protects both browser-facing
services, the client and the sign server, with the same members:

```bash
IAP_ENABLED="1"
IAP_MEMBERS="group:montage-users@example.com,user:someone@example.com"
```

Each member needs a type prefix: `user:`, `group:`, `domain:`,
`serviceAccount:`, or `principal://` / `principalSet://` for identities
signing in through Workforce Identity Federation (an external IdP). A bare
email is rejected before anything is deployed.

> [!IMPORTANT]
> By default IAP uses a Google-managed OAuth client, which admits only
> identities of the project's own organization. A member from another
> organization is granted access but cannot sign in. To admit them, create an
> OAuth client in the console, store its secret in Secret Manager, and set
> `IAP_OAUTH_CLIENT_ID` and `IAP_OAUTH_CLIENT_SECRET_NAME`. Every deploy and
> `setup --only iap` then applies the client to each IAP service with
> `gcloud beta iap settings set`. The secret is read at deploy time and passed
> in a private temp file, never on a command line. A custom client also lifts
> the requirement that the project belongs to an organization.

If an organization policy refuses a member (domain restricted sharing admits
only the customer IDs it lists), every other member is still granted, and
the run then fails, listing the refused members and the policy.

Both services are deployed with `gcloud beta run deploy --iap`, because the
flag exists only in the beta track. After each deploy of either service the
script:

1. creates the IAP service agent (`gcloud beta services identity create`),
2. grants it `roles/run.invoker` on that service,
3. grants each member `roles/iap.httpsResourceAccessor` on that service with
   `gcloud beta iap web add-iam-policy-binding --resource-type=cloud-run`.

IAP access is per service, so the allowlist is bound separately to the client
and to the sign server. A member signs in once per service hostname: opening
a media link from the client can show a brief Google sign-in redirect for the
sign server the first time.

### Adding and removing people

`IAP_MEMBERS` takes any principal type — `user:`, `group:`, `domain:` and
`serviceAccount:` — and there are two ways to change it. Neither builds an
image or creates a revision.

**Edit the whole list with `setup --only iap`.** It asks for the members, or
reads the config with `-y`, then makes each service's access match the list:
members you added are granted, members you removed are revoked.

```bash
$EDITOR deploy/config/dev.env                  # edit IAP_MEMBERS, then:
./deploy/deploy.sh setup --env dev --only iap -y

./deploy/deploy.sh setup --env dev --only iap  # or edit it at the prompt
```

Use this after editing the config by hand, because it is the one that also
removes people who are no longer listed. Email addresses are compared without
regard to case. Removal takes out every binding of the member, conditional
ones included. If the live policy cannot be read, a service cannot be
checked, or a removal fails, the run fails, so nobody keeps access silently.


---

## Everyday deploys

```bash
./deploy/deploy.sh --env dev                                  # build + deploy mcp, then client
./deploy/deploy.sh --env dev --only client                    # one service
./deploy/deploy.sh --env dev --only sign                      # update the sign server
./deploy/deploy.sh --env prod --only mcp --tag 0.6.3-ab12cd3  # redeploy / roll back, no build
./deploy/deploy.sh --env dev --dry-run                        # print commands, change nothing
```

| Flag | Values | Default |
| --- | --- | --- |
| `-e, --env` | name of a `deploy/config/<env>.env` | required |
| `--only` | `mcp` \| `client` \| `sign` | `mcp` and `client` |
| `--tag` | existing image tag; skips the build. Needs `--only` | `<version>-<git short sha>` |
| `--dry-run` | — | off |
| `-y, --yes` | skip the confirmation prompt | off |

### What a deploy does

1. **Validate and preflight** (seconds): the config exists and its settings
   are consistent, the client has exactly one access mode, no extra-keys file
   sets a key the deploy owns, `gcloud` has an active account (and the `beta`
   component when IAP is on), the project exists with billing enabled, and
   the setup resources exist (registry,
   bucket, service accounts, and the session secret when SQL is enabled).
   Missing pieces are listed together with the `setup` command that creates
   them. All of this runs before anything changes, so a deploy does not stop
   halfway for a reason it could have seen up front.
2. **Build on Cloud Build.** Vendored fonts are synced into
   `mcp_montage/assets/fonts` first, because `third_party/` is outside the
   build context. Images are tagged `<version>-<sha>`, with `-dirty` added
   when the service directory has uncommitted changes.
3. **Deploy `montage-mcp`** with `--no-allow-unauthenticated`, grant the
   client's service account `run.invoker`, then read the URL back with
   `gcloud run services describe`.
4. **Deploy `montage-client`** with `SERVER_URL` set to that URL, IAP or
   public access, and VPC egress plus the session secret when SQL is
   enabled. With SQL off, the VPC and the secret are detached, so turning SQL
   off takes effect. Switching a service from IAP to public turns IAP off.
   Then apply the IAP bindings.
5. **Smoke test** montage's `/version`:
   - **Without credentials** it must be refused. An HTTP 2xx fails the
     deploy with the command that removes public access.
   - **With your identity token** (`gcloud auth print-identity-token`) it
     should return 200. Anything else is a warning, because it can also mean
     your own account lacks `run.invoker`.
6. **Access check** of each browser-facing service deployed:
   - **Behind IAP** an anonymous request must be redirected to Google
     sign-in. HTTP 2xx fails the deploy. Anything else is a warning, since
     IAP can take minutes to start after it is first enabled.
   - **Public** it must not be refused. HTTP 401/403 fails the deploy: gcloud
     only warns when an organization policy blocks the `allUsers` binding,
     so this is where that is caught.

### Environment variables

Local `.env` files are **never** used for deploys; they point at localhost
and may hold personal API keys. They are also excluded from Cloud Build
uploads and images by each service's `.gcloudignore` and `.dockerignore`.

Each deploy renders a YAML env file per service into a temp directory:

| Service | Set by the script | Extra keys from |
| --- | --- | --- |
| mcp | `GOOGLE_GENAI_USE_VERTEXAI=True`, `GOOGLE_CLOUD_PROJECT`, `GOOGLE_CLOUD_LOCATION`, `GCS_BUCKET_NAME`, `LOG_TO_FILE=False`, `VIEW_ENDPOINT` (when set) | `deploy/config/<env>.mcp.env` |
| client | `GOOGLE_GENAI_USE_VERTEXAI=True`, `GOOGLE_CLOUD_PROJECT`, `GOOGLE_CLOUD_LOCATION`, `GCS_BUCKET_NAME`, `SERVER_CONNECTION_TYPE=http`, `SERVER_URL`, `GCS_INGREDIENT_IMAGES_FOLDER` (when set) | `deploy/config/<env>.client.env` |
| sign | `GOOGLE_CLOUD_PROJECT`, `GCS_BUCKET_NAME` | `deploy/config/<env>.sign.env` |

The extra files use dotenv syntax (`KEY=value`). Keys set by the script win
over them. Keys Cloud Run reserves (`PORT`, `K_SERVICE`, `K_REVISION`,
`K_CONFIGURATION`), and `SESSION_SERVICE_URI` in the client file when SQL is
enabled, are rejected before the deploy starts.

> [!WARNING]
> `--env-vars-file` **replaces** a service's entire environment. Variables
> set by hand in the Cloud Console are removed on the next deploy. Put them in
> the extra-keys file instead.

---

## Organization policies

An organization can forbid what the defaults do. Each constraint below has a
config key (see [`config/example.env`](./config/example.env)) that works
within it. Every key is off by default, so an environment that sets none of
them builds, stores secrets and deploys as it did before.

| Constraint | What it stops | Set |
| --- | --- | --- |
| `iam.allowedPolicyMemberDomains` (domain restricted sharing) | The `allUsers` binding of a public client or sign server; IAP members from other organizations | `PUBLIC_INVOKER_IAM_CHECK="0"` (deploys with `--no-invoker-iam-check`); allow the member's customer ID, or remove the member |
| *(no constraint; Google's OAuth client)* | Sign-in by IAP members from other organizations | `IAP_OAUTH_CLIENT_ID`, `IAP_OAUTH_CLIENT_SECRET_NAME` |
| Workforce Identity Federation | Members without a Google account | `principal://…` / `principalSet://…` in `IAP_MEMBERS` |
| `gcp.resourceLocations` | Builds in `global` and their US staging bucket; secrets with automatic replication | `BUILD_REGION`; `SECRET_LOCATIONS` |
| `cloudbuild.allowedWorkerPools` | Builds on the default pool | `BUILD_WORKER_POOL` (its region is used for the build) |
| `iam.automaticIamGrantsForDefaultServiceAccounts`, or a deleted Compute Engine default account | Builds as the default account | `BUILD_SERVICE_ACCOUNT` |
| `iam.disableServiceAccountCreation` | Creating `montage-*` accounts | `MCP_SERVICE_ACCOUNT`, `CLIENT_SERVICE_ACCOUNT`, `SIGN_SERVICE_ACCOUNT` |
| `gcp.restrictServiceUsage`, or no `serviceusage.services.enable` | Enabling APIs | Have them enabled; setup only enables APIs that are off |
| `gcp.restrictNonCmekServices` | Registry, bucket, SQL, secret and Cloud Run without CMEK | `KMS_KEY`, a key in `REGION`. The Cloud Build staging bucket is not covered. |
| `compute.restrictVpcPeering`, or a required Shared VPC | Creating the VPC and its peering | `VPC_NETWORK` / `VPC_SUBNET` as `projects/HOST/…` paths |
| `run.allowedIngress`, `run.allowedBinaryAuthorizationPolicies`, `run.allowedVPCEgress`, custom Cloud Run constraints | Deploys without the required setting | `RUN_DEPLOY_FLAGS`, or `MCP_`/`CLIENT_`/`SIGN_DEPLOY_FLAGS` |

When a command fails because of one of these constraints, the script keeps
gcloud's error and adds `HINT:` lines: the constraint, the setting from this
table, the config file to put it in, and the command that shows what the
policy allows:

```text
ERROR: (gcloud.secrets.create) FAILED_PRECONDITION: Constraint constraints/gcp.resourceLocations violated ...
HINT: An organization policy refused this: constraints/gcp.resourceLocations.
HINT: Keep everything in an allowed location: REGION, BUILD_REGION="<region>" for Cloud Build,
HINT: and SECRET_LOCATIONS="<region>" for the session secret.
HINT: See what it allows: gcloud org-policies describe gcp.resourceLocations --project=PROJECT_ID --effective
HINT: Settings go in deploy/config/dev.env; each key is described in deploy/config/example.env,
HINT: and deploy/README.md lists them by organization policy.
```

A constraint not in the table (a custom one, say) gets the pass-through
flags as its hint. To see which constraints apply to a project:

```bash
gcloud org-policies list --project=PROJECT_ID
```

---

## Building

All images build on Cloud Build with one shared
[`cloudbuild.yaml`](./cloudbuild.yaml), using the service directory as the
build context:

```bash
gcloud builds submit mcp_montage --config=deploy/cloudbuild.yaml \
  --substitutions=_IMAGE=<registry path>,_VERSION=<tag> \
  --machine-type=e2-highcpu-8 --disk-size=200 --timeout=2400s
```

Machine type, disk and timeout are passed as flags per service, so one config
file serves all three. The montage `Dockerfile` uses BuildKit cache mounts
(`RUN --mount=type=cache`), so the config builds with `buildx` and keeps a
layer cache in the registry. Cloud Build workers are ephemeral, so that
cache is the only one that survives between builds.

---

## Tests

```bash
bash deploy/tests/run.sh          # all tests
bash deploy/tests/run.sh sql      # tests whose name contains "sql"
(cd sign_server && uv run pytest) # sign server
```

`deploy/tests/run.sh` runs the real script in a throwaway git repository
against stub `gcloud` and `curl` executables that record every call. It
asserts the commands that matter:

- montage is private and runs as its own service account,
- the client has an invoker binding, and the client and sign server get the
  right access mode and the same IAP bindings,
- secrets reach Secret Manager only over stdin,
- local `.env` values never reach a service,
- setup is idempotent,
- `--dry-run` issues no mutating command,
- nothing changes when a check cannot run, and removing IAP members never
  fails silently,
- each organization policy setting reaches the right gcloud flag.

The stub answers success to any command it does not recognize, so a test of
a failure path must make that command fail (`STUB_ERROR`, `STUB_MISSING`, and
the other `STUB_*` switches in `write_gcloud_stub`).

Extend it whenever a flag or resource changes.
