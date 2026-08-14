# Morpheus EfficientIP Plugin

The Morpheus EfficientIP Plugin integrates Morpheus with EfficientIP SolidServer to provide IP address management (IPAM) and DNS record automation. The plugin communicates with the SolidServer REST API to allocate and release IP addresses and manage DNS records.

## Table of Contents

- [Features](#features)
- [Requirements](#requirements)
- [Repository structure](#repository-structure)
- [Building the plugin](#building-the-plugin)
- [License](#license)
- [Installing](#installing)
- [Detailed Usage Steps](#detailed-usage-steps)
- [API Endpoints](#api-endpoints)

---

## Features

### IP Address Management

Allocate and release IP addresses from EfficientIP SolidServer network pools within Morpheus. Supports automatic next-available IP selection, manual IP entry, and existing inventory import.

### DNS Record Management

Create and delete DNS records (A and alias records) in SolidServer zones when instances are provisioned or decommissioned.

### Cloud Sync

Morpheus synchronises the following SolidServer resources for inventory:

- Network pools (subnets and IP ranges managed in SolidServer)
- IP address allocations

---

## Requirements

| Requirement | Version |
|-------------|---------|
| Morpheus | 7.0.10 or later |
| Java | 11 or later |
| Gradle | Use the included Gradle wrapper (`./gradlew`) |

Additional prerequisites:

- A running EfficientIP SolidServer instance accessible over HTTP or HTTPS from the Morpheus appliance
- A SolidServer user account with sufficient API permissions to manage IP addresses and DNS records
- Network access from the Morpheus appliance to the SolidServer host on the configured port

---

## Repository structure

```
src/main/groovy/com/efficientip/solidserver/
├── SolidServerPlugin.groovy    - Plugin entry point; registers SolidServerProvider
└── SolidServerProvider.groovy  - IPAMProvider implementation; IPAM and DNS operations, sync, OptionTypes
build.gradle, gradle.properties - Build configuration and plugin metadata
```

---

## Building the plugin

Run the following command to compile and package the plugin jar:

```bash
./gradlew clean build
```

The packaged jar will be written to `build/libs/`.

To execute tests, use the following command:

```bash
./gradlew test
```

---

## License

This project is licensed under the Apache License 2.0.

See the [LICENSE](LICENSE) file for details.

---

## Installing

1. Build the plugin (see [Building the plugin](#building-the-plugin)) or download a released jar.
2. In Morpheus, navigate to **Administration > Integrations > Plugins**.
3. Click **Add** and upload the `morpheus-efficientip-plugin-<version>.jar` from `build/libs/`.
4. Navigate to **Infrastructure > Networks > IP Pools > Add** and select **EfficientIP** to configure the integration.

---

## Detailed Usage Steps

### Adding an EfficientIP IPAM Integration

1. Go to **Infrastructure > Networks > IP Pools > Add**.
2. Select **EfficientIP** as the pool server type.
3. Enter the **Service URL** (e.g. `https://solidserver.example.com`), **Username**, and **Password**.
4. Save. Morpheus connects to SolidServer and syncs available network pools.

### Allocating an IP Address

When provisioning an instance on a network backed by an EfficientIP pool, Morpheus automatically calls SolidServer to reserve the next available IP. A DNS record is created if DNS is configured on the network.

### Releasing an IP Address

When an instance is decommissioned, Morpheus calls SolidServer to release the IP and delete the associated DNS records.

---

## API Endpoints

This plugin communicates with the **EfficientIP SolidServer REST API** at the configured service URL. Authentication uses HTTP Basic credentials. All calls use HTTP or HTTPS as configured.

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `{serviceUrl}/rest/ip_add` | POST | Allocate an IP address |
| `{serviceUrl}/rest/ip_alias_add` | POST | Add an IP alias |
| `{serviceUrl}/rest/ip_delete` | DELETE | Release an IP address |
| `{serviceUrl}/rest/dns_rr_add` | POST | Create a DNS resource record |
| `{serviceUrl}/rest/dns_rr_delete` | DELETE | Delete a DNS resource record |
| `{serviceUrl}/rest/ip_list` | GET | List IP addresses in a subnet |
| `{serviceUrl}/rest/subnet_list` | GET | List subnets/pools |
