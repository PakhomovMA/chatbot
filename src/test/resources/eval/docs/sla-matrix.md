# Support SLA and escalation matrix

Response targets start when the support ticket receives a valid merchant ID and severity. Time spent waiting for customer diagnostics is shown separately and does not silently pause an incident response.

| Severity | First response | Workaround target | Update cadence | Escalation queue |
|---|---:|---:|---:|---|
| SEV-1 | 15 minutes | 30 minutes | 20 minutes | payments-critical |
| SEV-2 | 45 minutes | 4 hours | 60 minutes | payments-duty |
| SEV-3 | 4 hours | 2 working days | daily | product-support |
| SEV-4 | 1 working day | 10 working days | weekly | service-requests |

## Merchant tiers

The `strategic` tier receives a named incident contact for SEV-1 and SEV-2. The `standard` tier uses the same response targets but does not receive a named contact. Moving a merchant between tiers takes effect at 00:00 UTC on the following day.

## Escalation examples

A repeated timeout from all merchants belongs to SEV-1; a reporting discrepancy affecting one export is normally SEV-3. If a SEV-2 workaround is not delivered within four hours, the ticket moves to `payments-critical` without changing its original severity. The escalation reason must cite the elapsed target, not a vague urgency label.
