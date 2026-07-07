# Morpheus Efficient IP Plugin

This plugin provides an IPAM and DNS integration between [EfficientIP SOLIDserver](https://www.efficientip.com/products/solidserver/) and [Morpheus](https://morpheusdata.com). It enables subnet and pool sync, DNS zone and resource record inventory, host record management, IP allocation, and IP release automation from within the Morpheus platform.

## Requirements

| Component | Minimum Version |
|-----------|----------------|
| Morpheus | 7.0.10 |

## Installation

1. Download the latest `.jar` from the [Releases](https://github.com/HewlettPackard/morpheus-efficientip-plugin/releases) page, or [build it yourself](#building).
2. In Morpheus, navigate to **Administration → Integrations → Plugins**.
3. Click **Browse** and upload the `.jar` file.
4. The **EfficientIP SolidServer** IPAM/DNS network service integration will appear after the plugin loads.

## Configuration

When adding an EfficientIP SolidServer network service in Morpheus (**Infrastructure → Network → Services**), provide the following:

| Field | Description |
|-------|-------------|
| **API Url** | EfficientIP SOLIDserver API endpoint root URL. |
| **Credentials** | Morpheus credential containing the SOLIDserver username and password. |
| **Username** | SOLIDserver username used when local credentials are selected. |
| **Password** | SOLIDserver password used when local credentials are selected. |
| **Throttle Rate** | Optional API throttle rate for SOLIDserver requests. |
| **Disable SSL SNI Verification** | Disables SSL SNI verification when connecting to SOLIDserver. |
| **Inventory Existing** | Syncs existing IP address records and DNS resource records from SOLIDserver into Morpheus. |

Credentials can also be stored as a Morpheus [Credential](https://docs.morpheusdata.com/en/latest/administration/credentials/credentials.html) and selected at network service setup time.

## Features

### IPAM Sync

The plugin implements `IPAMProvider` and keeps Morpheus network pools aligned with EfficientIP SOLIDserver.

- **Subnets** — terminal SOLIDserver subnets are synced as Morpheus network pools
- **Pools** — SOLIDserver IP pools are synced as Morpheus network pools
- **Ranges** — start and end addresses are mapped to Morpheus pool ranges
- **Pool metadata** — names, display names, site IDs, size, and description parameters are retained

Any additions, updates, and removals in SOLIDserver are automatically reflected in Morpheus on the next network service refresh.

### IP Address Inventory

When existing inventory sync is enabled, the plugin caches SOLIDserver IP address records for synced subnets and pools.

- **Assigned addresses** — synced with hostname and external SOLIDserver ID
- **Network and broadcast addresses** — marked as unmanaged records
- **Address updates** — hostname, ID, and address state changes are reflected in Morpheus

### IP Allocation and Release

Morpheus can allocate and release addresses from synced SOLIDserver subnets and pools during workload lifecycle operations. Supported operations include:

- Assign a requested IP address when available
- Find and allocate a free address from a subnet or pool
- Create an A record during allocation when requested
- Update a host record name or address association
- Release allocated IP records when workloads are removed

### DNS Zone Sync

The plugin implements `DNSProvider` and discovers authoritative DNS zones from SOLIDserver.

- **Authoritative zones** — synced into Morpheus as network domains
- **DNS IDs and zone IDs** — retained for record creation and deletion
- **Existing record inventory** — optional sync of existing DNS resource records when enabled in configuration

### DNS Record Management

DNS resource records can be managed from Morpheus through the SOLIDserver API. Supported operations include:

- Create DNS resource records in synced zones
- Delete DNS resource records
- Sync existing DNS records with type, TTL, name, FQDN, and value data
- Update synced DNS records when values or names change in SOLIDserver

## Building

```bash
./gradlew shadowJar
```

The plugin JAR will be written to `build/libs/`.

## License

Copyright 2022 the original author or authors. Licensed under the [Apache License, Version 2.0](LICENSE).
