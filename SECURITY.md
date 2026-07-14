# Security Policy

## Supported versions

Only the latest release tag is supported. This repository is an intentionally faultable demo and is not a production reference architecture.

## Reporting a vulnerability

Please use GitHub private vulnerability reporting for the repository. Do not open a public issue containing credentials, exploitable endpoints or sensitive environment details.

## Deployment safety

- The EKS workshop overlay creates a public Gateway LoadBalancer. Use it only on a disposable demo cluster, keep the control token private and delete the LoadBalancer after the workshop.
- Keep the default Gateway ClusterIP for non-workshop deployments unless the demo is placed behind appropriate access control.
- Generate unique MySQL and control tokens; store them only in Compose environment files or Kubernetes Secrets.
- Never commit a DataWay URL, Public DataWay client token, workspace identifier, private key or access token.
- Rotate any credential immediately if it appears in logs, screenshots, Git history or a published image.
- MySQL and Redis are ephemeral, single-instance demo dependencies and must not store real data.
