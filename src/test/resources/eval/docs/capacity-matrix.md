# Capacity and saturation matrix

The values below are the approved starting points for production capacity reviews. Limits assume two availability zones and the standard request shape.

| Service | Minimum pods | Maximum pods | CPU target | Queue warning | Scale-in cooldown |
|---|---:|---:|---:|---:|---:|
| payments-api | 6 | 12 | 62 percent | 400 jobs | 8 minutes |
| ledger-writer | 4 | 8 | 55 percent | 120 jobs | 12 minutes |
| webhook-relay | 3 | 15 | 68 percent | 900 jobs | 5 minutes |
| settlement-worker | 2 | 6 | 60 percent | 75 jobs | 20 minutes |
| search-api | 5 | 20 | 70 percent | 250 jobs | 6 minutes |

## Review rules

A capacity review compares the busiest weekday with the previous four-week baseline. The payments-api reserve is calculated for the loss of one zone, so six pods are the floor even when average utilisation is low. Ledger-writer scaling must not exceed eight pods until the database connection budget is recalculated.

## Load test identifiers

The weekly scenario is named `mixed-checkout-v4`. It sends 70 percent card payments, 20 percent wallet payments and 10 percent refunds. A successful run keeps the queue below its warning threshold for 30 consecutive minutes and records the result under `capacity/weekly/<date>`.
