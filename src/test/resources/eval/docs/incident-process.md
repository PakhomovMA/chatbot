# Incident Management Process

This document describes how we detect, communicate and learn from production incidents.

## Severity levels

- **SEV-1**: customers cannot buy or pay; data loss or a security breach. Page the on-call and the
  incident commander immediately; update every 30 minutes.
- **SEV-2**: a major feature is degraded for many customers (for example search is down). Page the
  on-call; update every hour.
- **SEV-3**: a minor feature is degraded or a single customer is affected. Handle during working
  hours; no paging.
- **SEV-4**: cosmetic issue or internal tooling problem. Track as a normal bug.

The severity is set by the first responder and can be changed by the incident commander.

## Roles

The **incident commander** coordinates the response, decides on mitigations and owns communication.
The **communications lead** posts updates to the status page and to `#incidents`. **Responders**
investigate and apply fixes. During a SEV-1 the incident commander must not debug personally.

## Declaring an incident

Declare an incident with the Slack command `/incident declare`, which creates a dedicated channel
named `#inc-<date>-<slug>`, opens a Jira ticket in the `INC` project and pages the incident commander
for SEV-1 and SEV-2. Everything relevant (dashboards, hypotheses, commands run) is posted in the channel
so that the timeline can be reconstructed later.

## Communication

External communication goes through the status page at `status.shop.example`. The first public update
must be posted within fifteen minutes of declaring a SEV-1 or SEV-2. Internal updates go to
`#incidents` at the cadence defined by the severity. Never promise a fix time publicly; describe the
impact and the next update time instead.

## Mitigation first

The goal during an incident is to stop the customer impact, not to find the root cause. Preferred
mitigations, in order: roll back the latest release, disable the feature flag, scale up, fail over to
the secondary region. Only when the impact is stopped does the team move on to root-cause analysis.

## Postmortems

Every SEV-1 and SEV-2 gets a blameless postmortem within five working days. The document follows the
template in the `postmortems` repository and must contain a timeline, the root cause, the customer
impact in numbers and action items with owners and due dates. Action items are tracked in Jira with
the label `postmortem-action` and reviewed weekly until closed. Postmortems are shared with the whole
engineering organisation in the monthly reliability review.

## Incident metrics

We track time to detect, time to mitigate and time to resolve for every incident. The targets are
five minutes to detect, thirty minutes to mitigate and four hours to resolve for SEV-1.
