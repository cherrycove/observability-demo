#!/usr/bin/env bash
set -Eeuo pipefail

# One-click workshop installer for a self-hosted obs-agent on an existing EKS node.
# Run this script from an administrator workstation or AWS CloudShell.
# The only interactive inputs are Agent ID and Agent API Key, entered inside an
# encrypted SSM interactive session so the API key is not sent via Run Command.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
REGION="${AWS_REGION:-${AWS_DEFAULT_REGION:-ap-northeast-1}}"
CLUSTER_NAME="${EKS_CLUSTER_NAME:-${CLUSTER_NAME:-}}"
TARGET_INSTANCE_ID="${TARGET_INSTANCE_ID:-}"
KUBECTL_VERSION="${KUBECTL_VERSION:-}"
BEAK_ENDPOINT="${BEAK_ENDPOINT:-https://agent-api.truewatch.com}"
TOKEN_DURATION="${TOKEN_DURATION:-8h}"
STATE_FILE="${STATE_FILE:-${REPO_ROOT}/.obs-agent-eks-node-demo.state}"
SSM_POLICY_ARN="arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"

usage() {
  cat <<'EOF'
Usage:
  bash install-obs-agent-eks-node-demo.sh
  bash install-obs-agent-eks-node-demo.sh --cleanup

Optional environment overrides:
  AWS_REGION          Default: AWS_DEFAULT_REGION or ap-northeast-1
  EKS_CLUSTER_NAME    Required; CLUSTER_NAME is also accepted
  TARGET_INSTANCE_ID  Default: automatically select the first running EKS node
  KUBECTL_VERSION     Default: latest patch matching the EKS minor version
  BEAK_ENDPOINT       Default: https://agent-api.truewatch.com
  TOKEN_DURATION      Default: 8h
  STATE_FILE          Default: <repository>/.obs-agent-eks-node-demo.state
EOF
}

log() {
  printf '\n==> %s\n' "$*"
}

die() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || die "Required command not found: $1"
}

json_commands() {
  python3 -c 'import json, sys; print(json.dumps({"commands": [sys.stdin.read()]}))'
}

wait_for_ssm() {
  local instance_id="$1"
  local attempt status

  for attempt in $(seq 1 36); do
    status="$(aws ssm describe-instance-information \
      --region "$REGION" \
      --filters "Key=InstanceIds,Values=$instance_id" \
      --query 'InstanceInformationList[0].PingStatus' \
      --output text 2>/dev/null || true)"

    if [[ "$status" == "Online" ]]; then
      return 0
    fi

    printf 'Waiting for SSM registration (%s/36)...\n' "$attempt"
    sleep 10
  done

  return 1
}

show_command_result() {
  local command_id="$1"
  local instance_id="$2"

  aws ssm get-command-invocation \
    --region "$REGION" \
    --command-id "$command_id" \
    --instance-id "$instance_id" \
    --query '{Status:Status,StandardOutput:StandardOutputContent,StandardError:StandardErrorContent}' \
    --output yaml || true
}

send_setup_command() {
  local instance_id="$1"
  local command_text="$2"
  local comment="$3"
  local parameters command_id

  parameters="$(printf '%s' "$command_text" | json_commands)"
  command_id="$(aws ssm send-command \
    --region "$REGION" \
    --instance-ids "$instance_id" \
    --document-name AWS-RunShellScript \
    --comment "$comment" \
    --timeout-seconds 900 \
    --parameters "$parameters" \
    --query 'Command.CommandId' \
    --output text)"

  if ! aws ssm wait command-executed \
    --region "$REGION" \
    --command-id "$command_id" \
    --instance-id "$instance_id"; then
    show_command_result "$command_id" "$instance_id"
    return 1
  fi

  show_command_result "$command_id" "$instance_id"
}

load_state_value() {
  local key="$1"
  sed -n "s/^${key}=//p" "$STATE_FILE" | tail -n 1
}

