#!/usr/bin/env bash
set -Eeuo pipefail

# One-click workshop installer for a self-hosted obs-agent on an existing EKS node.
# Run this script from an administrator workstation or AWS CloudShell. A short-lived
# privileged helper Pod enters the selected node through its host filesystem; this
# avoids modifying the EC2 node IAM role or requiring AWS Systems Manager.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
REGION="${AWS_REGION:-${AWS_DEFAULT_REGION:-ap-northeast-1}}"
CLUSTER_NAME="${EKS_CLUSTER_NAME:-${CLUSTER_NAME:-}}"
TARGET_INSTANCE_ID="${TARGET_INSTANCE_ID:-}"
TARGET_NODE_NAME="${TARGET_NODE_NAME:-}"
KUBECTL_VERSION="${KUBECTL_VERSION:-}"
BEAK_ENDPOINT="${BEAK_ENDPOINT:-https://agent-api.truewatch.com}"
TOKEN_DURATION="${TOKEN_DURATION:-8h}"
STATE_FILE="${STATE_FILE:-${REPO_ROOT}/.obs-agent-eks-node-demo.state}"
HELPER_IMAGE="${HELPER_IMAGE:-public.ecr.aws/docker/library/busybox:1.36}"
HELPER_NAMESPACE="obs-agent"
HELPER_POD_NAME="obs-agent-node-helper"
TEMP_DIR=""
ADMIN_KUBECONFIG=""
HELPER_POD_CREATED=0

cleanup_local_resources() {
  if [[ "$HELPER_POD_CREATED" == "1" \
    && -n "${ADMIN_KUBECONFIG:-}" \
    && -f "$ADMIN_KUBECONFIG" ]]; then
    KUBECONFIG="$ADMIN_KUBECONFIG" kubectl -n "$HELPER_NAMESPACE" delete pod \
      "$HELPER_POD_NAME" --ignore-not-found --wait=false >/dev/null 2>&1 || true
  fi

  if [[ -n "${TEMP_DIR:-}" ]]; then
    rm -rf -- "$TEMP_DIR"
  fi
}

