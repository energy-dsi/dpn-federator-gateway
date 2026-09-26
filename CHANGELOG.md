# Changelog

**Repository:** `dpn-federator-gateway`  
**Description:** `Tracks all notable changes, version history, and roadmap toward 1.0.0 following Semantic Versioning.`

<!-- SPDX-License-Identifier: OGL-UK-3.0 -->

--- 

All notable changes to this repository will be documented in this file.

This project follows **Semantic Versioning (SemVer)** ([semver.org](https://semver.org/)), using the format:

`[MAJOR].[MINOR].[PATCH]`
- **MAJOR** (`X.0.0`) – Incompatible API/feature changes that break backward compatibility.
- **MINOR** (`0.X.0`) – Backward-compatible new features, enhancements, or functionality changes.
- **PATCH** (`0.0.X`) – Backward-compatible bug fixes, security updates, or minor corrections.
- **Pre-release versions** – Use suffixes such as `-alpha`, `-beta`, `-rc.1` (e.g., `2.1.0-beta.1`).
- **Build metadata** – If needed, use `+build` (e.g., `2.1.0+20260314`).

---

## How to Update This Changelog

1. When making changes, update this file under the **Unreleased** section.
2. Before a new release, move changes from **Unreleased** to a new dated section with a version number.
3. Follow **Semantic Versioning** rules to categorise changes correctly.
4. If pre-release versions are used, clearly mark them as `-alpha`, `-beta`, or `-rc.X`.

---

## Release 2.0.0 - September 2026

### Added

- DPN Vault authentication using App Role
- Use of container temp location for keystore and truststore file instead of shared file storage
- OTEL connection using HTTPS configuration
- Redis connectivity using HTTPS and Oaut with DPN authentication service
- AWS deployment pipeline using Github Actions

### Fixed

- Security vulnerabilities from Release 1.0.0

### Removed

- DPN Vault authentication using root token
- Shared file storage for keystore and truststore


## Release 1.0.0 - July 2026

- Federator Gateway server and client component to communicate across DPNs and DPN to DSM using mutual TLS and signed JWT authentication
- Logging using Open Telemetry - Added OTel logging/heartbeat support (OtelJsonLayout, HeartbeatService, OpenTelemetryConfig)
- File and stream based data transmission using GRPC over HTTP/2 protocol
- OCSP Implementation - Server and client check each other's certificate status. Data can be exchanged only with both ACTIVE certificates
- Periodic transmission of heartbeat (producer config fetch request) signal to DSM
- Validation of transferred files and stream data over Checksum report
- Integration with DPN Hashicorp vault for certificates
- Job runner interface for managing and monitoring federator client jobs

---

## Maintained by the National Energy System Operator (NESO)

Copyright 2026 NESO and Crown.  This work is licensed under the Open Government Licence 3.0 (OGL). This work has been developed by NESO using content licensed by the Department for Business and Trade (UK) under the OGL.   
 
Licensed under the Open Government Licence v3.0.

For full licensing terms, [OGL_LICENSE.md](./OGL_LICENSE.md)