# README

**Repository:** `dpn-federator-gateway`
**Description:** `Provides a mechanism to exchange data between Data Preparation nodes (DPN)`

<!-- SPDX-License-Identifier: Apache-2.0 AND OGL-UK-3.0 -->

---

## Overview

This repository contributes to the development of **secure, scalable, and interoperable data-sharing infrastructure**. It supports DSI's mission to enable **trusted, federated, and decentralised** data-sharing across organisations.

This repository is one of several open-source components that underpin DSI's **Data Preparation Node (DPN)**—a framework designed to allow organisations to manage and exchange data securely while maintaining control over their own information. The DPN is actively deployed and tested across multiple sectors, ensuring its adaptability and alignment with real-world needs.

## Prerequisites

* Java 21
* This repo uses a maven wrapper so no installation of maven is required.
* [Docker](https://www.docker.com/)
* [Git](https://git-scm.com/)

## Configuration & Installation

Detailed configuration and installation instructions for this repository are present in **[dpn-integration-playbook](https://github.com/energy-dsi/dpn-integration-playbook)**

This includes producer/consumer setup, CI/CD pipeline configuration and execution, and deployment validation. Refer to the guide matching your deployment target:

### AWS Deployment

Refer [aws-manual-beta](https://github.com/energy-dsi/dpn-integration-playbook/tree/main/Docs/03-dpn-application-deployment/aws-manual-beta) for AWS specific deployment 

**Note** AWS Manual deployment is an interim solution and GitHub Actions based deployment to replace the manual deployment in future release

### Azure Deployment

Refer [azure-ado-beta](https://github.com/energy-dsi/dpn-integration-playbook/tree/main/Docs/03-dpn-application-deployment/azure-ado-beta) for Azure specific deployment

## Features

The federator enables secure data exchange between Data Preparation Nodes, supporting both server (producer) and client (consumer) roles. Key features include:

### Data Federation
- Secure, scalable data sharing using Kafka as both source and target.
- Multiple federator servers and clients per organisation for flexible deployment.
- Communication between federator servers and clients uses gRPC over mTLS for secure, authenticated data transfer.
- Federation currently supports RDF payloads, with extensibility hooks for other data formats on a per-topic basis.

### File Streaming
- **File Transfer via gRPC**: Stream large files from server to client as chunked messages over the `GetFilesStream` RPC endpoint.
- **Multi-Cloud Storage Support**: Both producer (server) and consumer (client) support multiple storage backends:
  - **Server (Producer)**: Read files from AWS S3, Azure Blob Storage, Google Cloud Storage (GCP), or Local filesystem
  - **Client (Consumer)**: Write files to AWS S3, Azure Blob Storage, Google Cloud Storage (GCP), or Local filesystem
- **Integrity Verification**: SHA-256 checksums ensure file integrity during transfer
- **Resume Support**: Continue interrupted transfers using sequence IDs to avoid re-transferring complete files
- **Graceful Error Handling**: Server sends `StreamWarning` messages for invalid requests without terminating the stream, allowing subsequent files to be processed
- **S3-Compatible Storage**: Support for MinIO and other S3-compatible storage systems
- **Azure Support**: Works with Azure Blob Storage and Azurite emulator for local development
- **GCP Support**: Works with Google Cloud Storage and fake-gcs-server emulator for local development

### Common Infrastructure
- Integration with Management-Node for centralised configuration, topic management, and authorisation.
- Redis is used for offset tracking and short-lived configuration caching.
- Signed JWT-based authentication with DSM Identity Provider for consumer verification and authorisation.

### Connectivity and security:
- Multiple Federator Producers and Consumers can exchange data across organisations using gRPC over mTLS.
- As long as they are configured to talk to the same Management-Node, they will obtain compatible configuration (topics, roles, filters, endpoints) required for their data exchange.
- The Management-Node, together with the Identity Provider, issues the certificates/credentials and tokens that enable mutual TLS and authorisation.
- This means any number of Producers and Consumers can safely share data so long as their exchange requirements are defined in, and served by, the Management-Node.

### Exchange data between DPN nodes

The Federator is designed to allow data exchange between Data Preparation Nodes. It supports two primary modes of operation:

1. **RDF Message Streaming**: Kafka-to-Kafka message federation with filtering based on security labels
2. **File Streaming**: Large file transfer with multi-cloud storage support and integrity verification

Both modes use gRPC over mTLS for secure communication and are run in a distributed manner with multiple servers and clients.

#### Server (Producer) - Simplified View

**For RDF Messages:**
1. Reads messages from knowledge topics within the source Kafka broker
2. Authenticates clients using JWT tokens and verifies authorization
3. Filters messages based on security labels in message headers using configurable filters
4. Streams filtered messages to authorized clients via gRPC

**For Files:**
1. Reads files from configured storage (S3, Azure, GCP, or Local filesystem)
2. Authenticates clients using JWT tokens and verifies authorization
3. Chunks files into manageable pieces with a configurable chunk size
4. Streams file chunks to authorized clients via gRPC with SHA-256 checksums for integrity verification

#### Client (Consumer) - Simplified View

**For RDF Messages:**
1. Connects and authenticates with known server(s) using JWT tokens via gRPC
2. Requests message streams for authorized topics
3. Writes received messages to target Kafka broker with a configured topic prefix (e.g., 'federated')
4. Tracks offsets in Redis for resume capability

**For Files:**
1. Connects and authenticates with known server(s) using JWT tokens via gRPC
2. Requests file streams, optionally resuming from a previous sequence ID
3. Assembles received chunks and verifies integrity using SHA-256 checksums
4. Uploads complete files to configured storage destination (S3, Azure, GCP, or Local)
5. Tracks file sequence offsets in Redis for resume capability

The underlying communication protocol is [gRPC](https://grpc.io/) over mTLS, providing secure, authenticated data transfer between servers and clients.

## Public Funding Acknowledgment

This repository has been developed with public funding as part of the Data Sharing Infrastructure (DSI), a UK Government initiative. DSI, alongside its partners, has invested in this work to advance open, secure, and reusable digital twin technologies for any organisation, whether from the public or private sector, irrespective of size.

## License

This repository contains both source code and documentation, which are covered by different licenses:
- **Code:** Licensed under the [Apache License 2.0](./LICENSE.md).
- **Documentation:** Licensed under the [Open Government Licence (OGL) v3.0](./OGL_LICENSE.md).

By contributing to this repository, you agree that your contributions will be licenced under these terms.

See [`LICENSE.md`](./LICENSE.md), [`OGL_LICENSE.md`](./OGL_LICENSE.md) and [`NOTICE.md`](./NOTICE.md) for details.

## Security and Responsible Disclosure

We take security seriously. If you believe you have found a security vulnerability in this repository, please follow our responsible disclosure process outlined in [SECURITY.md](./SECURITY.md).

## Contributing

We welcome contributions that align with the Programme's objectives. Please read our [CONTRIBUTING.md](./CONTRIBUTING.md) guidelines before submitting pull requests.

## Acknowledgements  
This repository has benefited from collaboration with various organisations. For a list of acknowledgments, see [ACKNOWLEDGEMENTS.md](./ACKNOWLEDGEMENTS.md).  

## Support and Contact

For questions, feedback, or support requests:

- Contact DSI team via email to [dsi@neso.energy](mailto:dsi@neso.energy)

## Maintained by the National Energy System Operator (NESO)

Copyright 2026 NESO and the Crown.  This work is licensed under the Open Government Licence 3.0 (OGL). This work has been developed by NESO using content licensed by the Department for Business and Trade (UK) under the OGL.   
 
Licensed under the Open Government Licence v3.0.

For full licensing terms, [OGL_LICENSE.md](./OGL_LICENSE.md)
