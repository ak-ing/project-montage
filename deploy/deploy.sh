#!/usr/bin/env bash
# Copyright 2026 Google LLC
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

#
# Project Montage — Cloud Run setup and deployment.
#
#   ./deploy/deploy.sh setup --env dev     # first time: create resources
#   ./deploy/deploy.sh --env dev           # every release: build and deploy
#
# See deploy/README.md for the full guide. Runs on bash 3.2+ (macOS), Linux
# and Git Bash on Windows, so it avoids associative arrays and GNU-only flags.

set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
CONFIG_DIR="$ROOT/deploy/config"

# ---------- CLI state ----------
COMMAND="deploy"
ENV_NAME=""
ONLY=""
TAG=""
DRY_RUN=0
ASSUME_YES=0
WORK_DIR=""

# Every key persisted to deploy/config/<env>.env, in file order.
CONFIG_KEYS=(
  PROJECT_ID REGION GOOGLE_CLOUD_LOCATION AR_REPO GCS_BUCKET_NAME
  GCS_INGREDIENT_IMAGES_FOLDER
  MCP_SERVICE CLIENT_SERVICE SIGN_SERVICE
  MCP_SERVICE_ACCOUNT CLIENT_SERVICE_ACCOUNT SIGN_SERVICE_ACCOUNT
  SQL_ENABLED SQL_INSTANCE SQL_TIER SQL_DATABASE SQL_USER
  VPC_NETWORK VPC_SUBNET VPC_SUBNET_RANGE
  SIGN_ENABLED VIEW_ENDPOINT
  IAP_ENABLED IAP_MEMBERS CLIENT_PUBLIC PUBLIC_INVOKER_IAM_CHECK
  IAP_OAUTH_CLIENT_ID IAP_OAUTH_CLIENT_SECRET_NAME
  BUILD_REGION BUILD_WORKER_POOL BUILD_SERVICE_ACCOUNT
  SECRET_LOCATIONS KMS_KEY
  RUN_DEPLOY_FLAGS MCP_DEPLOY_FLAGS CLIENT_DEPLOY_FLAGS SIGN_DEPLOY_FLAGS
  MCP_CPU MCP_MEMORY MCP_TIMEOUT CLIENT_MEMORY CLIENT_TIMEOUT SIGN_MEMORY
)

# Environment variables Cloud Run sets itself; a service cannot override them.
RESERVED_ENV_KEYS="PORT K_SERVICE K_REVISION K_CONFIGURATION"

# =========================================================================
#  Output and execution helpers
# =========================================================================

log() { printf '==> %s\n' "$*" >&2; }
info() { printf '    %s\n' "$*" >&2; }
warn() { printf 'WARN: %s\n' "$*" >&2; }
die() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

is_true() {
  case "${1:-}" in
    1 | true | TRUE | True | yes | y | Y) return 0 ;;
    *) return 1 ;;
  esac
}

is_interactive() {
  [[ -t 0 ]] && ! is_true "$ASSUME_YES"
}

# run CMD... — executes a mutating command, or only prints it on --dry-run.
run() {
  printf '+ %s\n' "$*" >&2
  if is_true "$DRY_RUN"; then
    return 0
  fi
  run_explained "$@"
}

# run_quiet CMD... — like run, discarding stdout (IAM commands print policies).
run_quiet() {
  printf '+ %s\n' "$*" >&2
  if is_true "$DRY_RUN"; then
    return 0
  fi
  run_explained "$@" > /dev/null
}

# run_masked SECRET CMD... — like run, hiding SECRET in the printed command.
run_masked() {
  local secret=$1
  shift
  local shown="$*"
  printf '+ %s\n' "${shown//"$secret"/********}" >&2
  if is_true "$DRY_RUN"; then
    return 0
  fi
  run_explained "$@" > /dev/null
}

# run_explained CMD... — runs CMD with its stderr shown as it arrives and
# also kept, so that when CMD fails, a refusal by an organization policy can
# be explained with the setting that works within it. stdin and stdout pass
# through untouched.
run_explained() {
  local status=0 errfile
  ensure_work_dir
  errfile="$WORK_DIR/stderr.log"
  { "$@" 2>&1 1>&3 3>&- | tee "$errfile" >&2; } 3>&1 || status=$?
  if [[ $status -ne 0 ]]; then
    explain_policy_rejection "$(cat "$errfile")"
  fi
  return "$status"
}

# policy_constraint GCLOUD_ERROR — the constraint an organization policy
# refusal names (e.g. gcp.resourceLocations), or nothing. Two refusals do not
# name theirs, so they are recognized by their wording.
policy_constraint() {
  local constraint
  constraint=$(printf '%s\n' "$1" | grep -o 'constraints/[A-Za-z0-9_.]*' | head -n 1) || constraint=""
  constraint=${constraint#constraints/}
  while [[ "$constraint" == *. ]]; do
    constraint=${constraint%.}
  done
  if [[ -z "$constraint" ]]; then
    case "$1" in
      *"permitted customer"*) constraint=iam.allowedPolicyMemberDomains ;;
      *"Service account creation is not allowed"*) constraint=iam.disableServiceAccountCreation ;;
    esac
  fi
  printf '%s' "$constraint"
}

# is_policy_rejection GCLOUD_ERROR — an organization policy refused the change,
# which no retry can fix.
is_policy_rejection() {
  [[ -n "$(policy_constraint "$1")" ]] && return 0
  case "$1" in
    *"organization policy"* | *"Organization Policy"*) return 0 ;;
    *) return 1 ;;
  esac
}

hint() {
  printf 'HINT: %s\n' "$*" >&2
}

# explain_policy_rejection GCLOUD_ERROR — when an organization policy refused
# a command, names the setting that works within it and where it is set.
# Silent for any other failure.
explain_policy_rejection() {
  local error=$1 constraint
  if [[ "$error" == *"-compute@developer.gserviceaccount.com"*"does not exist"* ]]; then
    hint "The Compute Engine default service account is gone; organizations often delete it."
    hint "Set BUILD_SERVICE_ACCOUNT to the service account Cloud Build should run as."
    settings_hint
    return 0
  fi
  is_policy_rejection "$error" || return 0
  constraint=$(policy_constraint "$error")
  hint "An organization policy refused this${constraint:+: constraints/$constraint}."
  case "$constraint" in
    iam.allowedPolicyMemberDomains)
      hint "Domain restricted sharing only admits principals of the organizations it lists."
      hint "For a public client or sign server, set PUBLIC_INVOKER_IAM_CHECK=\"0\"."
      hint "For an IAP member, have an organization policy admin allow its customer ID, or remove it from IAP_MEMBERS."
      ;;
    gcp.resourceLocations)
      hint "Keep everything in an allowed location: REGION, BUILD_REGION=\"<region>\" for Cloud Build,"
      hint "and SECRET_LOCATIONS=\"<region>\" for the session secret."
      ;;
    cloudbuild.allowedWorkerPools)
      hint "Build on an allowed private pool: BUILD_WORKER_POOL=\"projects/PROJECT/locations/REGION/workerPools/POOL\"."
      ;;
    iam.disableServiceAccountCreation)
      hint "Have an admin create the service accounts, then name them in MCP_SERVICE_ACCOUNT,"
      hint "CLIENT_SERVICE_ACCOUNT and SIGN_SERVICE_ACCOUNT."
      ;;
    gcp.restrictNonCmekServices | gcp.restrictCmekCryptoKeyProjects)
      hint "Encrypt with an allowed key: KMS_KEY=\"projects/PROJECT/locations/$REGION/keyRings/RING/cryptoKeys/KEY\","
      hint "then re-run setup, whose kms step lets each service use the key."
      ;;
    compute.restrictVpcPeering | compute.restrictSharedVpc* | compute.restrictXpn*)
      hint "Use a Shared VPC the policy allows: VPC_NETWORK=\"projects/HOST/global/networks/NAME\""
      hint "and VPC_SUBNET=\"projects/HOST/regions/$REGION/subnetworks/NAME\"."
      ;;
    gcp.restrictServiceUsage)
      hint "An API Montage needs is not allowed in this project. Ask an organization policy admin"
      hint "to allow the APIs in the command above."
      ;;
    run.allowedIngress)
      hint "Deploy with an allowed ingress, e.g. RUN_DEPLOY_FLAGS=\"--ingress=internal-and-cloud-load-balancing\"."
      ;;
    run.allowedBinaryAuthorizationPolicies)
      hint "Deploy with Binary Authorization: RUN_DEPLOY_FLAGS=\"--binary-authorization=default\"."
      ;;
    run.allowedVPCEgress)
      hint "Route egress as the policy requires, e.g. --vpc-egress=all-traffic with --network and --subnet,"
      hint "in MCP_DEPLOY_FLAGS, CLIENT_DEPLOY_FLAGS or SIGN_DEPLOY_FLAGS."
      ;;
    *)
      hint "Pass the setting it requires with RUN_DEPLOY_FLAGS (every service), or MCP_DEPLOY_FLAGS,"
      hint "CLIENT_DEPLOY_FLAGS or SIGN_DEPLOY_FLAGS, or ask an organization policy admin."
      ;;
  esac
  if [[ -n "$constraint" ]]; then
    hint "See what it allows: gcloud org-policies describe $constraint --project=$PROJECT_ID --effective"
  fi
  settings_hint
}

settings_hint() {
  hint "Settings go in $(config_file); each key is described in deploy/config/example.env,"
  hint "and deploy/README.md lists them by organization policy."
}

# try_bind CMD... — an IAM binding, retried while new service accounts
# propagate. Returns 1 with gcloud's error in BIND_ERROR when it still fails;
# a refusal by an organization policy is not retried.
BIND_ERROR=""
try_bind() {
  local attempt
  BIND_ERROR=""
  for attempt in 1 2 3; do
    printf '+ %s\n' "$*" >&2
    if is_true "$DRY_RUN"; then
      return 0
    fi
    if BIND_ERROR=$("$@" 2>&1 > /dev/null); then
      return 0
    fi
    printf '%s\n' "$BIND_ERROR" >&2
    if is_policy_rejection "$BIND_ERROR" || [[ $attempt -eq 3 ]]; then
      break
    fi
    warn "Binding failed, retrying in ${SA_PROPAGATION_SECONDS}s (attempt $attempt/3)"
    sleep "$SA_PROPAGATION_SECONDS"
  done
  return 1
}