cleanup_demo() {
  require_command aws
  require_command kubectl
  require_command python3

  [[ -f "$STATE_FILE" ]] || die "State file not found: $STATE_FILE"

  REGION="$(load_state_value REGION)"
  CLUSTER_NAME="$(load_state_value CLUSTER_NAME)"
  TARGET_INSTANCE_ID="$(load_state_value TARGET_INSTANCE_ID)"
  NODE_ROLE_NAME="$(load_state_value NODE_ROLE_NAME)"
  SSM_POLICY_ADDED="$(load_state_value SSM_POLICY_ADDED)"
  NAMESPACE_CREATED="$(load_state_value NAMESPACE_CREATED)"

  [[ -n "$REGION" && -n "$CLUSTER_NAME" ]] || die "State file is incomplete"

  local temp_dir admin_kubeconfig cleanup_command
  temp_dir="$(mktemp -d)"
  admin_kubeconfig="$temp_dir/admin-kubeconfig"
  trap 'rm -rf "$temp_dir"' EXIT

  log "Removing temporary Kubernetes RBAC"
  aws eks update-kubeconfig \
    --region "$REGION" \
    --name "$CLUSTER_NAME" \
    --kubeconfig "$admin_kubeconfig" >/dev/null

  KUBECONFIG="$admin_kubeconfig" kubectl delete \
    clusterrolebinding obs-agent-demo-view \
    --ignore-not-found
  KUBECONFIG="$admin_kubeconfig" kubectl delete \
    clusterrolebinding obs-agent-demo-cluster-read \
    --ignore-not-found
  KUBECONFIG="$admin_kubeconfig" kubectl delete \
    clusterrole obs-agent-demo-cluster-read \
    --ignore-not-found
  KUBECONFIG="$admin_kubeconfig" kubectl -n obs-agent delete \
    serviceaccount obs-agent \
    --ignore-not-found

  if [[ "$NAMESPACE_CREATED" == "1" ]]; then
    KUBECONFIG="$admin_kubeconfig" kubectl delete namespace obs-agent --ignore-not-found
  fi

  if [[ -n "$TARGET_INSTANCE_ID" ]] && wait_for_ssm "$TARGET_INSTANCE_ID"; then
    log "Stopping obs-agent and removing the temporary kubeconfig"
    cleanup_command=$(cat <<'EOF'
set -eu
marker=/etc/obs-agent/workshop-install.marker
if [ ! -f "$marker" ]; then
  printf 'Workshop marker not found; refusing to remove an unmanaged Agent installation.\n'
  exit 0
fi

unit_path="$(systemctl show obs-agent.service -p FragmentPath --value 2>/dev/null || true)"
systemctl disable --now obs-agent.service 2>/dev/null || true

# The Agent API Key and Owl credentials are stored below these directories.
rm -rf /etc/obs-agent /var/lib/obs-agent
rm -f /usr/local/sbin/install-obs-agent-demo
rm -f /usr/local/bin/obs-agent /usr/local/bin/owl /usr/local/bin/obs-skill-dep
rm -f /usr/local/bin/kubectl
if [ -n "$unit_path" ]; then
  rm -f "$unit_path"
fi
systemctl daemon-reload 2>/dev/null || true
userdel obs-agent 2>/dev/null || true
groupdel obs-agent 2>/dev/null || true
EOF
)
    send_setup_command "$TARGET_INSTANCE_ID" "$cleanup_command" "Clean up obs-agent workshop demo" || \
      die "Node-local cleanup failed; state file was preserved for retry"
  else
    die "Node is not online in SSM; state file was preserved. Start or terminate the node, then retry cleanup."
  fi

  if [[ "$SSM_POLICY_ADDED" == "1" && -n "$NODE_ROLE_NAME" ]]; then
    log "Detaching the temporary SSM policy from $NODE_ROLE_NAME"
    aws iam detach-role-policy \
      --role-name "$NODE_ROLE_NAME" \
      --policy-arn "$SSM_POLICY_ARN"
  fi

  rm -f "$STATE_FILE"
  log "Cleanup completed"
}