usage() {
  cat <<'EOF'
Usage:
  bash install-obs-agent-eks-node-demo.sh
  bash install-obs-agent-eks-node-demo.sh --cleanup

Optional environment overrides:
  AWS_REGION          Default: AWS_DEFAULT_REGION or ap-northeast-1
  EKS_CLUSTER_NAME    Required; CLUSTER_NAME is also accepted
  TARGET_NODE_NAME    Default: automatically select the first Ready Linux worker
  TARGET_INSTANCE_ID  Optional EC2 instance ID used to select a Kubernetes node
  KUBECTL_VERSION     Default: latest patch matching the EKS minor version
  BEAK_ENDPOINT       Default: https://agent-api.truewatch.com
  TOKEN_DURATION      Default: 8h
  HELPER_IMAGE        Default: public.ecr.aws/docker/library/busybox:1.36
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

select_target_node() {
  local provider_id os_image

  if [[ -n "$TARGET_NODE_NAME" ]]; then
    KUBECONFIG="$ADMIN_KUBECONFIG" kubectl get node "$TARGET_NODE_NAME" >/dev/null
  elif [[ -n "$TARGET_INSTANCE_ID" ]]; then
    TARGET_NODE_NAME="$(KUBECONFIG="$ADMIN_KUBECONFIG" kubectl get nodes -o json | \
      python3 -c 'import json, sys
instance_id = sys.argv[1]
for item in json.load(sys.stdin)["items"]:
    if item.get("spec", {}).get("providerID", "").endswith("/" + instance_id):
        print(item["metadata"]["name"])
        break' "$TARGET_INSTANCE_ID")"
  else
    TARGET_NODE_NAME="$(KUBECONFIG="$ADMIN_KUBECONFIG" kubectl get nodes -o json | \
      python3 -c 'import json, sys
for item in json.load(sys.stdin)["items"]:
    spec = item.get("spec", {})
    labels = item.get("metadata", {}).get("labels", {})
    ready = any(c.get("type") == "Ready" and c.get("status") == "True" for c in item.get("status", {}).get("conditions", []))
    control_plane = "node-role.kubernetes.io/control-plane" in labels or "node-role.kubernetes.io/master" in labels
    if ready and not spec.get("unschedulable", False) and not control_plane:
        print(item["metadata"]["name"])
        break')"
  fi

  [[ -n "$TARGET_NODE_NAME" ]] || die "No matching Ready EKS worker node was found"
  provider_id="$(KUBECONFIG="$ADMIN_KUBECONFIG" kubectl get node "$TARGET_NODE_NAME" \
    -o jsonpath='{.spec.providerID}')"
  os_image="$(KUBECONFIG="$ADMIN_KUBECONFIG" kubectl get node "$TARGET_NODE_NAME" \
    -o jsonpath='{.status.nodeInfo.osImage}')"
  [[ "$os_image" != *Bottlerocket* ]] || die "Bottlerocket nodes are not supported"
  [[ "$(KUBECONFIG="$ADMIN_KUBECONFIG" kubectl get node "$TARGET_NODE_NAME" \
    -o jsonpath='{.status.nodeInfo.operatingSystem}')" == "linux" ]] || \
    die "Only Linux worker nodes are supported"

  if [[ -z "$TARGET_INSTANCE_ID" && "$provider_id" == */* ]]; then
    TARGET_INSTANCE_ID="${provider_id##*/}"
  fi
}

create_helper_pod() {
  KUBECONFIG="$ADMIN_KUBECONFIG" kubectl -n "$HELPER_NAMESPACE" delete pod \
    "$HELPER_POD_NAME" --ignore-not-found --wait=true >/dev/null

  KUBECONFIG="$ADMIN_KUBECONFIG" kubectl apply -f - <<EOF
apiVersion: v1
kind: Pod
metadata:
  name: ${HELPER_POD_NAME}
  namespace: ${HELPER_NAMESPACE}
  labels:
    app.kubernetes.io/name: obs-agent-node-helper
    app.kubernetes.io/part-of: observability-demo-workshop
spec:
  nodeName: ${TARGET_NODE_NAME}
  hostPID: true
  hostNetwork: true
  automountServiceAccountToken: false
  restartPolicy: Never
  tolerations:
    - operator: Exists
  containers:
    - name: helper
      image: ${HELPER_IMAGE}
      imagePullPolicy: IfNotPresent
      command: ["/bin/sh", "-c", "sleep 3600"]
      securityContext:
        privileged: true
        allowPrivilegeEscalation: true
        runAsUser: 0
      volumeMounts:
        - name: host-root
          mountPath: /host
  volumes:
    - name: host-root
      hostPath:
        path: /
        type: Directory
EOF
  HELPER_POD_CREATED=1

  KUBECONFIG="$ADMIN_KUBECONFIG" kubectl -n "$HELPER_NAMESPACE" wait \
    --for=condition=Ready "pod/$HELPER_POD_NAME" --timeout=2m
}

delete_helper_pod() {
  KUBECONFIG="$ADMIN_KUBECONFIG" kubectl -n "$HELPER_NAMESPACE" delete pod \
    "$HELPER_POD_NAME" --ignore-not-found --wait=true >/dev/null
  HELPER_POD_CREATED=0
}

run_on_node() {
  local command_text="$1"

  printf '%s\n' "$command_text" | \
    KUBECONFIG="$ADMIN_KUBECONFIG" kubectl -n "$HELPER_NAMESPACE" exec -i \
      "$HELPER_POD_NAME" -- chroot /host /bin/bash -s
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
  TARGET_NODE_NAME="$(load_state_value TARGET_NODE_NAME)"
  NAMESPACE_CREATED="$(load_state_value NAMESPACE_CREATED)"

  [[ -n "$REGION" && -n "$CLUSTER_NAME" ]] || die "State file is incomplete"

  local cleanup_command
  TEMP_DIR="$(mktemp -d)"
  ADMIN_KUBECONFIG="$TEMP_DIR/admin-kubeconfig"
  trap cleanup_local_resources EXIT

  log "Creating an isolated administrator kubeconfig for $CLUSTER_NAME"
  aws eks update-kubeconfig \
    --region "$REGION" \
    --name "$CLUSTER_NAME" \
    --kubeconfig "$ADMIN_KUBECONFIG" >/dev/null

  select_target_node
  log "Selected node: $TARGET_NODE_NAME"

  KUBECONFIG="$ADMIN_KUBECONFIG" kubectl create namespace "$HELPER_NAMESPACE" \
    --dry-run=client -o yaml | \
    KUBECONFIG="$ADMIN_KUBECONFIG" kubectl apply -f - >/dev/null

  log "Opening a temporary privileged helper Pod for node cleanup"
  create_helper_pod
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
  run_on_node "$cleanup_command" || \
    die "Node-local cleanup failed; state file was preserved for retry"
  delete_helper_pod

  log "Removing temporary Kubernetes RBAC"
  KUBECONFIG="$ADMIN_KUBECONFIG" kubectl delete \
    clusterrolebinding obs-agent-demo-view \
    --ignore-not-found
  KUBECONFIG="$ADMIN_KUBECONFIG" kubectl delete \
    clusterrolebinding obs-agent-demo-cluster-read \
    --ignore-not-found
  KUBECONFIG="$ADMIN_KUBECONFIG" kubectl delete \
    clusterrole obs-agent-demo-cluster-read \
    --ignore-not-found
  KUBECONFIG="$ADMIN_KUBECONFIG" kubectl -n "$HELPER_NAMESPACE" delete \
    serviceaccount obs-agent \
    --ignore-not-found

  if [[ "$NAMESPACE_CREATED" == "1" ]]; then
    KUBECONFIG="$ADMIN_KUBECONFIG" kubectl delete namespace "$HELPER_NAMESPACE" \
      --ignore-not-found
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

  [[ -n "$CLUSTER_NAME" ]] || \
    die "EKS_CLUSTER_NAME is required (CLUSTER_NAME is also accepted)"
  [[ ! -e "$STATE_FILE" ]] || \
    die "State file already exists. Run '$0 --cleanup' before installing again: $STATE_FILE"

  TEMP_DIR="$(mktemp -d)"
  ADMIN_KUBECONFIG="$TEMP_DIR/admin-kubeconfig"
  trap cleanup_local_resources EXIT

  log "Checking AWS identity"
  aws sts get-caller-identity --output table

  log "Creating an isolated administrator kubeconfig for $CLUSTER_NAME"
  aws eks update-kubeconfig \
    --region "$REGION" \
    --name "$CLUSTER_NAME" \
    --kubeconfig "$ADMIN_KUBECONFIG" >/dev/null
  KUBECONFIG="$ADMIN_KUBECONFIG" kubectl cluster-info >/dev/null

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
  if ! KUBECONFIG="$ADMIN_KUBECONFIG" kubectl get namespace "$HELPER_NAMESPACE" >/dev/null 2>&1; then
    namespace_created=1
  fi

  for resource in \
    "serviceaccount/obs-agent --namespace obs-agent" \
    "clusterrolebinding/obs-agent-demo-view" \
    "clusterrolebinding/obs-agent-demo-cluster-read" \
    "clusterrole/obs-agent-demo-cluster-read"; do
    # shellcheck disable=SC2086
    if KUBECONFIG="$ADMIN_KUBECONFIG" kubectl get $resource >/dev/null 2>&1; then
      die "Existing Kubernetes resource conflicts with this Workshop: $resource"
    fi
  done

  if [[ -z "$TARGET_NODE_NAME" && -z "$TARGET_INSTANCE_ID" ]]; then
    log "Automatically selecting a Ready EKS worker node"
  fi
  select_target_node
  log "Selected node: $TARGET_NODE_NAME (${TARGET_INSTANCE_ID:-unknown instance})"

  # Save enough state immediately so a later failure can still be cleaned up.
  umask 077
  cat >"$STATE_FILE" <<EOF
REGION=$REGION
CLUSTER_NAME=$CLUSTER_NAME
TARGET_INSTANCE_ID=$TARGET_INSTANCE_ID
TARGET_NODE_NAME=$TARGET_NODE_NAME
NAMESPACE_CREATED=$namespace_created
EOF

  log "Creating the temporary read-only Kubernetes identity"
  KUBECONFIG="$ADMIN_KUBECONFIG" kubectl create namespace "$HELPER_NAMESPACE" \
    --dry-run=client -o yaml | \
    KUBECONFIG="$ADMIN_KUBECONFIG" kubectl apply -f -

  KUBECONFIG="$ADMIN_KUBECONFIG" kubectl -n "$HELPER_NAMESPACE" create serviceaccount obs-agent \
    --dry-run=client -o yaml | \
    KUBECONFIG="$ADMIN_KUBECONFIG" kubectl apply -f -

  KUBECONFIG="$ADMIN_KUBECONFIG" kubectl create clusterrolebinding obs-agent-demo-view \
    --clusterrole=view \
    --serviceaccount=obs-agent:obs-agent \
    --dry-run=client -o yaml | \
    KUBECONFIG="$ADMIN_KUBECONFIG" kubectl apply -f -

  KUBECONFIG="$ADMIN_KUBECONFIG" kubectl apply -f - <<'EOF'
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

  KUBECONFIG="$ADMIN_KUBECONFIG" kubectl auth can-i \
    --as=system:serviceaccount:obs-agent:obs-agent \
    get pods --all-namespaces | grep -qx yes || die "ServiceAccount cannot read pods"
  KUBECONFIG="$ADMIN_KUBECONFIG" kubectl auth can-i \
    --as=system:serviceaccount:obs-agent:obs-agent \
    get nodes | grep -qx yes || die "ServiceAccount cannot read nodes"

  log "Generating a $TOKEN_DURATION read-only kubeconfig"
  local token server ca_data kubeconfig_file kubeconfig_b64
  token="$(KUBECONFIG="$ADMIN_KUBECONFIG" kubectl -n obs-agent create token obs-agent \
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
  kubeconfig_file="$TEMP_DIR/agent-kubeconfig"

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

  log "Opening a temporary privileged helper Pod on $TARGET_NODE_NAME"
  create_helper_pod

  log "Installing kubectl and the secure interactive Agent installer on the node"
  run_on_node "$remote_setup"
  unset remote_setup

  log "Opening the encrypted Kubernetes exec installer"
  printf 'Enter the Agent ID and Agent API Key when prompted.\n'
  KUBECONFIG="$ADMIN_KUBECONFIG" kubectl -n "$HELPER_NAMESPACE" exec -it \
    "$HELPER_POD_NAME" -- chroot /host /usr/local/sbin/install-obs-agent-demo
  delete_helper_pod

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