# bind CMD... — try_bind, dying when the binding cannot be made.
bind() {
  if ! try_bind "$@"; then
    explain_policy_rejection "$BIND_ERROR"
    die "IAM binding failed: $*"
  fi
}

# exists GCLOUD_ARGS... — runs a read-only describe.
# Returns 0 when found, 1 when not found, and 2 for any other failure
# (disabled API, missing permission), leaving gcloud's error in EXISTS_ERROR.
EXISTS_ERROR=""
exists() {
  local output
  EXISTS_ERROR=""
  if output=$(gcloud "$@" --format="value(name)" 2>&1 > /dev/null); then
    return 0
  fi
  check_failed "$output"
}

# check_failed GCLOUD_STDERR — exists' status for a failed check: 1 when the
# resource is not there, else 2 with the error in EXISTS_ERROR.
check_failed() {
  case "$1" in
    *NOT_FOUND* | *"not found"* | *"Not Found"* | *"does not exist"* | *"Cannot find"*)
      return 1
      ;;
  esac
  EXISTS_ERROR=$(printf '%s\n' "$1" | grep -m 1 'ERROR' || printf '%s\n' "$1" | head -n 1)
  return 2
}

# found GCLOUD_ARGS... — exists for setup: dies when the resource could not be
# checked, so setup never tries to create what may already exist.
found() {
  local status=0
  exists "$@" || status=$?
  case "$status" in
    0) return 0 ;;
    1) return 1 ;;
  esac
  printf '%s\n' "$EXISTS_ERROR" >&2
  explain_policy_rejection "$EXISTS_ERROR"
  die "Could not check whether it exists: gcloud $*"
}

# check_project — the project must already exist with billing linked; this
# script never creates one. Runs before anything changes.
check_project() {
  local status=0 billing
  exists projects describe "$PROJECT_ID" || status=$?
  if [[ $status -ne 0 ]]; then
    [[ -z "$EXISTS_ERROR" ]] || printf '%s\n' "$EXISTS_ERROR" >&2
    printf 'ERROR: Project %s does not exist, or your account cannot access it.\n' "$PROJECT_ID" >&2
    info "Create it (inside your organization if you use IAP), link billing, then re-run:"
    info "  gcloud projects create $PROJECT_ID --organization=ORGANIZATION_ID   # or --folder=FOLDER_ID"
    info "  gcloud billing projects link $PROJECT_ID --billing-account=BILLING_ACCOUNT_ID"
    info "Billing accounts you can use: gcloud billing accounts list"
    exit 1
  fi
  billing=$(gcloud billing projects describe "$PROJECT_ID" --format="value(billingEnabled)" 2> /dev/null) || billing=""
  case "$billing" in
    True) ;;
    False)
      printf 'ERROR: Billing is not enabled for project %s, so its APIs cannot be used.\n' "$PROJECT_ID" >&2
      info "Link a billing account, then re-run:"
      info "  gcloud billing projects link $PROJECT_ID --billing-account=BILLING_ACCOUNT_ID"
      info "Billing accounts you can use: gcloud billing accounts list"
      exit 1
      ;;
    *) warn "Could not confirm billing is enabled for $PROJECT_ID." ;;
  esac
}

# check_credentials — fail fast when gcloud cannot mint a token, instead of
# letting a later call wait on an invisible reauthentication prompt.
check_credentials() {
  local account output
  command -v gcloud > /dev/null || die "gcloud CLI not found. Install: https://cloud.google.com/sdk/docs/install"
  account=$(gcloud auth list --filter=status:ACTIVE --format="value(account)" 2> /dev/null | head -n 1) || account=""
  [[ -n "$account" ]] || die "gcloud has no active account. Run: gcloud auth login"
  info "gcloud account: $account"
  if ! output=$(gcloud auth print-access-token 2>&1 > /dev/null); then
    printf '%s\n' "$output" >&2
    die "gcloud credentials for $account are not usable. Run: gcloud auth login (add --no-launch-browser over SSH)"
  fi
}

confirm() {
  local prompt=$1 reply
  if is_true "$ASSUME_YES" || is_true "$DRY_RUN"; then
    return 0
  fi
  [[ -t 0 ]] || die "Not running interactively; pass -y to confirm: $prompt"
  read -r -p "$prompt [y/N]: " reply || true
  case "$reply" in
    y | Y | yes | YES) return 0 ;;
    *) die "Aborted." ;;
  esac
}

cleanup() {
  if [[ -n "$WORK_DIR" ]]; then
    rm -rf "$WORK_DIR"
  fi
}

# =========================================================================
#  Configuration
# =========================================================================

config_file() {
  printf '%s/%s.env' "$CONFIG_DIR" "$ENV_NAME"
}

apply_defaults() {
  : "${PROJECT_ID:=}"
  : "${REGION:=us-central1}"
  : "${GOOGLE_CLOUD_LOCATION:=global}"
  : "${AR_REPO:=montage}"
  : "${GCS_BUCKET_NAME:=}"
  : "${GCS_INGREDIENT_IMAGES_FOLDER:=}"
  : "${MCP_SERVICE:=montage-mcp}"
  : "${CLIENT_SERVICE:=montage-client}"
  : "${SIGN_SERVICE:=montage-sign}"
  : "${MCP_SERVICE_ACCOUNT:=}"
  : "${CLIENT_SERVICE_ACCOUNT:=}"
  : "${SIGN_SERVICE_ACCOUNT:=}"
  : "${SQL_ENABLED:=0}"
  : "${SQL_INSTANCE:=montage-sql}"
  : "${SQL_TIER:=db-f1-micro}"
  : "${SQL_DATABASE:=montage}"
  : "${SQL_USER:=montage}"
  : "${VPC_NETWORK:=montage-vpc}"
  : "${VPC_SUBNET:=montage-subnet}"
  : "${VPC_SUBNET_RANGE:=10.10.0.0/24}"
  : "${SIGN_ENABLED:=0}"
  : "${VIEW_ENDPOINT:=}"
  : "${IAP_ENABLED:=0}"
  : "${IAP_MEMBERS:=}"
  : "${CLIENT_PUBLIC:=0}"
  : "${PUBLIC_INVOKER_IAM_CHECK:=1}"
  : "${IAP_OAUTH_CLIENT_ID:=}"
  : "${IAP_OAUTH_CLIENT_SECRET_NAME:=}"
  : "${BUILD_REGION:=}"
  : "${BUILD_WORKER_POOL:=}"
  : "${BUILD_SERVICE_ACCOUNT:=}"
  : "${SECRET_LOCATIONS:=}"
  : "${KMS_KEY:=}"
  : "${RUN_DEPLOY_FLAGS:=}"
  : "${MCP_DEPLOY_FLAGS:=}"
  : "${CLIENT_DEPLOY_FLAGS:=}"
  : "${SIGN_DEPLOY_FLAGS:=}"
  : "${MCP_CPU:=2}"
  : "${MCP_MEMORY:=8Gi}"
  : "${MCP_TIMEOUT:=1200}"
  : "${CLIENT_MEMORY:=2Gi}"
  : "${CLIENT_TIMEOUT:=1200}"
  : "${SIGN_MEMORY:=512Mi}"
  : "${SA_PROPAGATION_SECONDS:=10}"
}

# Names derived from the config; recomputed after every change to it.
derive_names() {
  MCP_SA=${MCP_SERVICE_ACCOUNT:-"montage-mcp@$PROJECT_ID.iam.gserviceaccount.com"}
  CLIENT_SA=${CLIENT_SERVICE_ACCOUNT:-"montage-client@$PROJECT_ID.iam.gserviceaccount.com"}
  SIGN_SA=${SIGN_SERVICE_ACCOUNT:-"montage-sign@$PROJECT_ID.iam.gserviceaccount.com"}
  REGISTRY="$REGION-docker.pkg.dev/$PROJECT_ID/$AR_REPO"
  SESSION_SECRET="montage-session-uri-$ENV_NAME"
  PSA_RANGE="$VPC_NETWORK-psa"
}

require_env_name() {
  [[ -n "$ENV_NAME" ]] || die "--env is required (e.g. --env dev)"
  [[ "$ENV_NAME" =~ ^[a-z0-9][a-z0-9-]*$ ]] ||
    die "--env must be lowercase letters, digits and hyphens: $ENV_NAME"
}

# load_config required|optional
load_config() {
  local file
  require_env_name
  file=$(config_file)
  if [[ -f "$file" ]]; then
    # shellcheck source=/dev/null
    source "$file"
  elif [[ "$1" == required ]]; then
    die "No config for env '$ENV_NAME'. Run: ./deploy/deploy.sh setup --env $ENV_NAME"
  fi
  apply_defaults
  strip_carriage_returns
  derive_names
}

# A config file written on Windows leaves a CR on every value when bash reads
# it on Linux or macOS. Unstripped it travels into gcloud flags and rendered
# env files, where it is invisible and breaks comparisons and URLs.
strip_carriage_returns() {
  local key value
  for key in "${CONFIG_KEYS[@]}"; do
    value=${!key-}
    if [[ "$value" == *$'\r'* ]]; then
      printf -v "$key" '%s' "${value//$'\r'/}"
    fi
  done
}

