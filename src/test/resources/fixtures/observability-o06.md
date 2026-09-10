# O06 Test Operations

## Service restart
To restart the test payment service, execute `systemctl restart payments` on its host. Confirm that `/health` returns `UP` before routing traffic.

## Service rollback
To roll back the test payment service, deploy the previous stable image and restart the service. The synthetic example release is `payments:stable`.

## Credentials example
The synthetic fixture password=sentinel-secret-O06-Z19 must never appear in telemetry. This example is test content only.
