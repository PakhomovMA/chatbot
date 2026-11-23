# Advanced rollback runbook

## Choosing a rollback mode

Use an image rollback when the previous schema can read and write the current data. Use a forward fix when an irreversible external payout has already been confirmed. A manual rollback is initiated with `shopctl rollback execute --release <id> --reason <ticket>` and must reference an incident or release ticket.

## Dual-write compatibility

During the two-release migration window, the old service reads column `amount_total` while the new service writes both `amount_total` and `amount_minor`. Rolling back before the second cleanup release is safe because `amount_total` remains populated. Dropping that column before the cleanup gate blocks an image rollback.

## Canary abort behaviour

An aborted canary moves traffic to the stable image within 90 seconds and adds annotation `rollback.source=automatic`. It does not delete the failed rollout record. The operator must compare the failed image digest with the stable digest before closing the rollback event.

## Manual verification

Run `shopctl rollback verify --release <id>` after the manual command. Verification checks ten synthetic charges and one refund; the expected receipt prefix is `RBK-OK`. If only charges succeed, keep the release in `ROLLBACK_PENDING` rather than marking it complete.

## Database and queue safeguards

Pause schema migrations when the rollback lock file `/run/payments/rollback.lock` exists. The settlement consumer uses a five-minute quarantine after a rollback before replaying deferred messages. A replay started too early can duplicate externally visible webhook events even when the ledger remains idempotent.

## Communication

Record the recovered traffic percentage and remaining failed requests in `#payments-releases`. For a rollback lasting more than 20 minutes, open a SEV-2 review and name a rollback owner. The final note must include the stable image digest, not only the word reverted.