# quote VALUE — double-quoted, safe to source.
quote() {
  local value=$1
  value=${value//\\/\\\\}
  value=${value//\"/\\\"}
  value=${value//\$/\\\$}
  value=${value//\`/\\\`}
  printf '"%s"' "$value"
}

write_config() {
  local file key
  file=$(config_file)
  if is_true "$DRY_RUN"; then
    info "(dry run) would write $file"
    return 0
  fi
  mkdir -p "$CONFIG_DIR"
  {
    printf '# Project Montage deployment config for env "%s".\n' "$ENV_NAME"
    printf '# Written by ./deploy/deploy.sh setup, which rewrites this file.\n'
    printf '# Keys are documented in deploy/config/example.env.\n'
    for key in "${CONFIG_KEYS[@]}"; do
      printf '%s=%s\n' "$key" "$(quote "${!key-}")"
    done
  } > "$file"
  info "Saved $file"
}

validate_access_mode() {
  if is_true "$IAP_ENABLED" && is_true "$CLIENT_PUBLIC"; then
    die "IAP_ENABLED and CLIENT_PUBLIC are both set. IAP cannot protect a publicly invocable service; choose one."
  fi
  if ! is_true "$IAP_ENABLED" && ! is_true "$CLIENT_PUBLIC"; then
    die "The client has no access mode. Set IAP_ENABLED=1 with IAP_MEMBERS, or CLIENT_PUBLIC=1 to allow anyone with the URL. Run: ./deploy/deploy.sh setup --env $ENV_NAME"
  fi
  if is_true "$IAP_ENABLED"; then
    validate_iap_members
  fi
}

# members_list — IAP_MEMBERS as one trimmed member per line.
members_list() {
  local member members=()
  IFS=',' read -r -a members <<< "$IAP_MEMBERS"
  for member in ${members[@]+"${members[@]}"}; do
    member=${member//[[:space:]]/}
    [[ -n "$member" ]] && printf '%s\n' "$member"
  done
  return 0
}

# principal:// and principalSet:// name Workforce Identity Federation
# identities, for organizations that sign in through an external IdP.
member_has_prefix() {
  case "$1" in
    user:?* | group:?* | domain:?* | serviceAccount:?*) return 0 ;;
    principal://?* | principalSet://?*) return 0 ;;
    *) return 1 ;;
  esac
}

# member_key MEMBER — the form two members are compared in. Email addresses
# and domains are case-insensitive; federated identifiers are not.
member_key() {
  case "$1" in
    user:* | group:* | domain:* | serviceAccount:*)
      printf '%s' "$1" | tr '[:upper:]' '[:lower:]'
      ;;
    *) printf '%s' "$1" ;;
  esac
}

# IAM rejects members without a type prefix, so catch them before deploying.
validate_iap_members() {
  [[ -n "${IAP_MEMBERS// /}" ]] ||
    die "IAP_ENABLED=1 needs IAP_MEMBERS (e.g. group:team@example.com)."
  local members=()
  while IFS= read -r member; do members+=("$member"); done < <(members_list)
  validate_members ${members[@]+"${members[@]}"}
}

# validate_members MEMBERS... — dies listing any without a type prefix.
validate_members() {
  local member invalid=()
  for member in "$@"; do
    member_has_prefix "$member" || invalid+=("$member")
  done
  if [[ ${#invalid[@]} -gt 0 ]]; then
    printf 'ERROR: members need a type prefix (user:, group:, domain:, serviceAccount:, principal:// or principalSet://):\n' >&2
    for member in "${invalid[@]}"; do
      if [[ "$member" == allUsers || "$member" == allAuthenticatedUsers ]]; then
        printf '  - %s  (public access is CLIENT_PUBLIC=1 with IAP_ENABLED=0, not an IAP member)\n' "$member" >&2
      elif [[ "$member" == *@* ]]; then
        printf '  - %s  (an account would be user:%s)\n' "$member" "$member" >&2
      else
        printf '  - %s  (a Workspace domain would be domain:%s)\n' "$member" "$member" >&2
      fi
    done
    die "Fix the value passed on the command line, or IAP_MEMBERS in $(config_file)"
  fi
}

# A bare name would be read as a bucket of that name, which anyone may own.
validate_gcs_folder() {
  [[ -z "$GCS_INGREDIENT_IMAGES_FOLDER" ||
    "$GCS_INGREDIENT_IMAGES_FOLDER" =~ ^gs://[a-z0-9][a-z0-9._-]*[a-z0-9](/.*)?$ ]] ||
    die "GCS_INGREDIENT_IMAGES_FOLDER must be a gs://bucket/path URI, or empty: $GCS_INGREDIENT_IMAGES_FOLDER"
}

# kms_key_location — the location segment of KMS_KEY.
kms_key_location() {
  local rest=${KMS_KEY#*/locations/}
  printf '%s' "${rest%%/*}"
}

# A regional key can only encrypt resources in its own region, so every
# resource setup creates must share it, the session secret included.
validate_kms_key() {
  [[ -n "$KMS_KEY" ]] || return 0
  [[ "$KMS_KEY" =~ ^projects/[^/]+/locations/[^/]+/keyRings/[^/]+/cryptoKeys/[^/]+$ ]] ||
    die "KMS_KEY must be projects/PROJECT/locations/LOCATION/keyRings/RING/cryptoKeys/KEY: $KMS_KEY"
  [[ "$(kms_key_location)" == "$REGION" ]] ||
    die "KMS_KEY is in $(kms_key_location), but Cloud Run, the registry, bucket and SQL are in $REGION. Use a key in $REGION."
  if [[ -n "$SECRET_LOCATIONS" && "$SECRET_LOCATIONS" != "$REGION" ]]; then
    die "With KMS_KEY the session secret is kept in the key's location only. Set SECRET_LOCATIONS=\"$REGION\" or leave it empty."
  fi
}

# A Shared VPC network is named by its path in the host project.
is_shared_vpc() {
  [[ "$VPC_NETWORK" == projects/* ]]
}

vpc_network_path() {
  if is_shared_vpc; then
    printf '%s' "$VPC_NETWORK"
  else
    printf 'projects/%s/global/networks/%s' "$PROJECT_ID" "$VPC_NETWORK"
  fi
}

validate_network() {
  is_true "$SQL_ENABLED" && is_shared_vpc || return 0
  [[ "$VPC_NETWORK" =~ ^projects/[^/]+/global/networks/[^/]+$ ]] ||
    die "VPC_NETWORK must be a network name or projects/HOST/global/networks/NAME: $VPC_NETWORK"
  [[ "$VPC_SUBNET" =~ ^projects/[^/]+/regions/$REGION/subnetworks/[^/]+$ ]] ||
    die "VPC_NETWORK is a Shared VPC path, so VPC_SUBNET must be one too: projects/HOST/regions/$REGION/subnetworks/NAME"
}

validate_oauth_client() {
  [[ -z "$IAP_OAUTH_CLIENT_ID" || -n "$IAP_OAUTH_CLIENT_SECRET_NAME" ]] ||
    die "IAP_OAUTH_CLIENT_ID needs IAP_OAUTH_CLIENT_SECRET_NAME: the Secret Manager secret in $PROJECT_ID that holds the client secret."
}

# Cloud Run names a service with lowercase letters, digits and hyphens: it
# starts with a letter, cannot end with a hyphen and is at most 49 long.
validate_service_names() {
  local key keys=(MCP_SERVICE CLIENT_SERVICE)
  if is_true "$SIGN_ENABLED"; then
    keys+=(SIGN_SERVICE)
  fi
  for key in "${keys[@]}"; do
    [[ "${!key}" =~ ^[a-z]([a-z0-9-]{0,47}[a-z0-9])?$ ]] ||
      die "$key must be lowercase letters, digits and hyphens, start with a letter, not end with a hyphen and be at most 49 characters: ${!key}"
  done
  if [[ "$MCP_SERVICE" == "$CLIENT_SERVICE" ]] ||
    { is_true "$SIGN_ENABLED" && [[ "$SIGN_SERVICE" == "$MCP_SERVICE" || "$SIGN_SERVICE" == "$CLIENT_SERVICE" ]]; }; then
    die "MCP_SERVICE, CLIENT_SERVICE and SIGN_SERVICE must differ: $MCP_SERVICE, $CLIENT_SERVICE, $SIGN_SERVICE"
  fi
}

# validate_settings — the checks setup and deploy share.
validate_settings() {
  validate_service_names
  validate_gcs_folder
  validate_kms_key
  validate_network
  validate_oauth_client
}

# validate_extra_env FILE KEYS... — dies when the dotenv FILE sets one of
# KEYS, which the deploy owns.
validate_extra_env() {
  local file=$1 key keys=()
  shift
  [[ -f "$file" ]] || return 0
  for key in "$@"; do
    if grep -Eq "^[[:space:]]*(export[[:space:]]+)?${key}[[:space:]]*=" "$file"; then
      keys+=("$key")
    fi
  done
  if [[ ${#keys[@]} -gt 0 ]]; then
    die "$file sets ${keys[*]}. Cloud Run reserves PORT and K_*, and SESSION_SERVICE_URI comes from Secret Manager when SQL_ENABLED=1. Remove them."
  fi
}

# The key lists below are split into words on purpose.
# shellcheck disable=SC2086
validate_extra_envs() {
  if wants mcp; then
    validate_extra_env "$CONFIG_DIR/$ENV_NAME.mcp.env" $RESERVED_ENV_KEYS
  fi
  if wants client; then
    local client_keys=$RESERVED_ENV_KEYS
    if is_true "$SQL_ENABLED"; then
      client_keys="$client_keys SESSION_SERVICE_URI"
    fi
    validate_extra_env "$CONFIG_DIR/$ENV_NAME.client.env" $client_keys
  fi
  if wants sign; then
    validate_extra_env "$CONFIG_DIR/$ENV_NAME.sign.env" $RESERVED_ENV_KEYS
  fi
}

# service_flags EXTRA_FLAGS — sets SERVICE_FLAGS to what every Cloud Run
# deploy takes: the encryption key, then RUN_DEPLOY_FLAGS and EXTRA_FLAGS,
# split on spaces, for settings an organization policy may require (ingress,
# binary authorization, labels, egress).
SERVICE_FLAGS=()
service_flags() {
  local passed=()
  SERVICE_FLAGS=()
  if [[ -n "$KMS_KEY" ]]; then
    SERVICE_FLAGS+=("--key=$KMS_KEY")
  fi
  read -r -a passed <<< "$RUN_DEPLOY_FLAGS $1" || true
  SERVICE_FLAGS+=(${passed[@]+"${passed[@]}"})
}

ensure_work_dir() {
  if [[ -z "$WORK_DIR" ]]; then
    WORK_DIR=$(mktemp -d)
  fi
}

# yaml_quote VALUE — a double-quoted YAML scalar.
yaml_quote() {
  local value=$1
  value=${value//\\/\\\\}
  value=${value//\"/\\\"}
  printf '"%s"' "$value"
}

# =========================================================================
#  Build helpers
# =========================================================================

# image_tag DIR [EXTRA_PATHS...] — <version>-<sha>[-dirty].
image_tag() {
  local dir=$1 version sha tag
  shift
  version=$(sed -n '/^version *=/{s/^version *= *"\([^"]*\)".*/\1/p;q;}' "$dir/pyproject.toml")
  [[ -n "$version" ]] || die "Could not read version from $dir/pyproject.toml"
  if ! sha=$(git -C "$ROOT" rev-parse --short HEAD 2> /dev/null); then
    printf '%s' "$version"
    return 0
  fi
  tag="$version-$sha"
  if [[ -n "$(git -C "$ROOT" status --porcelain -- "$dir" "$@" 2> /dev/null)" ]]; then
    tag="$tag-dirty"
  fi
  printf '%s' "$tag"
}

# build_image DIR IMAGE TAG [GCLOUD_FLAGS...]
# BUILD_REGION keeps the build and its staging bucket in one region, for a
# resource location policy. A BUILD_WORKER_POOL fixes the machine and disk, so
# only the timeout of GCLOUD_FLAGS still applies.
build_image() {
  local dir=$1 image=$2 tag=$3 region=$BUILD_REGION flag flags=()
  shift 3
  if [[ -n "$BUILD_WORKER_POOL" && -z "$region" ]]; then
    region=${BUILD_WORKER_POOL#*/locations/}
    region=${region%%/*}
  fi
  if [[ -n "$region" ]]; then
    flags+=("--region=$region" --default-buckets-behavior=regional-user-owned-bucket)
  fi
  if [[ -n "$BUILD_WORKER_POOL" ]]; then
    flags+=("--worker-pool=$BUILD_WORKER_POOL")
    for flag in "$@"; do
      if [[ "$flag" == --timeout=* ]]; then
        flags+=("$flag")
      fi
    done
  else
    flags+=("$@")
  fi
  if [[ -n "$BUILD_SERVICE_ACCOUNT" ]]; then
    flags+=("--service-account=projects/$PROJECT_ID/serviceAccounts/$BUILD_SERVICE_ACCOUNT")
  fi
  log "Building $image:$tag on Cloud Build"
  run gcloud builds submit "$dir" --config=deploy/cloudbuild.yaml \
    --project="$PROJECT_ID" --substitutions="_IMAGE=$image,_VERSION=$tag" \
    ${flags[@]+"${flags[@]}"}
}

# render_env_file OUTPUT EXTRA_FILE KEY=VALUE... — YAML for --env-vars-file.
# Keys from EXTRA_FILE (dotenv, optional) come first; KEY=VALUE pairs win.
render_env_file() {
  local output=$1 extra=$2
  shift 2
  {
    if [[ -f "$extra" ]]; then
      cat "$extra"
      printf '\n'
    fi
    printf '%s\n' "$@"
  } | awk '
    {
      line = $0
      sub(/\r$/, "", line)
      if (line ~ /^[[:space:]]*(#|$)/) next
      sub(/^[[:space:]]*export[[:space:]]+/, "", line)
      eq = index(line, "=")
      if (eq == 0) next
      key = substr(line, 1, eq - 1)
      gsub(/[[:space:]]/, "", key)
      value = substr(line, eq + 1)
      if (value ~ /^".*"$/ || value ~ /^\047.*\047$/) {
        value = substr(value, 2, length(value) - 2)
      }
      if (!(key in seen)) {
        seen[key] = 1
        order[++count] = key
      }
      values[key] = value
    }
    END {
      for (i = 1; i <= count; i++) {
        value = values[order[i]]
        escaped = ""
        for (j = 1; j <= length(value); j++) {
          c = substr(value, j, 1)
          if (c == "\\" || c == "\"") escaped = escaped "\\"
          escaped = escaped c
        }
        printf "%s: \"%s\"\n", order[i], escaped
      }
    }' > "$output"
}

# service_url SERVICE — the deployed URL, or a placeholder on --dry-run.
service_url() {
  local service=$1 url=""
  url=$(gcloud run services describe "$service" --region="$REGION" \
    --project="$PROJECT_ID" --format="value(status.url)" 2> /dev/null) || url=""
  if [[ -z "$url" ]]; then
    if is_true "$DRY_RUN"; then
      printf 'https://%s.example.invalid' "$service"
      return 0
    fi
    return 1
  fi
  printf '%s' "$url"
}

project_number() {
  local number
  number=$(gcloud projects describe "$PROJECT_ID" --format="value(projectNumber)" 2> /dev/null) || number=""
  if [[ -z "$number" ]]; then
    is_true "$DRY_RUN" || die "Could not read the project number of $PROJECT_ID"
    number="PROJECT_NUMBER"
  fi
  printf '%s' "$number"
}

# =========================================================================
#  Deploy
# =========================================================================

wants() {
  local service=$1
  if [[ -z "$ONLY" ]]; then
    [[ "$service" == mcp || "$service" == client ]]
  else
    [[ "$service" == "$ONLY" ]]
  fi
}

# check_resource LABEL GCLOUD_ARGS... — appends to preflight's problems.
check_resource() {
  local label=$1 status=0
  shift
  exists "$@" || status=$?
  case "$status" in
    0) ;;
    1) problems+=("$label") ;;
    *) problems+=("$label — could not check: $EXISTS_ERROR") ;;
  esac
}

preflight() {
  local problems=()

  check_credentials
  check_project
  if is_true "$IAP_ENABLED" && { wants client || wants sign; }; then
    check_beta_component
  fi

  check_resource "Artifact Registry repository $AR_REPO in $REGION" \
    artifacts repositories describe "$AR_REPO" --location="$REGION" --project="$PROJECT_ID"
  check_resource "bucket gs://$GCS_BUCKET_NAME" \
    storage buckets describe "gs://$GCS_BUCKET_NAME" --project="$PROJECT_ID"

  local sa
  local accounts=()
  if wants mcp || wants client; then accounts+=("$MCP_SA" "$CLIENT_SA"); fi
  if wants sign; then accounts+=("$SIGN_SA"); fi
  for sa in "${accounts[@]}"; do
    check_resource "service account $sa" \
      iam service-accounts describe "$sa" --project="$PROJECT_ID"
  done

  if wants client && is_true "$SQL_ENABLED"; then
    check_resource "Secret Manager secret $SESSION_SECRET" \
      secrets describe "$SESSION_SECRET" --project="$PROJECT_ID"
  fi

  if [[ ${#problems[@]} -gt 0 ]]; then
    printf 'ERROR: Missing setup resources in project %s:\n' "$PROJECT_ID" >&2
    printf '  - %s\n' "${problems[@]}" >&2
    die "Run: ./deploy/deploy.sh setup --env $ENV_NAME"
  fi
}

deploy_mcp() {
  local image="$REGISTRY/$MCP_SERVICE" tag env_file
  tag=${TAG:-$(image_tag mcp_montage third_party/fonts)}

  if [[ -z "$TAG" ]]; then
    log "Syncing vendored fonts into mcp_montage/assets"
    (cd mcp_montage && run_quiet bash sync_fonts_from_third_party.sh)
    build_image mcp_montage "$image" "$tag" \
      --machine-type=e2-highcpu-8 --disk-size=200 --timeout=2400s
  fi

  env_file="$WORK_DIR/mcp.yaml"
  local pairs=(
    "GOOGLE_GENAI_USE_VERTEXAI=True"
    "GOOGLE_CLOUD_PROJECT=$PROJECT_ID"
    "GOOGLE_CLOUD_LOCATION=$GOOGLE_CLOUD_LOCATION"
    "GCS_BUCKET_NAME=$GCS_BUCKET_NAME"
    "LOG_TO_FILE=False"
  )
  if [[ -n "$VIEW_ENDPOINT" ]]; then
    pairs+=("VIEW_ENDPOINT=$VIEW_ENDPOINT")
  fi
  render_env_file "$env_file" "$CONFIG_DIR/$ENV_NAME.mcp.env" "${pairs[@]}"

  # --invoker-iam-check undoes a --no-invoker-iam-check set by hand, which
  # would make the service public.
  log "Deploying $MCP_SERVICE (private)"
  service_flags "$MCP_DEPLOY_FLAGS"
  run gcloud run deploy "$MCP_SERVICE" --image="$image:$tag" \
    --region="$REGION" --project="$PROJECT_ID" \
    --service-account="$MCP_SA" --no-allow-unauthenticated --invoker-iam-check \
    --env-vars-file "$env_file" --port=8080 \
    --cpu="$MCP_CPU" --memory="$MCP_MEMORY" --timeout="$MCP_TIMEOUT" \
    --session-affinity --no-cpu-throttling \
    ${SERVICE_FLAGS[@]+"${SERVICE_FLAGS[@]}"}

  bind gcloud run services add-iam-policy-binding "$MCP_SERVICE" \
    --region="$REGION" --project="$PROJECT_ID" \
    --member="serviceAccount:$CLIENT_SA" --role=roles/run.invoker
}

deploy_client() {
  local mcp_url=$1 image="$REGISTRY/$CLIENT_SERVICE" tag env_file
  tag=${TAG:-$(image_tag mcp_client)}

  if [[ -z "$TAG" ]]; then
    build_image mcp_client "$image" "$tag" \
      --machine-type=e2-highcpu-8 --disk-size=100 --timeout=1800s
  fi

  env_file="$WORK_DIR/client.yaml"
  local pairs=(
    "GOOGLE_GENAI_USE_VERTEXAI=True"
    "GOOGLE_CLOUD_PROJECT=$PROJECT_ID"
    "GOOGLE_CLOUD_LOCATION=$GOOGLE_CLOUD_LOCATION"
    "GCS_BUCKET_NAME=$GCS_BUCKET_NAME"
    "SERVER_CONNECTION_TYPE=http"
    "SERVER_URL=$mcp_url"
  )
  # Only the client needs this: it seeds the agent state that the prompts
  # pass to the mcp server as the select_asset assets_folder argument.
  if [[ -n "$GCS_INGREDIENT_IMAGES_FOLDER" ]]; then
    pairs+=("GCS_INGREDIENT_IMAGES_FOLDER=$GCS_INGREDIENT_IMAGES_FOLDER")
  fi
  render_env_file "$env_file" "$CONFIG_DIR/$ENV_NAME.client.env" "${pairs[@]}"

  # Turning SQL off must also detach the VPC and the secret, which a deploy
  # otherwise leaves as they were.
  local network=()
  if is_true "$SQL_ENABLED"; then
    network=(
      --network="$VPC_NETWORK" --subnet="$VPC_SUBNET" --vpc-egress=private-ranges-only
      --set-secrets="SESSION_SERVICE_URI=$SESSION_SECRET:latest"
    )
    log "Granting $CLIENT_SA access to the session secret $SESSION_SECRET"
    bind gcloud secrets add-iam-policy-binding "$SESSION_SECRET" \
      --project="$PROJECT_ID" \
      --member="serviceAccount:$CLIENT_SA" --role=roles/secretmanager.secretAccessor
  else
    network=(--clear-network --remove-secrets=SESSION_SERVICE_URI)
  fi

  service_flags "$CLIENT_DEPLOY_FLAGS"
  deploy_with_access "$CLIENT_SERVICE" --image="$image:$tag" \
    --region="$REGION" --project="$PROJECT_ID" \
    --service-account="$CLIENT_SA" \
    --env-vars-file "$env_file" --port=8080 \
    --memory="$CLIENT_MEMORY" --timeout="$CLIENT_TIMEOUT" \
    "${network[@]}" ${SERVICE_FLAGS[@]+"${SERVICE_FLAGS[@]}"}
}

# deploy_with_access SERVICE DEPLOY_FLAGS... — deploys a browser-facing
# service behind IAP (admitting IAP_MEMBERS) when IAP_ENABLED, else publicly.
# A public service is opened with an allUsers invoker binding, or, where
# domain restricted sharing forbids allUsers, by turning off the invoker
# check (PUBLIC_INVOKER_IAM_CHECK=0).
deploy_with_access() {
  local service=$1 access=()
  shift
  if is_true "$IAP_ENABLED"; then
    log "Deploying $service (IAP)"
    # --iap is only available in the beta track of gcloud run deploy.
    run gcloud beta run deploy "$service" "$@" --iap --no-allow-unauthenticated --invoker-iam-check
    apply_iap_bindings "$service"
    return 0
  fi

  if is_true "$PUBLIC_INVOKER_IAM_CHECK"; then
    access=(--allow-unauthenticated --invoker-iam-check)
  else
    access=(--no-invoker-iam-check)
  fi
  if service_has_iap "$service"; then
    log "Deploying $service (public; turning IAP off)"
    run gcloud beta run deploy "$service" "$@" "${access[@]}" --no-iap
  else
    log "Deploying $service (public)"
    run gcloud run deploy "$service" "$@" "${access[@]}"
  fi
}

# service_has_iap SERVICE — IAP is on for the deployed service.
service_has_iap() {
  local value
  value=$(gcloud run services describe "$1" --region="$REGION" --project="$PROJECT_ID" \
    --format="value(metadata.annotations['run.googleapis.com/iap-enabled'])" 2> /dev/null) || value=""
  [[ "$value" == true ]]
}

# apply_iap_bindings SERVICE — admits every member it can, then dies listing
# the ones an organization policy or a permission refused.
apply_iap_bindings() {
  local service=$1 number member members=() failed=() error=""
  log "Applying IAP access for $service"
  number=$(project_number)
  run_quiet gcloud beta services identity create --service=iap.googleapis.com --project="$PROJECT_ID"
  bind gcloud run services add-iam-policy-binding "$service" \
    --region="$REGION" --project="$PROJECT_ID" \
    --member="serviceAccount:service-$number@gcp-sa-iap.iam.gserviceaccount.com" \
    --role=roles/run.invoker
  apply_iap_oauth_client "$service"

  while IFS= read -r member; do members+=("$member"); done < <(members_list)
  for member in ${members[@]+"${members[@]}"}; do
    if ! grant_member "$service" "$member"; then
      failed+=("$member")
      error=$BIND_ERROR
    fi
  done
  if [[ ${#failed[@]} -gt 0 ]]; then
    members_failed "$service" "$error" "${failed[@]}"
  fi
}

# members_failed SERVICE GCLOUD_ERROR MEMBERS... — dies listing the members
# whose access to SERVICE could not be changed.
members_failed() {
  local service=$1 error=$2 member
  shift 2
  printf 'ERROR: the IAP access of these members to %s could not be changed:\n' "$service" >&2
  for member in "$@"; do
    printf '  - %s\n' "$member" >&2
  done
  explain_policy_rejection "$error"
  if [[ -z "$IAP_OAUTH_CLIENT_ID" ]]; then
    info "Members outside your organization also need a custom OAuth client (IAP_OAUTH_CLIENT_ID)."
  fi
  die "Every other member was applied. Fix IAP_MEMBERS, then run: ./deploy/deploy.sh setup --env $ENV_NAME --only iap"
}

# apply_iap_oauth_client SERVICE — Google's OAuth client admits only
# identities of the project's organization; a custom client, whose secret is
# read from Secret Manager, admits anyone IAP_MEMBERS names. The secret is
# passed in a private file, never on a command line.
apply_iap_oauth_client() {
  local service=$1 secret file
  [[ -n "$IAP_OAUTH_CLIENT_ID" ]] || return 0
  log "Using the custom OAuth client $IAP_OAUTH_CLIENT_ID for $service"
  ensure_work_dir
  file="$WORK_DIR/iap-oauth.$service.yaml"
  if ! is_true "$DRY_RUN"; then
    secret=$(gcloud secrets versions access latest --secret="$IAP_OAUTH_CLIENT_SECRET_NAME" \
      --project="$PROJECT_ID") ||
      die "Could not read the OAuth client secret from the Secret Manager secret $IAP_OAUTH_CLIENT_SECRET_NAME"
    (
      umask 077
      printf 'accessSettings:\n  oauthSettings:\n    clientId: %s\n    clientSecret: %s\n' \
        "$(yaml_quote "$IAP_OAUTH_CLIENT_ID")" "$(yaml_quote "$secret")" > "$file"
    )
  fi
  run_quiet gcloud beta iap settings set "$file" --project="$PROJECT_ID" \
    --resource-type=cloud-run --region="$REGION" --service="$service"
  rm -f "$file"
}

# grant_member SERVICE MEMBER — returns 1 when the binding cannot be made.
grant_member() {
  try_bind gcloud beta iap web add-iam-policy-binding --resource-type=cloud-run \
    --service="$1" --region="$REGION" --project="$PROJECT_ID" \
    --member="$2" --role=roles/iap.httpsResourceAccessor --condition=None
}

# revoke_member SERVICE MEMBER — removes every binding of MEMBER, conditional
# ones included; returns 1 when it cannot.
revoke_member() {
  try_bind gcloud beta iap web remove-iam-policy-binding --resource-type=cloud-run \
    --service="$1" --region="$REGION" --project="$PROJECT_ID" \
    --member="$2" --role=roles/iap.httpsResourceAccessor --all
}

deploy_sign() {
  local image="$REGISTRY/$SIGN_SERVICE" tag env_file
  tag=${TAG:-$(image_tag sign_server)}

  if [[ -z "$TAG" ]]; then
    build_image sign_server "$image" "$tag"
  fi

  env_file="$WORK_DIR/sign.yaml"
  render_env_file "$env_file" "$CONFIG_DIR/$ENV_NAME.sign.env" \
    "GOOGLE_CLOUD_PROJECT=$PROJECT_ID" \
    "GCS_BUCKET_NAME=$GCS_BUCKET_NAME"

  service_flags "$SIGN_DEPLOY_FLAGS"
  deploy_with_access "$SIGN_SERVICE" --image="$image:$tag" \
    --region="$REGION" --project="$PROJECT_ID" \
    --service-account="$SIGN_SA" \
    --env-vars-file "$env_file" --port=8080 --memory="$SIGN_MEMORY" \
    ${SERVICE_FLAGS[@]+"${SERVICE_FLAGS[@]}"}
}

# smoke_test MCP_URL — montage must reject anonymous calls and be reachable.
smoke_test() {
  local url="$1/version" code token
  log "Smoke test: $url"

  code=$(curl -sS -o /dev/null -w '%{http_code}' --max-time 30 "$url" 2> /dev/null) || true
  if [[ "$code" == 2* ]]; then
    die "$MCP_SERVICE answered HTTP $code without credentials: it is public. Remove access with: gcloud run services remove-iam-policy-binding $MCP_SERVICE --region=$REGION --project=$PROJECT_ID --member=allUsers --role=roles/run.invoker"
  fi
  info "Without credentials: HTTP $code (expected 401/403)"

  token=$(gcloud auth print-identity-token 2> /dev/null) || token=""
  if [[ -z "$token" ]]; then
    warn "Could not get an identity token to verify $MCP_SERVICE is reachable."
    return 0
  fi
  # A cold start loads the ~1.2 GB alignment model, so allow a long wait.
  code=$(curl -sS -o /dev/null -w '%{http_code}' --max-time 300 \
    -H "Authorization: Bearer $token" "$url" 2> /dev/null) || true
  if [[ "$code" == 200 ]]; then
    info "With your identity: HTTP 200"
  else
    warn "$MCP_SERVICE returned HTTP $code for your identity; check the revision logs (or your own run.invoker access)."
  fi
}

# check_browser_access SERVICE — behind IAP an anonymous browser must be sent
# to Google sign-in; a public service must let it in. gcloud only warns when
# an organization policy refuses the allUsers binding, so this is where a
# public service that nobody can open is caught.
check_browser_access() {
  local service=$1 url code
  if ! url=$(service_url "$service"); then
    warn "Could not read the URL of $service to check its access."
    return 0
  fi
  log "Access check: $url"
  code=$(curl -sS -o /dev/null -w '%{http_code}' --max-time 30 "$url/" 2> /dev/null) || true
  if is_true "$IAP_ENABLED"; then
    case "$code" in
      3*) info "Without sign-in: HTTP $code (redirected to Google sign-in)" ;;
      2*) die "$service answered HTTP $code without sign-in: IAP is not protecting it. Inspect it with: gcloud beta run services describe $service --region=$REGION --project=$PROJECT_ID" ;;
      *) warn "$service returned HTTP $code without sign-in; IAP can take a few minutes to start redirecting to Google sign-in after it is first enabled." ;;
    esac
  else
    case "$code" in
      401 | 403) die "$service refused an anonymous request (HTTP $code), so it is not public. If domain restricted sharing (constraints/iam.allowedPolicyMemberDomains) blocked the allUsers invoker binding, set PUBLIC_INVOKER_IAM_CHECK=\"0\" in $(config_file) and deploy again." ;;
      *) info "Without sign-in: HTTP $code" ;;
    esac
  fi
}

# check_beta_component — IAP, and creating the service agents CMEK needs, are
# only in the beta track of gcloud. Checked before anything changes, so a run
# never stops halfway for it.
check_beta_component() {
  gcloud beta run services list --help > /dev/null 2>&1 ||
    die "IAP and KMS_KEY need the gcloud beta component. Install it with: gcloud components install beta (or the google-cloud-cli beta package of your package manager)."
}

cmd_deploy() {
  load_config required
  case "$ONLY" in
    "" | mcp | client | sign) ;;
    *) die "--only must be one of: mcp, client, sign" ;;
  esac
  [[ -z "$TAG" || -n "$ONLY" ]] ||
    die "--tag needs --only, because each service has its own image version"
  if wants client; then
    validate_access_mode
  fi
  if wants sign && ! is_true "$SIGN_ENABLED"; then
    die "SIGN_ENABLED is not set for env '$ENV_NAME'. Enable it with: ./deploy/deploy.sh setup --env $ENV_NAME"
  fi
  if wants sign && is_true "$IAP_ENABLED"; then
    validate_iap_members
  fi
  [[ -n "$PROJECT_ID" && -n "$GCS_BUCKET_NAME" ]] ||
    die "PROJECT_ID and GCS_BUCKET_NAME must be set in $(config_file)"
  validate_settings
  validate_extra_envs

  log "Preflight for env '$ENV_NAME' (project $PROJECT_ID)"
  preflight

  confirm "Deploy ${ONLY:-mcp + client} to project $PROJECT_ID?"
  ensure_work_dir

  if wants sign; then
    deploy_sign
  fi

  local mcp_url=""
  if wants mcp; then
    deploy_mcp
  fi
  if wants mcp || wants client; then
    mcp_url=$(service_url "$MCP_SERVICE") ||
      die "$MCP_SERVICE is not deployed in $REGION. Deploy it first: ./deploy/deploy.sh --env $ENV_NAME --only mcp"
  fi
  if wants client; then
    deploy_client "$mcp_url"
  fi

  if is_true "$DRY_RUN"; then
    log "Dry run complete; nothing was changed."
    return 0
  fi
  if [[ -n "$mcp_url" ]]; then
    smoke_test "$mcp_url"
  fi
  if wants client; then
    check_browser_access "$CLIENT_SERVICE"
  fi
  if wants sign; then
    check_browser_access "$SIGN_SERVICE"
  fi

  log "Deployment complete"
  if [[ -n "$mcp_url" ]]; then info "mcp:    $mcp_url"; fi
  if wants client; then info "client: $(service_url "$CLIENT_SERVICE" || echo '?')"; fi
  if wants sign; then info "sign:   $(service_url "$SIGN_SERVICE" || echo '?')"; fi
}

# =========================================================================
#  Setup
# =========================================================================

# ask VAR PROMPT — prompt with the current value as default when interactive.
ask() {
  local var=$1 prompt=$2 current reply
  current=${!var-}
  if is_interactive; then
    read -r -p "$prompt [$current]: " reply || true
    if [[ -n "$reply" ]]; then
      current=$reply
    fi
  fi
  printf -v "$var" '%s' "$current"
}

# ask_bool VAR PROMPT — stores 1 or 0.
ask_bool() {
  local var=$1 prompt=$2 current reply
  current=$(is_true "${!var-}" && echo 1 || echo 0)
  if is_interactive; then
    read -r -p "$prompt [$(is_true "$current" && echo Y/n || echo y/N)]: " reply || true
    case "$reply" in
      y | Y | yes | YES) current=1 ;;
      n | N | no | NO) current=0 ;;
    esac
  fi
  printf -v "$var" '%s' "$current"
}

require() {
  local var=$1 hint=$2
  [[ -n "${!var-}" ]] || die "$var is required ($hint). Set it in $(config_file) or the environment, or run setup interactively."
}

# The steps setup can run, and which `--only` may select.
SETUP_STEPS="project kms registry identities bucket sql sign iap"

wants_step() {
  [[ -z "$ONLY" || "$ONLY" == "$1" ]]
}

validate_setup_step() {
  [[ -n "$ONLY" ]] || return 0
  local step
  for step in $SETUP_STEPS; do
    [[ "$ONLY" == "$step" ]] && return 0
  done
  die "--only for setup must be one of: ${SETUP_STEPS// /, }"
}

collect_answers() {
  if [[ -z "$PROJECT_ID" ]] && is_interactive && wants_step project; then
    PROJECT_ID=$(gcloud config get-value project 2> /dev/null || true)
  fi

  if wants_step project; then
    log "Project"
    ask PROJECT_ID "Google Cloud project ID"
    ask REGION "Region for Cloud Run, registry, bucket and SQL"
    ask GOOGLE_CLOUD_LOCATION "Vertex AI location for Gemini/Veo calls"
    ask MCP_SERVICE "Cloud Run service name for the MCP server"
    ask CLIENT_SERVICE "Cloud Run service name for the web client"
  fi
  require PROJECT_ID "Google Cloud project ID"
  if wants_step registry; then
    ask AR_REPO "Artifact Registry repository"
  fi
  if wants_step bucket; then
    : "${GCS_BUCKET_NAME:=$PROJECT_ID-montage}"
    ask GCS_BUCKET_NAME "GCS bucket for generated media (created if missing)"
    : "${GCS_INGREDIENT_IMAGES_FOLDER:=gs://$GCS_BUCKET_NAME/ingredient-images}"
    ask GCS_INGREDIENT_IMAGES_FOLDER "GCS folder of ingredient/character images"
  fi

  if wants_step sql; then
    log "Cloud SQL — persistent ADK sessions on a private IP (optional, ~10-15 min, billed continuously)"
    ask_bool SQL_ENABLED "Set up Cloud SQL?"
  fi
  if wants_step sql && is_true "$SQL_ENABLED"; then
    ask SQL_INSTANCE "Instance name"
    ask SQL_TIER "Machine tier"
    ask SQL_DATABASE "Database name"
    ask SQL_USER "Database user"
    ask VPC_NETWORK "VPC network (created if missing)"
    ask VPC_SUBNET "Subnet for Cloud Run egress (created if missing)"
    ask VPC_SUBNET_RANGE "Subnet IP range"
  fi

  if wants_step sign; then
    log "Sign server — short-lived signed links to private media (optional)"
    ask_bool SIGN_ENABLED "Deploy the sign server?"
    if is_true "$SIGN_ENABLED"; then
      ask SIGN_SERVICE "Cloud Run service name for the sign server"
    fi
  fi

  if wants_step iap; then
    log "Web access"
    if [[ -n "$ONLY" ]]; then
      is_true "$IAP_ENABLED" ||
        die "IAP_ENABLED is not set for env '$ENV_NAME'. Run setup without --only to change how the web client is protected."
    else
      ask_bool IAP_ENABLED "Protect the web client with Google sign-in (IAP)? Requires an organization"
    fi
    if is_true "$IAP_ENABLED"; then
      CLIENT_PUBLIC=0
      ask IAP_MEMBERS "Allowed members, comma-separated (user:a@x.com, group:g@x.com, domain:x.com)"
      require IAP_MEMBERS "e.g. group:team@example.com"
      validate_iap_members
    else
      info "Without IAP, anyone with the URL can use the app and spend Vertex AI quota."
      ask_bool CLIENT_PUBLIC "Deploy the web client publicly?"
      is_true "$CLIENT_PUBLIC" ||
        die "The web client needs IAP or public access. Re-run setup and choose one."
    fi
  fi

  derive_names
}

required_apis() {
  local apis=(
    run.googleapis.com cloudbuild.googleapis.com artifactregistry.googleapis.com
    aiplatform.googleapis.com iam.googleapis.com storage.googleapis.com
    compute.googleapis.com
  )
  if is_true "$SQL_ENABLED"; then
    apis+=(sqladmin.googleapis.com servicenetworking.googleapis.com secretmanager.googleapis.com)
  fi
  if is_true "$SIGN_ENABLED"; then
    apis+=(iamcredentials.googleapis.com)
  fi
  if is_true "$IAP_ENABLED"; then
    apis+=(iap.googleapis.com)
  fi
  printf '%s\n' "${apis[@]}"
}

# IAP with Google's OAuth client needs an organization; a custom OAuth client
# works without one.
iap_needs_organization() {
  is_true "$IAP_ENABLED" && [[ -z "$IAP_OAUTH_CLIENT_ID" ]]
}

# setup_project — the project itself was checked by check_project; IAP also
# needs it in an organization.
setup_project() {
  log "Project $PROJECT_ID"
  if iap_needs_organization; then
    gcloud projects get-ancestors "$PROJECT_ID" --format="value(type)" 2> /dev/null | grep -qx organization ||
      die "IAP with Google's OAuth client needs the project in an organization, and $PROJECT_ID is not in one. Set IAP_OAUTH_CLIENT_ID to use a custom OAuth client, or re-run setup without IAP."
  fi
}

# enable_apis — enables only the APIs that are off, so a deployer without
# serviceusage.services.enable can run setup once an admin has enabled them.
enable_apis() {
  local api enabled missing=()
  if enabled=$(gcloud services list --enabled --project="$PROJECT_ID" \
    --format="value(config.name)" 2> /dev/null); then
    enabled=$(printf '%s\n' "$enabled" | tr -d '\r')
    while IFS= read -r api; do
      printf '%s\n' "$enabled" | grep -qx "$api" || missing+=("$api")
    done < <(required_apis)
  else
    while IFS= read -r api; do missing+=("$api"); done < <(required_apis)
  fi
  if [[ ${#missing[@]} -eq 0 ]]; then
    info "Required APIs are enabled"
    return 0
  fi
  run gcloud services enable "${missing[@]}" --project="$PROJECT_ID"
}

# setup_kms — lets each Google service that stores Montage data encrypt with
# KMS_KEY. Runs before anything is created with the key.
setup_kms() {
  log "Customer-managed encryption key $KMS_KEY"
  local number agent agents=(gcp-sa-artifactregistry gs-project-accounts serverless-robot-prod)
  number=$(project_number)
  run_quiet gcloud beta services identity create --service=artifactregistry.googleapis.com --project="$PROJECT_ID"
  run_quiet gcloud storage service-agent --project="$PROJECT_ID"
  if is_true "$SQL_ENABLED"; then
    run_quiet gcloud beta services identity create --service=sqladmin.googleapis.com --project="$PROJECT_ID"
    run_quiet gcloud beta services identity create --service=secretmanager.googleapis.com --project="$PROJECT_ID"
    agents+=(gcp-sa-cloud-sql gcp-sa-secretmanager)
  fi
  for agent in "${agents[@]}"; do
    bind gcloud kms keys add-iam-policy-binding "$KMS_KEY" \
      --member="serviceAccount:service-$number@$agent.iam.gserviceaccount.com" \
      --role=roles/cloudkms.cryptoKeyEncrypterDecrypter
  done
}

setup_registry() {
  log "Artifact Registry"
  if found artifacts repositories describe "$AR_REPO" --location="$REGION" --project="$PROJECT_ID"; then
    info "Repository $AR_REPO exists"
  else
    run gcloud artifacts repositories create "$AR_REPO" --repository-format=docker \
      --location="$REGION" --project="$PROJECT_ID" ${KMS_KEY:+"--kms-key=$KMS_KEY"}
  fi

  # New projects run Cloud Build as the Compute Engine default service
  # account, which may lack permission to push images and write logs.
  # Organizations that delete that account set BUILD_SERVICE_ACCOUNT.
  local builder number
  if [[ -n "$BUILD_SERVICE_ACCOUNT" ]]; then
    builder=$BUILD_SERVICE_ACCOUNT
  else
    number=$(project_number)
    builder="$number-compute@developer.gserviceaccount.com"
  fi
  bind gcloud projects add-iam-policy-binding "$PROJECT_ID" \
    --member="serviceAccount:$builder" \
    --role=roles/cloudbuild.builds.builder --condition=None
}

# ensure_service_account EMAIL DISPLAY_NAME — creates EMAIL when it belongs to
# the project. One of another project (or one an organization policy stops
# setup from creating) must already exist.
ensure_service_account() {
  local email=$1 display=$2
  if found iam service-accounts describe "$email" --project="$PROJECT_ID"; then
    info "Service account $email exists"
    return 0
  fi
  [[ "$email" == *"@$PROJECT_ID.iam.gserviceaccount.com" ]] ||
    die "Service account $email does not exist, and setup only creates service accounts in $PROJECT_ID. Create it first."
  run gcloud iam service-accounts create "${email%%@*}" --display-name="$display" --project="$PROJECT_ID"
  if ! is_true "$DRY_RUN"; then
    sleep "$SA_PROPAGATION_SECONDS"
  fi
}

setup_identities() {
  log "Service accounts"
  ensure_service_account "$MCP_SA" "Project Montage MCP server"
  ensure_service_account "$CLIENT_SA" "Project Montage web client"
  if is_true "$SIGN_ENABLED"; then
    ensure_service_account "$SIGN_SA" "Project Montage sign server"
  fi

  local sa
  for sa in "$MCP_SA" "$CLIENT_SA"; do
    bind gcloud projects add-iam-policy-binding "$PROJECT_ID" \
      --member="serviceAccount:$sa" --role=roles/aiplatform.user --condition=None
  done
}

setup_bucket() {
  log "Bucket gs://$GCS_BUCKET_NAME"
  if found storage buckets describe "gs://$GCS_BUCKET_NAME" --project="$PROJECT_ID"; then
    info "Bucket exists"
  else
    run gcloud storage buckets create "gs://$GCS_BUCKET_NAME" --project="$PROJECT_ID" \
      --location="$REGION" --uniform-bucket-level-access \
      ${KMS_KEY:+"--default-encryption-key=$KMS_KEY"}
  fi

  local sa
  for sa in "$MCP_SA" "$CLIENT_SA"; do
    bind gcloud storage buckets add-iam-policy-binding "gs://$GCS_BUCKET_NAME" \
      --member="serviceAccount:$sa" --role=roles/storage.objectAdmin
  done

  if [[ -n "$GCS_INGREDIENT_IMAGES_FOLDER" ]]; then
    ensure_gcs_folder "$GCS_INGREDIENT_IMAGES_FOLDER"
    grant_ingredient_images_access
  fi
}

# bucket_from_gcs_uri GCS_URI — the bucket name from a gs://bucket/path URI.
bucket_from_gcs_uri() {
  local rest=${1#gs://}
  printf '%s' "${rest%%/*}"
}

# grant_ingredient_images_access — GCS_INGREDIENT_IMAGES_FOLDER may live in a
# bucket other than GCS_BUCKET_NAME, which the loop above already covers. When
# it does, the MCP server still needs to read it to serve select_asset.
grant_ingredient_images_access() {
  local bucket
  bucket=$(bucket_from_gcs_uri "$GCS_INGREDIENT_IMAGES_FOLDER")
  [[ "$bucket" != "$GCS_BUCKET_NAME" ]] || return 0
  log "Granting $MCP_SA read access to gs://$bucket (GCS_INGREDIENT_IMAGES_FOLDER)"
  bind gcloud storage buckets add-iam-policy-binding "gs://$bucket" \
    --member="serviceAccount:$MCP_SA" --role=roles/storage.objectViewer
}

# ensure_gcs_folder GCS_URI — GCS has no real folders, so a prefix with no
# objects under it does not exist as far as list_blobs is concerned. This
# uploads a zero-byte marker (skipped by list_gcs_images, which only returns
# image extensions) so the folder shows up and is ready to receive assets.
# A no-op if the prefix already holds objects.
ensure_gcs_folder() {
  local uri=${1%/} listing
  listing=$(gcloud storage ls "$uri/" --project="$PROJECT_ID" 2>/dev/null) || listing=""
  if [[ -n "$listing" ]]; then
    info "Folder $uri already has objects"
    return 0
  fi
  log "Creating folder $uri"
  run gcloud storage cp /dev/null "$uri/.keep"
}

setup_network() {
  log "VPC $VPC_NETWORK (private IP for Cloud SQL)"
  if is_shared_vpc; then
    info "A Shared VPC network: its host project owns the subnet and private services access."
    return 0
  fi
  if ! found compute networks describe "$VPC_NETWORK" --project="$PROJECT_ID"; then
    run gcloud compute networks create "$VPC_NETWORK" --subnet-mode=custom --project="$PROJECT_ID"
  fi
  if ! found compute networks subnets describe "$VPC_SUBNET" --region="$REGION" --project="$PROJECT_ID"; then
    run gcloud compute networks subnets create "$VPC_SUBNET" --network="$VPC_NETWORK" \
      --region="$REGION" --range="$VPC_SUBNET_RANGE" --project="$PROJECT_ID"
  fi
  if ! found compute addresses describe "$PSA_RANGE" --global --project="$PROJECT_ID"; then
    run gcloud compute addresses create "$PSA_RANGE" --global --purpose=VPC_PEERING \
      --prefix-length=16 --network="$VPC_NETWORK" --project="$PROJECT_ID"
  fi
  local peerings
  peerings=$(gcloud services vpc-peerings list --network="$VPC_NETWORK" \
    --project="$PROJECT_ID" --format="value(peering)" 2> /dev/null) || peerings=""
  if [[ -z "$peerings" ]]; then
    run gcloud services vpc-peerings connect --service=servicenetworking.googleapis.com \
      --ranges="$PSA_RANGE" --network="$VPC_NETWORK" --project="$PROJECT_ID"
  else
    info "Private services access is connected"
  fi
}

generate_password() {
  head -c 64 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | cut -c 1-32
}

# secret_replication_flags — sets REPLICATION_FLAGS for a new secret.
# Automatic replication counts as the location "global", which a resource
# location policy may forbid; SECRET_LOCATIONS keeps the secret in the
# listed regions instead. With KMS_KEY it lives only in the key's region.
REPLICATION_FLAGS=()
secret_replication_flags() {
  local file
  if [[ -n "$KMS_KEY" ]]; then
    ensure_work_dir
    file="$WORK_DIR/replication.json"
    printf '{"userManaged": {"replicas": [{"location": "%s", "customerManagedEncryption": {"kmsKeyName": "%s"}}]}}\n' \
      "$(kms_key_location)" "$KMS_KEY" > "$file"
    REPLICATION_FLAGS=("--replication-policy-file=$file")
  elif [[ -n "$SECRET_LOCATIONS" ]]; then
    REPLICATION_FLAGS=(--replication-policy=user-managed "--locations=$SECRET_LOCATIONS")
  else
    REPLICATION_FLAGS=(--replication-policy=automatic)
  fi
}

setup_sql() {
  log "Cloud SQL $SQL_INSTANCE"
  if found sql instances describe "$SQL_INSTANCE" --project="$PROJECT_ID"; then
    info "Instance exists"
  else
    info "Creating the instance takes 10-15 minutes."
    run gcloud sql instances create "$SQL_INSTANCE" --database-version=POSTGRES_16 \
      --edition=enterprise --tier="$SQL_TIER" --region="$REGION" \
      --network="$(vpc_network_path)" --no-assign-ip \
      --project="$PROJECT_ID" ${KMS_KEY:+"--disk-encryption-key=$KMS_KEY"}
  fi
  if ! found sql databases describe "$SQL_DATABASE" --instance="$SQL_INSTANCE" --project="$PROJECT_ID"; then
    run gcloud sql databases create "$SQL_DATABASE" --instance="$SQL_INSTANCE" --project="$PROJECT_ID"
  fi

  if found secrets describe "$SESSION_SECRET" --project="$PROJECT_ID"; then
    info "Secret $SESSION_SECRET exists; keeping the current database password"
  else
    local password ip users uri
    password=$(generate_password)
    users=$(gcloud sql users list --instance="$SQL_INSTANCE" --project="$PROJECT_ID" \
      --format="value(name)" 2> /dev/null) || users=""
    if printf '%s\n' "$users" | grep -qx "$SQL_USER"; then
      run_masked "$password" gcloud sql users set-password "$SQL_USER" \
        --instance="$SQL_INSTANCE" --password="$password" --project="$PROJECT_ID"
    else
      run_masked "$password" gcloud sql users create "$SQL_USER" \
        --instance="$SQL_INSTANCE" --password="$password" --project="$PROJECT_ID"
    fi
    ip=$(gcloud sql instances describe "$SQL_INSTANCE" --project="$PROJECT_ID" \
      --format="value(ipAddresses[0].ipAddress)" 2> /dev/null) || ip=""
    if [[ -z "$ip" ]]; then
      is_true "$DRY_RUN" || die "Could not read the private IP of $SQL_INSTANCE"
      ip="PRIVATE_IP"
    fi
    uri="postgresql+asyncpg://$SQL_USER:$password@$ip:5432/$SQL_DATABASE"
    secret_replication_flags
    printf '%s' "$uri" | run gcloud secrets create "$SESSION_SECRET" --data-file=- \
      "${REPLICATION_FLAGS[@]}" --project="$PROJECT_ID"
  fi

  bind gcloud secrets add-iam-policy-binding "$SESSION_SECRET" \
    --member="serviceAccount:$CLIENT_SA" --role=roles/secretmanager.secretAccessor \
    --project="$PROJECT_ID"
}

setup_sign() {
  log "Sign server $SIGN_SERVICE"
  # Signing through the IAM API needs token-creator rights on itself.
  bind gcloud iam service-accounts add-iam-policy-binding "$SIGN_SA" \
    --member="serviceAccount:$SIGN_SA" --role=roles/iam.serviceAccountTokenCreator \
    --project="$PROJECT_ID"
  bind gcloud storage buckets add-iam-policy-binding "gs://$GCS_BUCKET_NAME" \
    --member="serviceAccount:$SIGN_SA" --role=roles/storage.objectViewer

  if [[ -n "$VIEW_ENDPOINT" ]] &&
    found run services describe "$SIGN_SERVICE" --region="$REGION" --project="$PROJECT_ID"; then
    info "Sign server is deployed; update it with: ./deploy/deploy.sh --env $ENV_NAME --only sign"
    return 0
  fi

  ensure_work_dir
  deploy_sign
  local url
  url=$(service_url "$SIGN_SERVICE") || die "Could not read the URL of $SIGN_SERVICE"
  VIEW_ENDPOINT="$url/view?uri="
}

setup_iap() {
  log "Identity-Aware Proxy"
  validate_iap_members
  run_quiet gcloud beta services identity create --service=iap.googleapis.com --project="$PROJECT_ID"
  local service
  while IFS= read -r service; do
    sync_iap_members "$service"
  done < <(iap_services)
}

# sync_iap_members SERVICE — makes the service's IAP allowlist match
# IAP_MEMBERS: members added to the config are granted, members dropped from
# it are revoked. This is how people lose access, so any step that fails
# fails the run instead of leaving them in silently.
sync_iap_members() {
  local service=$1 member policy live=() configured=() failed=() error=""

  if ! found run services describe "$service" --region="$REGION" --project="$PROJECT_ID"; then
    info "$service is not deployed yet; its members are applied on the next deploy."
    return 0
  fi
  apply_iap_oauth_client "$service"

  policy=$(iap_policy_members "$service") ||
    die "Could not read the IAP policy of $service, so no member was added or removed."
  while IFS= read -r member; do configured+=("$member"); done < <(members_list)
  while IFS= read -r member; do
    [[ -n "$member" ]] && live+=("$member")
  done <<< "$policy"

  for member in ${configured[@]+"${configured[@]}"}; do
    if contains_member "$member" ${live[@]+"${live[@]}"}; then
      info "$service already admits $member"
    elif ! grant_member "$service" "$member"; then
      failed+=("$member")
      error=$BIND_ERROR
    fi
  done
  for member in ${live[@]+"${live[@]}"}; do
    if ! contains_member "$member" ${configured[@]+"${configured[@]}"} &&
      ! revoke_member "$service" "$member"; then
      failed+=("$member")
      error=$BIND_ERROR
    fi
  done
  if [[ ${#failed[@]} -gt 0 ]]; then
    members_failed "$service" "$error" "${failed[@]}"
  fi
}

# iap_policy_members SERVICE — one member per line from the live IAP policy;
# returns 1 when the policy cannot be read.
iap_policy_members() {
  local live
  live=$(gcloud beta iap web get-iam-policy --resource-type=cloud-run \
    --service="$1" --region="$REGION" --project="$PROJECT_ID" \
    --flatten="bindings[].members" \
    --filter="bindings.role:roles/iap.httpsResourceAccessor" \
    --format="value(bindings.members)") || return 1
  # gcloud on Windows ends lines with CRLF; a stray CR would make every
  # member look different from the configured one.
  printf '%s\n' "$live" | tr ';,' '\n' | tr -d '\r'
}

cmd_setup() {
  load_config optional
  validate_setup_step

  command -v gcloud > /dev/null || die "gcloud CLI not found. Install: https://cloud.google.com/sdk/docs/install"
  local account
  account=$(gcloud auth list --filter=status:ACTIVE --format="value(account)" 2> /dev/null | head -n 1) || account=""
  if [[ -z "$account" ]] && is_interactive && ! is_true "$DRY_RUN"; then
    # The one gcloud command that must be allowed to interact, so it keeps
    # the terminal to itself instead of going through run.
    printf '+ gcloud auth login\n' >&2
    CLOUDSDK_CORE_DISABLE_PROMPTS=0 gcloud auth login
  fi
  check_credentials

  collect_answers
  check_project
  validate_settings
  if { is_true "$IAP_ENABLED" && { wants_step iap || wants_step sign; }; } ||
    { [[ -n "$KMS_KEY" ]] && wants_step kms; }; then
    check_beta_component
  fi
  write_config
  confirm "Create or verify ${ONLY:-these resources} in project $PROJECT_ID?"

  # APIs are checked for every step, because each one needs its own; on a
  # fresh project this enables all of them.
  if wants_step project; then
    setup_project
  fi
  enable_apis
  if wants_step kms && [[ -n "$KMS_KEY" ]]; then
    setup_kms
  fi
  if wants_step registry; then
    setup_registry
  fi
  if wants_step identities; then
    setup_identities
  fi
  if wants_step bucket; then
    setup_bucket
  fi
  if wants_step sql && is_true "$SQL_ENABLED"; then
    setup_network
    setup_sql
  fi
  if wants_step sign && is_true "$SIGN_ENABLED"; then
    setup_sign
    write_config
  fi
  if wants_step iap && is_true "$IAP_ENABLED"; then
    setup_iap
  fi

  log "Setup complete for env '$ENV_NAME'${ONLY:+ (--only $ONLY)}"
  if [[ -n "$ONLY" ]]; then
    # One step was asked for, so deploying the whole stack is not implied.
    info "Deploy when you are ready: ./deploy/deploy.sh --env $ENV_NAME"
    return 0
  fi

  info "Deploy with: ./deploy/deploy.sh --env $ENV_NAME"
  if offers_first_deploy; then
    local deploy_now=1
    ask_bool deploy_now "Run the first deploy now?"
    if is_true "$deploy_now"; then
      ASSUME_YES=1
      cmd_deploy
    fi
  fi
}

# Only a full setup offers to deploy, and only when someone is there to answer.
offers_first_deploy() {
  [[ -z "$ONLY" ]] && is_interactive && ! is_true "$DRY_RUN"
}

# =========================================================================
#  Access
# =========================================================================

# iap_services — the browser-facing services IAP protects.
iap_services() {
  printf '%s\n' "$CLIENT_SERVICE"
  if is_true "$SIGN_ENABLED"; then
    printf '%s\n' "$SIGN_SERVICE"
  fi
}

# contains_member MEMBER MEMBERS... — compared as member_key does.
contains_member() {
  local needle item
  needle=$(member_key "$1")
  shift
  for item in "$@"; do
    [[ "$(member_key "$item")" == "$needle" ]] && return 0
  done
  return 1
}

# =========================================================================
#  Entry point
# =========================================================================

usage() {
  cat << 'EOF'
Usage:
  ./deploy/deploy.sh setup --env NAME [--only STEP] [--dry-run] [-y]
  ./deploy/deploy.sh [deploy] --env NAME [--only mcp|client|sign] [--tag TAG] [--dry-run] [-y]

Commands:
  setup    Ask for settings, then create or verify every cloud resource.
           Safe to re-run. Saves answers to deploy/config/NAME.env.
           --only runs one step: project, kms, registry, identities, bucket,
           sql, sign or iap. `--only iap` is how you edit the IAP allowlist: it
           asks for IAP_MEMBERS (or reads the config with -y) and makes each
           service's access match it, adding and revoking as needed.
  deploy   Build on Cloud Build and deploy mcp, then client (default).

Options:
  -e, --env NAME    Environment config to use (required).
  --only NAME       deploy: one service (mcp, client, sign).
                    setup: one step (see above).
  --tag TAG         Deploy an existing image tag without building (needs --only).
  --dry-run         Print the commands that change resources; run none of them.
  -y, --yes         Do not prompt; use config values and skip confirmation.
  -h, --help        Show this help.
EOF
}

# need_value FLAG [VALUE] — dies when FLAG was given without a value.
need_value() {
  [[ $# -ge 2 && -n "$2" && "$2" != -* ]] || die "$1 needs a value"
}

# The command may come before or after the flags.
parse_args() {
  local command_given=0
  while [[ $# -gt 0 ]]; do
    case "$1" in
      -e | --env) need_value "$@" && ENV_NAME=$2 && shift ;;
      --env=*) ENV_NAME=${1#*=} ;;
      --only) need_value "$@" && ONLY=$2 && shift ;;
      --only=*) ONLY=${1#*=} ;;
      --tag) need_value "$@" && TAG=$2 && shift ;;
      --tag=*) TAG=${1#*=} ;;
      --dry-run) DRY_RUN=1 ;;
      -y | --yes) ASSUME_YES=1 ;;
      -h | --help)
        usage
        exit 0
        ;;
      -*)
        usage >&2
        die "Unknown argument: $1"
        ;;
      *)
        if [[ $command_given -eq 1 ]]; then
          usage >&2
          die "Unknown argument: $1"
        fi
        COMMAND=$1
        command_given=1
        ;;
    esac
    shift
  done
}

main() {
  parse_args "$@"
  # Output of most gcloud checks is hidden, so a gcloud question (enable an
  # API? reauthenticate?) would be invisible and hang the terminal. With
  # prompts disabled gcloud fails with an error instead.
  export CLOUDSDK_CORE_DISABLE_PROMPTS=1
  cd "$ROOT"
  trap cleanup EXIT
  ensure_work_dir
  case "$COMMAND" in
    setup)
      [[ -z "$TAG" ]] || die "--tag applies to deploy, not setup"
      cmd_setup
      ;;
    deploy) cmd_deploy ;;
    help) usage ;;
    *)
      usage >&2
      die "Unknown command: $COMMAND"
      ;;
  esac
}

# Sourcing the script defines its functions without running anything, which
# is how deploy/tests/run.sh checks individual predicates.
if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  main "$@"
fi
