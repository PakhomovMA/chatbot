# API compatibility and versioning guide

## Selecting a version

Clients select a contract with header `Shop-API-Version` using a date such as `2026-08-15`. Requests without the header use the account default, not automatically the newest version. The sandbox account `sandbox-version-lab` can opt into release candidates.

## Deprecation timeline

A deprecated version remains available for 180 days after its Sunset header first appears. Sunset timestamps use GMT and the API returns `Deprecation: true` throughout the warning period. Emergency removal is allowed only for a security defect and requires a `BREAKING-SECURITY` notice.

## Compatible changes

Adding an optional response field is compatible; adding a required request field is not. Renaming an enum value requires a new version, while adding a new enum value requires clients to tolerate unknown values. Error payload field `diagnostic_id` is stable across versions and safe to log.

## Pagination changes

Cursor tokens are opaque and expire after 24 hours. Clients must not sort the returned items locally to infer the next page. Version 2026-08-15 uses cursor `created_at,id`; older versions used only `created_at` and therefore could skip equal timestamps.

## Webhook compatibility

Webhook schema version is configured independently from the REST version. The endpoint setting `webhook_contract=v3` enables the `payment.disputed.v2` event without changing REST responses. Replays preserve the original schema version even after the endpoint is upgraded.

## Testing and migration

The compatibility suite is invoked with `shopctl api-contract check --base <old> --candidate <new>`. A migration guide lists one before/after request pair for every breaking field. Partner notifications go to `api-change-announcements` at least 60 days before a planned breaking release.
