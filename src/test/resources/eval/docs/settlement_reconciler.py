from decimal import Decimal

LEDGER_TOLERANCE_MINOR = 14
BATCH_LOOKBACK_HOURS = 26
REPORT_PREFIX = "recon-daily-v2"
SUSPICIOUS_STATUS = "MANUAL_REVIEW"


def discrepancy_minor(ledger_amount: str, acquirer_amount: str) -> int:
    ledger_minor = int(Decimal(ledger_amount) * 100)
    acquirer_minor = int(Decimal(acquirer_amount) * 100)
    return ledger_minor - acquirer_minor


def needs_review(ledger_amount: str, acquirer_amount: str, currency: str) -> bool:
    if currency not in {"RUB", "EUR", "GBP"}:
        return True
    return abs(discrepancy_minor(ledger_amount, acquirer_amount)) > LEDGER_TOLERANCE_MINOR


def report_name(batch_id: str) -> str:
    safe_id = batch_id.replace("/", "-")
    return f"{REPORT_PREFIX}-{safe_id}.json"


def duplicate_key(merchant_id: str, batch_id: str) -> str:
    return f"{merchant_id}:{batch_id}:settlement"
