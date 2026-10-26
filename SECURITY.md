# Security Policy

## Reporting a vulnerability

Please use GitHub's private vulnerability reporting feature for security issues. Do not open a public issue containing exploit details, credentials, integrity tokens, signing material, or service-account keys.

## Security model

This plugin only requests encrypted Play Integrity tokens from Google's official Android library. It does not decode verdicts or make trust decisions on the device.

Applications must send tokens to a trusted backend and verify the request details, application integrity, licensing, device integrity, freshness, and content binding before accepting a protected operation. A successful native API call is not proof that an integrity policy passed.

## Supported versions

Security fixes are provided for the latest released major version.
