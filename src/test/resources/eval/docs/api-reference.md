# Shop API Reference

The Shop API is a JSON REST API served at `https://api.shop.example/v2`. All timestamps are ISO 8601
in UTC and all amounts are integers in minor currency units (cents).

## Authentication

Requests are authenticated with a bearer token obtained from the `/oauth/token` endpoint using the
client-credentials grant. Access tokens expire after sixty minutes; refresh them before expiry to avoid
`401 Unauthorized` responses. Tokens carry scopes; the `orders:write` scope is required to create or
cancel orders and `catalog:read` is enough for product lookups.

## Rate limits

Each client is limited to 600 requests per minute per API key. The current budget is reported in the
`X-RateLimit-Remaining` header. When the budget is exhausted the API returns `429 Too Many Requests`
with a `Retry-After` header in seconds. Bulk endpoints (`/orders/export`) have a separate limit of
10 requests per minute.

## Orders

### Create an order

`POST /orders` creates an order from a list of line items. The request must include an
`Idempotency-Key` header; repeating a request with the same key within 24 hours returns the original
response instead of creating a second order. The response contains the `orderId`, the `status`
(`PENDING`, `PAID`, `SHIPPED`, `CANCELLED`) and the total amount.

### Cancel an order

`POST /orders/{orderId}/cancel` cancels an order that has not been shipped. Cancelling a `SHIPPED`
order returns `409 Conflict` with error code `ORDER_ALREADY_SHIPPED`; use the returns flow instead.

### Search orders

`GET /orders?customerId=&status=&from=&to=` returns a paginated list. Pages contain at most 100
orders; use the `cursor` from the response to fetch the next page. Results are sorted by creation time,
newest first.

## Products

`GET /products/{sku}` returns the product with prices per currency and the available stock per
warehouse. Stock values are cached for sixty seconds. `GET /products?query=` performs a full-text
search over names and descriptions and supports the `inStock=true` filter.

## Webhooks

Clients can register webhook URLs for the events `order.paid`, `order.shipped` and `order.cancelled`.
Each delivery is signed with HMAC-SHA256 using the webhook secret; the signature is sent in the
`X-Shop-Signature` header. Deliveries are retried with exponential backoff for up to 24 hours,
starting at one minute. Endpoints must answer within five seconds with a 2xx status.

## Error format

Errors use RFC 9457 problem details with `application/problem+json`. The `code` property holds a
machine-readable error code such as `VALIDATION_FAILED`, `INSUFFICIENT_STOCK` or
`ORDER_ALREADY_SHIPPED`. Validation errors list the offending fields under `errors`.

## Versioning and deprecation

Breaking changes are released as a new major version path (`/v3`). A deprecated version keeps
working for twelve months after the announcement and sends a `Deprecation` header with the sunset
date during that period.
