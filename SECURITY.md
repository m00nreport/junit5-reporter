# Security Policy

## Reporting a vulnerability

**Please do not open a public issue for a security problem.** A public report is
readable by everyone, including whoever would exploit it, from the moment it is
filed.

Two private channels, in order of preference:

1. **GitHub private vulnerability reporting** - the "Report a vulnerability"
   button on this repository's Security tab. It is the fastest route, it needs no
   mailbox on either side, and it keeps the whole exchange attached to the code.
2. **security@m00nreport.com** if you would rather use email, or if private
   reporting is unavailable to you.

Tell us what you can: what the flaw is, how to reach it, and what an attacker
gets. A rough report sent early is more useful than a polished one sent late.

## What to expect

- An acknowledgement within 3 working days.
- An assessment, with our view of severity and a rough timeline, within 10
  working days.
- Credit in the release notes if you want it, and none if you do not.

We will tell you when a fix ships and to which version. If we disagree that
something is a vulnerability we will say so plainly, with our reasoning, rather
than letting the report go quiet.

## Scope

This repository is the M00N Report reporter for JUnit 5. It runs inside your test process and talks to a M00N
Report ingest service over HTTPS.

In scope, and the areas worth looking at hardest:

- Anything that discloses the project API key, or a URL carrying it, to a place
  it does not belong: logs, a third-party origin, an error message, a crash dump.
- Anything that lets one project's key read or write another project's data.
- Anything that makes the reporter execute data it received from a server as code.
- Anything reachable through a malicious or compromised ingest endpoint, since
  the server URL is configuration and a CI job may be pointed anywhere.

Out of scope: the hosted M00N Report service itself, which has its own reporting
path at https://m00nreport.com; and reports that consist only of an automated
scanner's output with no demonstrated impact.

## Supported versions

The latest published release is supported. Fixes are issued as a new release
rather than as patches to older ones, because the registries this ships to serve
whatever is current.
