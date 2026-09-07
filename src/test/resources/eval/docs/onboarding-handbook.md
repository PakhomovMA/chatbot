# Engineering Onboarding Handbook

Welcome to the platform engineering group. This handbook lists what to set up in your first two weeks
and how we work together.

## Accounts and access

On your first day you receive a Google Workspace account, a GitHub invitation to the `shop`
organisation and a Slack account. Access to AWS and Vault is requested through the `access-requests`
Jira project; approvals come from your team lead and usually take one working day. Production
database access is not granted to individuals; use the read replica through the `sqlpad` tool.

## Laptop setup

Install the toolchain with the bootstrap script:

```
curl -fsSL https://tools.shop.example/bootstrap.sh | bash
```

The script installs Homebrew packages, the AWS CLI, `kubectl`, `helm` and the internal `shopctl`
tool. Full-disk encryption must be enabled and the laptop must enrol in the device management before
VPN access is activated.

## VPN and network

The VPN client is Tailscale; sign in with your Google account. Internal services are reachable under
`*.internal.shop.example` only while connected. If the VPN drops repeatedly, restart the Tailscale
daemon with `sudo tailscale down && sudo tailscale up`.

## Repositories and branching

All services live in the `shop` GitHub organisation, one repository per service. We use trunk-based
development: short-lived branches from `main`, squash merges, and no long-running release branches.
Branch names follow `<ticket-id>-<short-description>`, for example `SHOP-1234-fix-checkout-total`.

## Code review

Every change needs one approving review from a code owner; changes to payment or authentication code
need two. Reviewers are expected to respond within one working day. Keep pull requests under 400
changed lines where possible; larger changes should be split. Use the `draft` state for work in
progress so that nobody reviews it prematurely.

## Testing expectations

Unit tests run on every push. Integration tests run in the pipeline against the `dev` environment and
must pass before a change can be promoted. Flaky tests are quarantined by adding the `@Flaky` tag and
opening a ticket; a quarantined test must be fixed or deleted within two weeks.

## On-call

Engineers join the on-call rotation after three months. Rotations are one week long, primary and
secondary. The primary responds to pages within fifteen minutes; the secondary takes over if the
primary does not acknowledge within thirty minutes. On-call weeks are compensated with one extra day
of leave.

## Useful channels

`#platform-help` for questions about tooling, `#incidents` for live incidents, `#releases` for release
announcements and `#random-dogs` for morale.
