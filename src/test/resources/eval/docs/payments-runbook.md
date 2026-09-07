# Payments Service Runbook

This runbook covers day-to-day operation of the payments service (`payments-api`) that processes card
authorisations and settlements for the web shop. It is owned by the Payments team; the on-call rotation
is published in PagerDuty under the schedule "payments-primary".

## Service overview

The payments service is a Spring Boot application deployed as three replicas behind the internal load
balancer. It talks to the acquirer gateway over mutual TLS and stores transactions in the `payments`
PostgreSQL database. Settlement batches are sent to the acquirer every night at 02:15 UTC by the
`settlement-cron` job.

## Restarting the service

A rolling restart is the first remedy for stuck connection pools or a memory leak warning.

1. Check the current health with `kubectl get pods -n payments`.
2. Restart one replica at a time: `kubectl rollout restart deployment/payments-api -n payments`.
3. Watch the rollout with `kubectl rollout status deployment/payments-api -n payments`; it should finish
   within four minutes.
4. Confirm that the `payments_auth_success_rate` metric returns above 99.5 percent within ten minutes.

Never restart all replicas at once during the settlement window (02:00 to 02:45 UTC): the batch job holds
an advisory lock in the database and a hard restart leaves the batch half-sent.

## Rolling back a release

If a release causes elevated declines, roll back to the previous image tag:

```
./deploy.sh payments-api --rollback --to previous
```

The script reads the previous tag from the `releases` ConfigMap. A rollback takes about six minutes and
does not require a database migration because migrations are always backward compatible for one
release. After the rollback, post the release id and the reason in the `#payments-releases` channel.

## Alerts and their meaning

| Alert | Threshold | First action |
|---|---|---|
| `PaymentsHighDeclineRate` | declines above 8 percent for 5 minutes | check acquirer status page, then rollback |
| `PaymentsGatewayLatency` | p95 above 1500 ms for 10 minutes | check `gateway_pool_active` and restart |
| `PaymentsSettlementFailed` | batch exit code non-zero | run the settlement retry (below) |
| `PaymentsDbConnections` | more than 180 of 200 connections | restart, then check for leaked transactions |

## Retrying a failed settlement batch

The settlement job can be replayed safely because every batch is idempotent on the acquirer side.

```
kubectl create job --from=cronjob/settlement-cron settlement-retry-$(date +%s) -n payments
```

Check the job logs for the line `settlement accepted` and the acquirer reference number, then close
the `PaymentsSettlementFailed` alert. If the acquirer rejects the batch twice, escalate to the acquirer
support line (reference number needed) and page the Payments manager.

## Database maintenance

The `payments` database runs on PostgreSQL 16. Vacuum runs automatically; a manual `VACUUM ANALYZE`
is only needed after bulk deletes. Point-in-time recovery is configured with a retention of fourteen
days. To restore a snapshot, open a ticket with the DBA team; never restore directly from the
application host.

## Feature flags

Feature flags live in the `payments-flags` ConfigMap. The most important ones are `3ds_enforced`
(forces 3-D Secure on every card transaction) and `new_risk_engine` (routes ten percent of traffic to
the new risk scoring model). Flag changes take effect within thirty seconds without a restart.