install_demo() {
  require_command aws
  require_command kubectl
  require_command python3
  require_command base64
  require_command curl
  require_command session-manager-plugin

  [[ -n "$CLUSTER_NAME" ]] || \
    die "EKS_CLUSTER_NAME is required (CLUSTER_NAME is also accepted)"
  [[ ! -e "$STATE_FILE" ]] || \
    die "State file already exists. Run '$0 --cleanup' before installing again: $STATE_FILE"

  local temp_dir admin_kubeconfig
  temp_dir="$(mktemp -d)"
  admin_kubeconfig="$temp_dir/admin-kubeconfig"
  trap 'rm -rf "$temp_dir"' EXIT

  log "Checking AWS identity"
  aws sts get-caller-identity --output table

  log "Creating an isolated administrator kubeconfig for $CLUSTER_NAME"
  aws eks update-kubeconfig \
    --region "$REGION" \
    --name "$CLUSTER_NAME" \
    --kubeconfig "$admin_kubeconfig" >/dev/null
  KUBECONFIG="$admin_kubeconfig" kubectl cluster-info >/dev/null

  if [[ -z "$KUBECTL_VERSION" ]]; then
    local cluster_version
    cluster_version="$(aws eks describe-cluster \
      --region "$REGION" \
      --name "$CLUSTER_NAME" \
      --query 'cluster.version' \
      --output text)"
    [[ "$cluster_version" =~ ^[0-9]+\.[0-9]+$ ]] || \
      die "Could not determine the EKS Kubernetes version"
    KUBECTL_VERSION="$(curl -fsSL \
      "https://dl.k8s.io/release/stable-${cluster_version}.txt")"
  fi
  [[ "$KUBECTL_VERSION" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]] || \
    die "Invalid KUBECTL_VERSION: $KUBECTL_VERSION"
  log "Using kubectl $KUBECTL_VERSION"

  local namespace_created
  namespace_created=0
  if ! KUBECONFIG="$admin_kubeconfig" kubectl get namespace obs-agent >/dev/null 2>&1; then
    namespace_created=1
  fi

  for resource in \
    "serviceaccount/obs-agent --namespace obs-agent" \
    "clusterrolebinding/obs-agent-demo-view" \
    "clusterrolebinding/obs-agent-demo-cluster-read" \
    "clusterrole/obs-agent-demo-cluster-read"; do
    # shellcheck disable=SC2086
    if KUBECONFIG="$admin_kubeconfig" kubectl get $resource >/dev/null 2>&1; then
      die "Existing Kubernetes resource conflicts with this Workshop: $resource"
    fi
  done

  if [[ -z "$TARGET_INSTANCE_ID" ]]; then
    log "Automatically selecting a running EKS node"
    TARGET_INSTANCE_ID="$(aws ec2 describe-instances \
      --region "$REGION" \
      --filters \
        "Name=tag:eks:cluster-name,Values=$CLUSTER_NAME" \
        "Name=instance-state-name,Values=running" \
      --query 'Reservations[].Instances[].InstanceId' \
      --output text | awk '{print $1}')"

    if [[ -z "$TARGET_INSTANCE_ID" || "$TARGET_INSTANCE_ID" == "None" ]]; then
      TARGET_INSTANCE_ID="$(aws ec2 describe-instances \
        --region "$REGION" \
        --filters \
          "Name=tag:kubernetes.io/cluster/$CLUSTER_NAME,Values=owned,shared" \
          "Name=instance-state-name,Values=running" \
        --query 'Reservations[].Instances[].InstanceId' \
        --output text | awk '{print $1}')"
    fi
  fi

  [[ -n "$TARGET_INSTANCE_ID" && "$TARGET_INSTANCE_ID" != "None" ]] || \
    die "No running EC2 node found for EKS cluster $CLUSTER_NAME"

  log "Selected node: $TARGET_INSTANCE_ID"

  local profile_arn profile_name node_role_name ssm_policy_count ssm_policy_added
  profile_arn="$(aws ec2 describe-instances \
    --region "$REGION" \
    --instance-ids "$TARGET_INSTANCE_ID" \
    --query 'Reservations[0].Instances[0].IamInstanceProfile.Arn' \
    --output text)"
  [[ -n "$profile_arn" && "$profile_arn" != "None" ]] || \
    die "The selected node does not have an IAM instance profile"

  profile_name="${profile_arn##*/}"
  node_role_name="$(aws iam get-instance-profile \
    --instance-profile-name "$profile_name" \
    --query 'InstanceProfile.Roles[0].RoleName' \
    --output text)"

  ssm_policy_count="$(aws iam list-attached-role-policies \
    --role-name "$node_role_name" \
    --query "length(AttachedPolicies[?PolicyArn=='$SSM_POLICY_ARN'])" \
    --output text)"
  ssm_policy_added=0

  if [[ "$ssm_policy_count" == "0" ]]; then
    log "Temporarily attaching SSM access to node role $node_role_name"
    aws iam attach-role-policy \
      --role-name "$node_role_name" \
      --policy-arn "$SSM_POLICY_ARN"
    ssm_policy_added=1
  else
    log "SSM policy is already attached to $node_role_name"
  fi

  # Save enough state immediately so a later failure can still be cleaned up.
  umask 077
  cat >"$STATE_FILE" <<EOF
REGION=$REGION
CLUSTER_NAME=$CLUSTER_NAME
TARGET_INSTANCE_ID=$TARGET_INSTANCE_ID
NODE_ROLE_NAME=$node_role_name
SSM_POLICY_ADDED=$ssm_policy_added
NAMESPACE_CREATED=$namespace_created
EOF

  log "Waiting for the node to become available in SSM"
  wait_for_ssm "$TARGET_INSTANCE_ID" || \
    die "The node did not register with SSM. Check SSM Agent, VPC egress/endpoints, and the node role."

  log "Creating the temporary read-only Kubernetes identity"
  KUBECONFIG="$admin_kubeconfig" kubectl create namespace obs-agent \
    --dry-run=client -o yaml | \
    KUBECONFIG="$admin_kubeconfig" kubectl apply -f -

  KUBECONFIG="$admin_kubeconfig" kubectl -n obs-agent create serviceaccount obs-agent \
    --dry-run=client -o yaml | \
    KUBECONFIG="$admin_kubeconfig" kubectl apply -f -

  KUBECONFIG="$admin_kubeconfig" kubectl create clusterrolebinding obs-agent-demo-view \
    --clusterrole=view \
    --serviceaccount=obs-agent:obs-agent \
    --dry-run=client -o yaml | \
    KUBECONFIG="$admin_kubeconfig" kubectl apply -f -

  KUBECONFIG="$admin_kubeconfig" kubectl apply -f - <<'EOF'
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRole
metadata:
  name: obs-agent-demo-cluster-read
rules:
- apiGroups: [""]
  resources: ["nodes", "namespaces", "persistentvolumes"]
  verbs: ["get", "list", "watch"]
- apiGroups: ["storage.k8s.io"]
  resources: ["storageclasses", "csidrivers", "csinodes", "volumeattachments"]
  verbs: ["get", "list", "watch"]
- apiGroups: ["apiextensions.k8s.io"]
  resources: ["customresourcedefinitions"]
  verbs: ["get", "list", "watch"]
- apiGroups: ["metrics.k8s.io"]
  resources: ["nodes", "pods"]
  verbs: ["get", "list", "watch"]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRoleBinding
metadata:
  name: obs-agent-demo-cluster-read
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: ClusterRole
  name: obs-agent-demo-cluster-read
subjects:
- kind: ServiceAccount
  name: obs-agent
  namespace: obs-agent
EOF

  KUBECONFIG="$admin_kubeconfig" kubectl auth can-i \
    --as=system:serviceaccount:obs-agent:obs-agent \
    get pods --all-namespaces | grep -qx yes || die "ServiceAccount cannot read pods"
  KUBECONFIG="$admin_kubeconfig" kubectl auth can-i \
    --as=system:serviceaccount:obs-agent:obs-agent \
    get nodes | grep -qx yes || die "ServiceAccount cannot read nodes"

  log "Generating a $TOKEN_DURATION read-only kubeconfig"
  local token server ca_data kubeconfig_file kubeconfig_b64
  token="$(KUBECONFIG="$admin_kubeconfig" kubectl -n obs-agent create token obs-agent \
    --duration="$TOKEN_DURATION")"
  server="$(aws eks describe-cluster \
    --region "$REGION" \
    --name "$CLUSTER_NAME" \
    --query 'cluster.endpoint' \
    --output text)"
  ca_data="$(aws eks describe-cluster \
    --region "$REGION" \
    --name "$CLUSTER_NAME" \
    --query 'cluster.certificateAuthority.data' \
    --output text)"
  kubeconfig_file="$temp_dir/agent-kubeconfig"

  umask 077
  printf '%s\n' \
    'apiVersion: v1' \
    'kind: Config' \
    'clusters:' \
    "- name: $CLUSTER_NAME" \
    '  cluster:' \
    "    server: $server" \
    "    certificate-authority-data: $ca_data" \
    'contexts:' \
    '- name: obs-agent-demo' \
    '  context:' \
    "    cluster: $CLUSTER_NAME" \
    '    user: obs-agent' \
    'current-context: obs-agent-demo' \
    'users:' \
    '- name: obs-agent' \
    '  user:' \
    "    token: $token" >"$kubeconfig_file"
  unset token server ca_data

  kubeconfig_b64="$(base64 <"$kubeconfig_file" | tr -d '\n')"

  local interactive_installer interactive_installer_b64 remote_setup
  IFS= read -r -d '' interactive_installer <<'EOF' || true
#!/usr/bin/env bash
set -Eeuo pipefail

if [[ "${EUID:-$(id -u)}" -ne 0 ]]; then
  exec sudo "$0" "$@"
fi

printf '\nobs-agent workshop installer\n'
printf 'Only Agent ID and Agent API Key are required.\n\n'
read -r -p 'Agent ID: ' AGENT_ID
read -r -s -p 'Agent API Key: ' AGENT_API_KEY
printf '\n'

if [[ -z "$AGENT_ID" || -z "$AGENT_API_KEY" ]]; then
  printf 'Agent ID and Agent API Key must not be empty.\n' >&2
  exit 1
fi

cleanup_secret() {
  unset AGENT_ID AGENT_API_KEY
}
trap cleanup_secret EXIT

curl -fsSLo /tmp/self-host-install.sh \
  https://static.truewatch.com/obs-agent/self-host-install.sh
bash -n /tmp/self-host-install.sh

env \
  AGENT_ID="$AGENT_ID" \
  AGENT_API_KEY="$AGENT_API_KEY" \
  BEAK_ENDPOINT="__BEAK_ENDPOINT__" \
  PERMISSION_MODE="standard" \
  bash /tmp/self-host-install.sh

unset AGENT_ID AGENT_API_KEY

[[ -f /etc/obs-agent/agent.env ]] || {
  printf '/etc/obs-agent/agent.env was not created by the official installer.\n' >&2
  exit 1
}

sed -i \
  -e '/^KUBECONFIG=/d' \
  -e '/^PATH=/d' \
  /etc/obs-agent/agent.env

cat >>/etc/obs-agent/agent.env <<'ENVEOF'
KUBECONFIG="/etc/obs-agent/kubeconfig"
PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
ENVEOF

chown root:obs-agent /etc/obs-agent/agent.env /etc/obs-agent/kubeconfig
chmod 0640 /etc/obs-agent/agent.env /etc/obs-agent/kubeconfig

systemctl daemon-reload
systemctl restart obs-agent

printf '\nVerifying EKS read access as the obs-agent service user...\n'
runuser -u obs-agent -- env \
  KUBECONFIG=/etc/obs-agent/kubeconfig \
  PATH=/usr/local/bin:/usr/bin:/bin \
  kubectl get nodes
runuser -u obs-agent -- env \
  KUBECONFIG=/etc/obs-agent/kubeconfig \
  PATH=/usr/local/bin:/usr/bin:/bin \
  kubectl get pods -A

printf '\nobs-agent service status:\n'
systemctl --no-pager --full status obs-agent || true
printf '\nInstallation completed. The Kubernetes token is temporary.\n'
EOF
  interactive_installer="${interactive_installer//__BEAK_ENDPOINT__/$BEAK_ENDPOINT}"
  interactive_installer_b64="$(printf '%s' "$interactive_installer" | base64 | tr -d '\n')"

  IFS= read -r -d '' remote_setup <<'EOF' || true
set -Eeuo pipefail

for required in systemctl runuser sha256sum; do
  command -v "$required" >/dev/null 2>&1 || {
    printf 'Required node command not found: %s\n' "$required" >&2
    exit 1
  }
done

if [[ -e /etc/obs-agent ]] \
  || [[ -e /var/lib/obs-agent ]] \
  || [[ -e /usr/local/bin/obs-agent ]] \
  || [[ -e /usr/local/bin/owl ]] \
  || [[ -e /usr/local/bin/obs-skill-dep ]] \
  || [[ -e /usr/local/bin/kubectl ]] \
  || systemctl list-unit-files obs-agent.service --no-legend 2>/dev/null | grep -q '^obs-agent\.service'; then
  printf 'An obs-agent, Owl, or workshop kubectl installation already exists on this node; refusing to overwrite it.\n' >&2
  exit 1
fi

if command -v dnf >/dev/null 2>&1; then
  dnf install -y curl ca-certificates
elif command -v yum >/dev/null 2>&1; then
  yum install -y curl ca-certificates
elif command -v apt-get >/dev/null 2>&1; then
  apt-get update
  DEBIAN_FRONTEND=noninteractive apt-get install -y curl ca-certificates
elif ! command -v curl >/dev/null 2>&1; then
  printf 'curl is required and no supported package manager was found.\n' >&2
  exit 1
fi

case "$(uname -m)" in
  x86_64) kubectl_arch=amd64 ;;
  aarch64|arm64) kubectl_arch=arm64 ;;
  *) printf 'Unsupported architecture: %s\n' "$(uname -m)" >&2; exit 1 ;;
