# Deployment Guide

This guide explains how services are built, tested and promoted through the environments. It applies
to every service in the `shop` GitHub organisation.

## Environments

There are three environments. `dev` is rebuilt from `main` on every merge and has no uptime guarantee.
`staging` mirrors production data with masked customer fields and is refreshed every Monday morning.
`production` serves customers and changes only through the release pipeline. Direct `kubectl apply`
against production is blocked by the admission controller.

## Pipeline stages

Every merge to `main` triggers the pipeline in GitHub Actions:

1. **build** compiles the service and runs unit tests; the job fails if coverage drops below 70 percent.
2. **scan** runs Trivy against the container image; any critical CVE without an exception ticket blocks
   the release.
3. **deploy-dev** applies the Helm chart to `dev`.
4. **integration** runs the contract tests against `dev`.
5. **promote-staging** deploys to staging and waits for a manual approval from a release manager.
6. **canary** sends five percent of production traffic to the new version for thirty minutes and
   compares error rate and p95 latency with the stable version.
7. **rollout** completes the production deployment when the canary passes.

The whole pipeline takes about forty-five minutes when nobody has to approve manually.

## Canary rules

The canary is aborted automatically when the error rate of the new version exceeds the stable
version by more than 0.5 percentage points, or when p95 latency is more than 20 percent higher. An
aborted canary rolls back on its own and opens an incident of severity SEV-3.

## Secrets management

Secrets are stored in HashiCorp Vault under `secret/<service>/<environment>`. Services read them at
start-up through the Vault agent sidecar; nothing is baked into images. Secrets are rotated every
ninety days by the `vault-rotator` job, which runs on Sunday nights. To add a new secret, submit a
pull request to the `vault-policies` repository; the security team reviews it within two working days.

## Helm charts and configuration

Each service has a chart in `deploy/chart`. Environment-specific values live in `values-dev.yaml`,
`values-staging.yaml` and `values-production.yaml`. Resource requests must be set explicitly; charts
without requests are rejected by the `chart-lint` step. Production replicas default to three and must
never be set below two.

## Database migrations

Migrations run as a Kubernetes job before the new version starts. They must be backward compatible with
the previous release so that a rollback never needs a down migration. Destructive changes (dropping a
column, renaming a table) are split into two releases: the first stops using the column, the second
removes it.

## Emergency hotfix procedure

For an urgent fix, create a branch from the production tag, cherry-pick the change and open a pull
request labelled `hotfix`. The pipeline skips the staging approval for hotfix branches but still runs
the canary. Hotfixes must be merged back into `main` on the same day.
