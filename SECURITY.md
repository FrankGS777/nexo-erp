# Security and Demo Boundaries

This repository is an isolated portfolio edition. It is not a production service and must not be connected to a real company database.

## Public browser showcase

- GitHub Pages serves static HTML, CSS, JavaScript and local assets only.
- No application backend, API credentials, identity lookups, fiscal connections, analytics or third-party scripts are used.
- A content security policy blocks network connections from the application.
- Sample edits are stored in that browser's local storage. The reset control restores the fixtures.
- Printed documents are explicitly marked as demonstrations without fiscal validity.
- Static content is public. Browser storage is not suitable for personal or confidential information.

## Runnable Java edition

- Uses a fresh H2 in-memory database; the fixture loader rejects persistent or remote databases.
- Contains only synthetic companies, customers, product prices and sample operations.
- Starts read-only. Requests that mutate business data or open account, fiscal and security administration are denied before reaching controllers.
- The fiscal gateway always returns `DEMO_NO_FISCAL_CONNECTION`. The real transport, certificate-loading setup and identity-provider adapters are not included.
- Form login, CSRF protection, authorization checks, output escaping and security headers remain enabled.
- The published `viewer` password is deliberately a demonstration credential. It does not grant access to any external system.
- The repository includes TOTP and QR components as implementation examples; public visitors cannot enroll or change the shared demo account.

## Reporting a concern

Use the private security advisory workflow on this repository when available. Do not put credentials or private business records in an issue, discussion or pull request.

Automated checks reduce risk; they are not a guarantee that software has no vulnerabilities. Review all changes and assets before making a fork public.