esac

curl -fsSLo /tmp/kubectl \
  "https://dl.k8s.io/release/__KUBECTL_VERSION__/bin/linux/$kubectl_arch/kubectl"
curl -fsSLo /tmp/kubectl.sha256 \
  "https://dl.k8s.io/release/__KUBECTL_VERSION__/bin/linux/$kubectl_arch/kubectl.sha256"
printf '%s  %s\n' "$(cat /tmp/kubectl.sha256)" /tmp/kubectl | sha256sum --check -
install -o root -g root -m 0755 /tmp/kubectl /usr/local/bin/kubectl

install -d -o root -g root -m 0755 /etc/obs-agent
printf '%s\n' 'observability-demo-workshop' \
  >/etc/obs-agent/workshop-install.marker
chmod 0600 /etc/obs-agent/workshop-install.marker
printf '%s' '__KUBECONFIG_B64__' | base64 -d >/etc/obs-agent/kubeconfig
chown root:root /etc/obs-agent/kubeconfig
chmod 0600 /etc/obs-agent/kubeconfig

printf '%s' '__INTERACTIVE_INSTALLER_B64__' | base64 -d \
  >/usr/local/sbin/install-obs-agent-demo
chown root:root /usr/local/sbin/install-obs-agent-demo
chmod 0700 /usr/local/sbin/install-obs-agent-demo

/usr/local/bin/kubectl --kubeconfig=/etc/obs-agent/kubeconfig get nodes
printf 'Node preparation completed.\n'
EOF
  remote_setup="${remote_setup//__KUBECTL_VERSION__/$KUBECTL_VERSION}"
  remote_setup="${remote_setup//__KUBECONFIG_B64__/$kubeconfig_b64}"
  remote_setup="${remote_setup//__INTERACTIVE_INSTALLER_B64__/$interactive_installer_b64}"
  unset kubeconfig_b64 interactive_installer interactive_installer_b64

  log "Installing kubectl and the secure interactive Agent installer on the node"
  send_setup_command \
    "$TARGET_INSTANCE_ID" \
    "$remote_setup" \
    "Prepare obs-agent workshop demo"
  unset remote_setup

  log "Opening the encrypted interactive installer"
  printf 'Enter the Agent ID and Agent API Key when prompted.\n'
  aws ssm start-session \
    --region "$REGION" \
    --target "$TARGET_INSTANCE_ID" \
    --document-name AWS-StartInteractiveCommand \
    --parameters '{"command":["sudo /usr/local/sbin/install-obs-agent-demo"]}'

  log "Installation session finished"
  printf 'State file: %s\n' "$STATE_FILE"
  printf 'When the workshop is over, run:\n  bash %q --cleanup\n' "$0"
}

case "${1:-}" in
  "") install_demo ;;
  --cleanup) cleanup_demo ;;
  -h|--help) usage ;;
  *) usage >&2; exit 2 ;;
esac
