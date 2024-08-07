/*
* Copyright 2022 the original author or authors.
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
*    http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
package com.efficientip.solidserver

import com.morpheusdata.core.DNSProvider
import com.morpheusdata.core.IPAMProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.util.ConnectionUtils
import com.morpheusdata.core.util.HttpApiClient
import com.morpheusdata.core.util.NetworkUtility
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.AccountIntegration
import com.morpheusdata.model.Icon
import com.morpheusdata.model.Network
import com.morpheusdata.model.NetworkDomain
import com.morpheusdata.model.NetworkDomainRecord
import com.morpheusdata.model.NetworkPool
import com.morpheusdata.model.NetworkPoolIp
import com.morpheusdata.model.NetworkPoolRange
import com.morpheusdata.model.NetworkPoolServer
import com.morpheusdata.model.NetworkPoolType
import com.morpheusdata.model.OptionType
import com.morpheusdata.model.projection.NetworkDomainIdentityProjection
import com.morpheusdata.model.projection.NetworkDomainRecordIdentityProjection
import com.morpheusdata.model.projection.NetworkPoolIdentityProjection
import com.morpheusdata.model.projection.NetworkPoolIpIdentityProjection
import com.morpheusdata.response.ServiceResponse
import groovy.util.logging.Slf4j
import io.reactivex.rxjava3.core.Single
import org.apache.http.entity.ContentType
import io.reactivex.rxjava3.core.Observable

/**
 * The IPAM / DNS Provider implementation for EfficientIP SolidServer
 * This contains most methods used for interacting directly with the SolidServer 8.0+ REST API
 * 
 * @author David Estes
 */
@Slf4j
class SolidServerProvider implements IPAMProvider, DNSProvider {

    MorpheusContext morpheusContext
    Plugin plugin

    SolidServerProvider(Plugin plugin, MorpheusContext morpheusContext) {
        this.morpheusContext = morpheusContext
        this.plugin = plugin
    }

    /**
     * Creates a manually allocated DNS Record of the specified record type on the passed {@link NetworkDomainRecord} object.
     * This is typically called outside of automation and is a manual method for administration purposes.
     * @param integration The DNS Integration record which contains things like connectivity info to the DNS Provider
     * @param record The domain record that is being requested for creation. All the metadata needed to create teh record
     *               should exist here.
     * @param opts any additional options that may be used in the future to configure behavior. Currently unused
     * @return a ServiceResponse with the success/error state of the create operation as well as the modified record.
     */
    @Override
    ServiceResponse createRecord(AccountIntegration integration, NetworkDomainRecord record, Map opts) {
        ServiceResponse<NetworkDomainRecord> rtn = new ServiceResponse<>()
        HttpApiClient client = new HttpApiClient()
        client.networkProxy = morpheusContext.services.setting.getGlobalNetworkProxy()
        String dnsRRAddPath = "/rest/dns_rr_add"
        def poolServer = morpheus.network.getPoolServerByAccountIntegration(integration).blockingGet()

        try {
            if(integration) {
                def fqdn = record.name
                if(!record.name.endsWith(record.networkDomain.name)) {
                    fqdn = "${record.name}.${record.networkDomain.name}"
                }

                //first see if it auto synced from a pool creation
                def listResults = listDnsResourceRecords(client,poolServer,opts + [queryParams:[WHERE:"dnszone_id=${record.networkDomain.externalId} and value1='${record.content}'".toString()]])

                if(listResults.success && listResults.data) {
                    def existingRecord = listResults.data.first()
                    record.externalId = existingRecord.rr_id
                    return new ServiceResponse<NetworkDomainRecord>(true,null,null,record)
                } else {
                    def addQueryParams = [rr_name: fqdn.toString(), rr_type: record.type, value1: record.content, ttl: record.ttl?.toString(), dns_id: record.networkDomain.configuration, dns_zone_id: record.networkDomain.externalId]
                    log.info("Add DNS Query Params ${addQueryParams}")
                    def results = client.callJsonApi(poolServer.serviceUrl, dnsRRAddPath, poolServer.credentialData?.username as String ?: poolServer.serviceUsername, poolServer.credentialData?.password as String ?: poolServer.servicePassword, new HttpApiClient.RequestOptions(headers:['Content-Type':'application/json'], ignoreSSL: poolServer.ignoreSsl,queryParams: addQueryParams), 'POST')


                    log.info("createRecord results: ${results}")
                    if(results.success) {
                        record.externalId = results.data?.first()?.ret_oid
                        return new ServiceResponse<NetworkDomainRecord>(true,null,null,record)
                    }
                }
            } else {
                log.warn("no integration")
            }
        } catch(e) {
            log.error("createRecord error: ${e}", e)
        } finally {
            client.shutdownClient()
        }
        return rtn
    }

    /**
     * Deletes a Zone Record that is specified on the Morpheus side with the target integration endpoint.
     * This could be any record type within the specified integration and the authoritative zone object should be
     * associated with the {@link NetworkDomainRecord} parameter.
     * @param integration The DNS Integration record which contains things like connectivity info to the DNS Provider
     * @param record The zone record object to be deleted on the target integration.
     * @param opts opts any additional options that may be used in the future to configure behavior. Currently unused
     * @return the ServiceResponse with the success/error of the delete operation.
     */
    @Override
    ServiceResponse deleteRecord(AccountIntegration integration, NetworkDomainRecord record, Map opts) {
        def rtn = new ServiceResponse()
        try {
            if(integration) {
                morpheus.network.getPoolServerByAccountIntegration(integration).doOnSuccess({ poolServer ->
                    def serviceUrl = poolServer.serviceUrl
                    HttpApiClient client = new HttpApiClient()
                    client.networkProxy = morpheusContext.services.setting.getGlobalNetworkProxy()
                    try {
                        String apiPath = "/rest/dns_rr_delete"
                        def deleteQueryParams = [rr_id: record.externalId]
                        //we have an A Record to delete
                        def results = client.callJsonApi(serviceUrl, apiPath, poolServer.credentialData?.username as String ?: poolServer.serviceUsername, poolServer.credentialData?.password as String ?: poolServer.servicePassword, new HttpApiClient.RequestOptions(headers:['Content-Type':'application/json'], ignoreSSL: poolServer.ignoreSsl, queryParams: deleteQueryParams,
                                contentType:ContentType.APPLICATION_JSON), 'DELETE')
                        log.info("deleteRecord results: ${results}")
                        if(results.success) {
                            rtn.success = true
                        }
                    } finally {
                        client.shutdownClient()
                    }

                }).doOnError({error ->
                    log.error("Error deleting record: {}",error.message,error)
                }).doOnSubscribe({ sub ->
                    log.info "Subscribed"
                }).blockingGet()
                return ServiceResponse.success()
            } else {
                log.warn("no integration")
            }
        } catch(e) {
            log.error("provisionServer error: ${e}", e)
        }
        return rtn
        return null
    }

    /**
     * Validation Method used to validate all inputs applied to the integration of an IPAM Provider upon save.
     * If an input fails validation or authentication information cannot be verified, Error messages should be returned
     * via a {@link ServiceResponse} object where the key on the error is the field name and the value is the error message.
     * If the error is a generic authentication error or unknown error, a standard message can also be sent back in the response.
     *
     * @param poolServer The Integration Object contains all the saved information regarding configuration of the IPAM Provider.
     * @param opts any custom payload submission options may exist here
     * @return A response is returned depending on if the inputs are valid or not.
     */
    @Override
    ServiceResponse verifyNetworkPoolServer(NetworkPoolServer poolServer, Map opts) {
        ServiceResponse<NetworkPoolServer> rtn = ServiceResponse.error()
        rtn.data = poolServer
        HttpApiClient solidServerClient = new HttpApiClient()
        def networkProxy = morpheusContext.services.setting.getGlobalNetworkProxy()
        solidServerClient.networkProxy = networkProxy
        try {
            def apiUrl = poolServer.serviceUrl
            boolean hostOnline = false
            try {
                def apiUrlObj = new URL(apiUrl)
                def apiHost = apiUrlObj.host
                def apiPort = apiUrlObj.port > 0 ? apiUrlObj.port : (apiUrlObj?.protocol?.toLowerCase() == 'https' ? 443 : 80)
                hostOnline = ConnectionUtils.testHostConnectivity(apiHost, apiPort, false, true, networkProxy)
            } catch(e) {
                log.error("Error parsing URL {}", apiUrl, e)
            }
            if(hostOnline) {
                opts.doPaging = false
                opts.maxResults = 1
                def spacesList = listSpaces(solidServerClient,poolServer, opts)
                if(spacesList.success) {
                    rtn.success = true
                } else {
                    rtn.msg = spacesList.msg ?: 'Error connecting to SolidServer'
                }
            } else {
                rtn.msg = 'Host not reachable'
            }
        } catch(e) {
            log.error("verifyPoolServer error: ${e}", e)
        } finally {
            solidServerClient.shutdownClient()
        }
        return rtn
    }

    /**
     * Called during creation of a {@link NetworkPoolServer} operation. This allows for any custom operations that need
     * to be performed outside of the standard operations.
     * @param poolServer The Integration Object contains all the saved information regarding configuration of the IPAM Provider.
     * @param opts any custom payload submission options may exist here
     * @return A response is returned depending on if the operation was a success or not.
     */
    @Override
    ServiceResponse createNetworkPoolServer(NetworkPoolServer poolServer, Map opts) {
        return null
    }

    /**
     * Called during update of an existing {@link NetworkPoolServer}. This allows for any custom operations that need
     * to be performed outside of the standard operations.
     * @param poolServer The Integration Object contains all the saved information regarding configuration of the IPAM Provider.
     * @param opts any custom payload submission options may exist here
     * @return A response is returned depending on if the operation was a success or not.
     */
    @Override
    ServiceResponse updateNetworkPoolServer(NetworkPoolServer poolServer, Map opts) {
        return null
    }

    ServiceResponse testNetworkPoolServer(HttpApiClient client, NetworkPoolServer poolServer) {
        def rtn = new ServiceResponse()
        try {
            def spacesList = listSpaces(client, poolServer, [:])
            rtn.success = spacesList.success
            rtn.data = [:]
            if(!spacesList.success) {
                rtn.msg = 'error connecting to SolidServer'
            }
        } catch(e) {
            rtn.success = false
            log.error("test network pool server error: ${e}", e)
        }
        return rtn
    }

    /**
     * Periodically called to refresh and sync data coming from the relevant integration. Most integration providers
     * provide a method like this that is called periodically (typically 5 - 10 minutes). DNS Sync operates on a 10min
     * cycle by default. Useful for caching Host Records created outside of Morpheus.
     * @param poolServer The Integration Object contains all the saved information regarding configuration of the IPAM Provider.
     */
    @Override
    void refresh(NetworkPoolServer poolServer) {
        log.debug("refreshNetworkPoolServer: {}", poolServer.dump())
        HttpApiClient solidServerClient = new HttpApiClient()
        solidServerClient.throttleRate = poolServer.serviceThrottleRate
        def networkProxy = morpheusContext.services.setting.getGlobalNetworkProxy()
        solidServerClient.networkProxy = networkProxy
        try {
            def apiUrl = poolServer.serviceUrl
            def apiUrlObj = new URL(apiUrl)
            def apiHost = apiUrlObj.host
            def apiPort = apiUrlObj.port > 0 ? apiUrlObj.port : (apiUrlObj?.protocol?.toLowerCase() == 'https' ? 443 : 80)
            def hostOnline = ConnectionUtils.testHostConnectivity(apiHost, apiPort, false, true, networkProxy)
            log.debug("online: {} - {}", apiHost, hostOnline)
            def testResults
            // Promise
            if(hostOnline) {
                testResults = testNetworkPoolServer(solidServerClient,poolServer) as ServiceResponse<Map>

                if(!testResults.success) {
                    //NOTE invalidLogin was only ever set to false.
                    morpheus.network.updateNetworkPoolServerStatus(poolServer, AccountIntegration.Status.error, 'error calling EfficientIP SolidServer').subscribe().dispose()
                } else {

                    morpheus.network.updateNetworkPoolServerStatus(poolServer, AccountIntegration.Status.syncing).subscribe().dispose()
                }
            } else {
                morpheus.network.updateNetworkPoolServerStatus(poolServer, AccountIntegration.Status.error, 'SolidServer api not reachable').subscribe().dispose()
            }
            Date now = new Date()
            if(testResults?.success) {
                cacheNetworks(solidServerClient,poolServer)
                cacheZones(solidServerClient,poolServer)
                if(poolServer?.configMap?.inventoryExisting) {
                    cacheIpAddressRecords(solidServerClient,poolServer)
                    cacheZoneRecords(solidServerClient,poolServer)
                }
                log.info("Sync Completed in ${new Date().time - now.time}ms")
                morpheus.network.updateNetworkPoolServerStatus(poolServer, AccountIntegration.Status.ok).subscribe().dispose()
            }
        } catch(e) {
            log.error("refreshNetworkPoolServer error: ${e}", e)
        } finally {
            solidServerClient.shutdownClient()
        }
    }

    // cacheNetworks methods
    void cacheNetworks(HttpApiClient client, NetworkPoolServer poolServer, Map opts = [:]) {
        opts.doPaging = true
        def listResults = listNetworkSubnetsAndPools(client, poolServer, opts)

        if(listResults.success) {
            List apiItems = listResults.data as List<Map>
            Observable<NetworkPoolIdentityProjection> poolRecords = morpheus.network.pool.listIdentityProjections(poolServer.id)

            SyncTask<NetworkPoolIdentityProjection,Map,NetworkPool> syncTask = new SyncTask(poolRecords, apiItems as Collection<Map>)
            syncTask.addMatchFunction { NetworkPoolIdentityProjection domainObject, Map apiItem ->
                if(apiItem.type == 'pool') {
                    domainObject.typeCode == 'solidserver.pool' && domainObject.externalId == apiItem.pool_id
                } else { //subnet
                    domainObject.typeCode == 'solidserver.subnet' && domainObject.externalId == apiItem.subnet_id
                }
            }.onDelete {removeItems ->
                morpheus.network.pool.remove(poolServer.id, removeItems).blockingGet()
            }.onAdd { itemsToAdd ->
                addMissingPools(poolServer, itemsToAdd)
            }.withLoadObjectDetails { List<SyncTask.UpdateItemDto<NetworkPoolIdentityProjection,Map>> updateItems ->

                Map<Long, SyncTask.UpdateItemDto<NetworkPoolIdentityProjection, Map>> updateItemMap = updateItems.collectEntries { [(it.existingItem.id): it]}
                return morpheus.network.pool.listById(updateItems.collect{it.existingItem.id} as Collection<Long>).map { NetworkPool pool ->
                    SyncTask.UpdateItemDto<NetworkPoolIdentityProjection, Map> matchItem = updateItemMap[pool.id]
                    return new SyncTask.UpdateItem<NetworkPool,Map>(existingItem:pool, masterItem:matchItem.masterItem)
                }

            }.onUpdate { List<SyncTask.UpdateItem<NetworkPool,Map>> updateItems ->
                updateMatchedPools(poolServer, updateItems)
            }.start()
        }
    }

    void addMissingPools(NetworkPoolServer poolServer, Collection<Map> chunkedAddList) {
        def subnetType = new NetworkPoolType(code: 'solidserver.subnet')
        def poolType = new NetworkPoolType(code: 'solidserver.pool')
        List<NetworkPool> missingPoolsList = []
        List<NetworkPoolRange> ranges = []
        chunkedAddList?.each { Map add ->
            def addConfig = [parentId: poolServer.id, parentType: 'NetworkPoolServer', poolEnabled:true, configuration: add.site_id]
            def rangeConfig = [ startAddress: add.start_hostaddr,
                                endAddress: add.end_hostaddr, addressCount: add.pool_size?.toInteger() ?: add.subnet_size?.toInteger()]
            def rangeName = "${rangeConfig.startAddress} - ${rangeConfig.endAddress}"
            if(add.type == 'pool') {
                addConfig += [type:poolType, name: add.pool_name ?: rangeName,externalId: add.pool_id, displayName: "${add.subnet_name} - ${add.pool_name}"]
            } else {
                addConfig += [type:subnetType, name: add.subnet_name ?: rangeName,externalId: add.subnet_id, displayName: add.subnet_name ?: rangeName]
            }
            addConfig.ipCount = rangeConfig.addressCount

            def newNetworkPool =new NetworkPool(addConfig)
            newNetworkPool.ipRanges = []
            def addRange = new NetworkPoolRange(rangeConfig)
            newNetworkPool.ipRanges.add(addRange)
            missingPoolsList.add(newNetworkPool)
        }
        morpheus.network.pool.create(poolServer.id, missingPoolsList).blockingGet()
    }

    void updateMatchedPools(NetworkPoolServer poolServer, List<SyncTask.UpdateItem<NetworkPool,Map>> chunkedUpdateList) {
        List<NetworkPool> poolsToUpdate = []
        chunkedUpdateList?.each { update ->
            NetworkPool existingItem = update.existingItem
            if(existingItem) {
                //update view ?
                def save = false
                String displayName
                Integer ipCount = update.masterItem.pool_size?.toInteger() ?: update.masterItem.subnet_size?.toInteger()


                if(update.masterItem.type == 'pool') {
                    displayName = "${update.masterItem.subnet_name} - ${update.masterItem.pool_name}"
                } else {
                    displayName = update.masterItem.subnet_name
                }

                if(existingItem.ipCount != ipCount) {
                    existingItem.ipCount = ipCount
                    save = true
                }

                if(existingItem?.displayName != displayName) {
                    existingItem.displayName = displayName
                    save = true
                }
                if(!existingItem.configuration) {
                    existingItem.configuration = update.masterItem.site_id
                    save = true
                }

                if(!existingItem.ipRanges) {
                    log.warn("no ip ranges found!")
                    def rangeConfig =  [ startAddress: update.masterItem.start_hostaddr,
                                                      endAddress: update.masterItem.end_hostaddr, addressCount: update.masterItem.pool_size ?: update.masterItem.subnet_size]
                    def addRange = new NetworkPoolRange(rangeConfig)
                    existingItem.addToIpRanges(addRange)

                    save = true
                }
                if(save) {
                    poolsToUpdate << existingItem
                }
            }
        }
        if(poolsToUpdate.size() > 0) {
            morpheus.network.pool.save(poolsToUpdate).blockingGet()
        }
    }


    // cacheIpAddressRecords
    void cacheIpAddressRecords(HttpApiClient client, NetworkPoolServer poolServer, Map opts=[:]) {
        morpheus.network.pool.listIdentityProjections(poolServer.id).buffer(50).concatMap { Collection<NetworkPoolIdentityProjection> poolIdents ->
            return morpheus.network.pool.listById(poolIdents.collect{it.id})
        }.concatMap { NetworkPool pool ->
            def listResults
            if(pool.type.code == 'solidserver.pool') {
                listResults = listIpAddresses(client,poolServer,opts + [queryParams:[WHERE:"pool_id=${pool.externalId}".toString()]])
            } else {
                listResults = listIpAddresses(client,poolServer,opts + [queryParams:[WHERE:"subnet_id=${pool.externalId}".toString()]])
            }

            if (listResults.success) {

                List<Map> apiItems = listResults.data.findAll{!it.type?.contains('free') || it?.subnet_start_hostaddr == it.hostaddr || it?.subnet_end_hostaddr == it.hostaddr} as List<Map>
                Observable<NetworkPoolIpIdentityProjection> poolIps = morpheus.network.pool.poolIp.listIdentityProjections(pool.id)
                SyncTask<NetworkPoolIpIdentityProjection, Map, NetworkPoolIp> syncTask = new SyncTask<NetworkPoolIpIdentityProjection, Map, NetworkPoolIp>(poolIps, apiItems)
                return syncTask.addMatchFunction { NetworkPoolIpIdentityProjection ipObject, Map apiItem ->
                    ipObject.externalId == apiItem.ip_id
                }.addMatchFunction { NetworkPoolIpIdentityProjection domainObject, Map apiItem ->
                    domainObject.ipAddress == apiItem.hostaddr
                }.onDelete {removeItems ->
                    morpheus.network.pool.poolIp.remove(pool.id, removeItems).blockingGet()
                }.onAdd { itemsToAdd ->
                    addMissingIps(pool, itemsToAdd)
                }.withLoadObjectDetails { List<SyncTask.UpdateItemDto<NetworkPoolIpIdentityProjection,Map>> updateItems ->

                    Map<Long, SyncTask.UpdateItemDto<NetworkPoolIpIdentityProjection, Map>> updateItemMap = updateItems.collectEntries { [(it.existingItem.id): it]}
                    return morpheus.network.pool.poolIp.listById(updateItems.collect{it.existingItem.id} as Collection<Long>).map { NetworkPoolIp poolIp ->
                        SyncTask.UpdateItemDto<NetworkPoolIpIdentityProjection, Map> matchItem = updateItemMap[poolIp.id]
                        return new SyncTask.UpdateItem<NetworkPoolIp,Map>(existingItem:poolIp, masterItem:matchItem.masterItem)
                    }

                }.onUpdate { List<SyncTask.UpdateItem<NetworkDomain,Map>> updateItems ->
                    updateMatchedIps(updateItems)
                }.observe()
            } else {
                return Single.just(false)
            }
        }.doOnError{ e ->
            log.error("cacheIpRecords error: ${e}", e)
        }.blockingSubscribe()

    }

    void addMissingIps(NetworkPool pool, List addList) {

        List<NetworkPoolIp> poolIpsToAdd = addList?.collect { it ->
            def ip = it.hostaddr
            def type = it.type
            def id = it.ip_id
            def hostname = it.name
            def ipType = 'assigned'
            def start = it?.subnet_start_hostaddr ?: null
            def end = it?.subnet_end_hostaddr ?: null

            if(ip == start) {
                ipType = 'unmanaged'
                hostname = 'Network'
            }
            if(ip == end) {
                ipType = 'unmanaged'
                hostname = 'Broadcast'
            }

            def addConfig = [networkPool: pool, networkPoolRange: pool.ipRanges ? pool.ipRanges.first() : null, ipType: ipType, hostname: hostname, ipAddress: ip, externalId:id, internalId:ip]
            def newObj = new NetworkPoolIp(addConfig)
            return newObj
        }
        if(poolIpsToAdd.size() > 0) {
            morpheus.network.pool.poolIp.create(pool, poolIpsToAdd).blockingGet()
        }
    }

    void updateMatchedIps(List<SyncTask.UpdateItem<NetworkPoolIp,Map>> updateList) {
        List<NetworkPoolIp> ipsToUpdate = []
        updateList?.each {  update ->
            NetworkPoolIp existingItem = update.existingItem

            if(existingItem) {
                def hostname = update.masterItem.name
                def type = update.masterItem.type
                def ipType = 'assigned'
                def ip = update.masterItem.hostaddr
                def id = update.masterItem.ip_id
                def start = update.masterItem?.subnet_start_hostaddr ?: null
                def end = update.masterItem?.subnet_end_hostaddr ?: null
                def save = false

                if(ip == start) {
                    ipType = 'unmanaged'
                    hostname = 'Network'
                }
                if(ip == end) {
                    ipType = 'unmanaged'
                    hostname = 'Broadcast'
                }

                if(existingItem.ipType != ipType) {
                    existingItem.ipType = ipType
                    save = true
                }
                if(existingItem.hostname != hostname) {
                    existingItem.hostname = hostname
                    save = true
                }
                if(existingItem.internalId != ip) {
                    existingItem.internalId = ip
                    save = true
                }
                if(existingItem.externalId != id) {
                    existingItem.externalId = id
                    save = true
                }
                if(save) {
                    ipsToUpdate << existingItem
                }
            }
        }
        if(ipsToUpdate.size() > 0) {
            morpheus.network.pool.poolIp.save(ipsToUpdate).blockingGet()
        }
    }


    // Cache Zones methods
    def cacheZones(HttpApiClient client, NetworkPoolServer poolServer, Map opts = [:]) {
        try {
            def listResults = listDnsZones(client, poolServer, opts)

            if (listResults.success) {
                List apiItems = listResults.data as List<Map>
                Observable<NetworkDomainIdentityProjection> domainRecords = morpheus.network.domain.listIdentityProjections(poolServer.integration.id)

                SyncTask<NetworkDomainIdentityProjection,Map,NetworkDomain> syncTask = new SyncTask(domainRecords, apiItems as Collection<Map>)
                syncTask.addMatchFunction { NetworkDomainIdentityProjection domainObject, Map apiItem ->
                    domainObject.externalId == apiItem.dnszone_id
                }.onDelete {removeItems ->
                    morpheus.network.domain.remove(poolServer.integration.id, removeItems).blockingGet()
                }.onAdd { itemsToAdd ->
                    addMissingZones(poolServer, itemsToAdd)
                }.withLoadObjectDetails { List<SyncTask.UpdateItemDto<NetworkDomainIdentityProjection,Map>> updateItems ->
                    Map<Long, SyncTask.UpdateItemDto<NetworkDomainIdentityProjection, Map>> updateItemMap = updateItems.collectEntries { [(it.existingItem.id): it]}
                    return morpheus.network.domain.listById(updateItems.collect{it.existingItem.id} as Collection<Long>).map { NetworkDomain networkDomain ->
                        SyncTask.UpdateItemDto<NetworkDomainIdentityProjection, Map> matchItem = updateItemMap[networkDomain.id]
                        return new SyncTask.UpdateItem<NetworkDomain,Map>(existingItem:networkDomain, masterItem:matchItem.masterItem)
                    }
                }.onUpdate { List<SyncTask.UpdateItem<NetworkDomain,Map>> updateItems ->
                    updateMatchedZones(poolServer, updateItems)
                }.start()
            }
        } catch (e) {
            log.error("cacheZones error: ${e}", e)
        }
    }

    /**
     * Creates a mapping for networkDomainService.createSyncedNetworkDomain() method on the network context.
     * @param poolServer
     * @param addList
     */
    void addMissingZones(NetworkPoolServer poolServer, Collection addList) {
        List<NetworkDomain> missingZonesList = addList?.collect { Map add ->
            NetworkDomain networkDomain = new NetworkDomain()
            networkDomain.externalId = add.dnszone_id
            networkDomain.name = NetworkUtility.getFriendlyDomainName(add.dnszone_name_utf as String)
            networkDomain.fqdn = NetworkUtility.getFqdnDomainName(add.dnszone_name_utf as String)
            networkDomain.refSource = 'integration'
            networkDomain.zoneType = 'Authoritative'
            networkDomain.configuration = add.dns_id
            return networkDomain
        }
        morpheus.network.domain.create(poolServer.integration.id, missingZonesList).blockingGet()
    }

    /**
     * Given a pool server and updateList, extract externalId's and names to match on and update NetworkDomains.
     * @param poolServer
     * @param addList
     */
    void updateMatchedZones(NetworkPoolServer poolServer, List<SyncTask.UpdateItem<NetworkDomain,Map>> updateList) {
        def domainsToUpdate = []
        for(SyncTask.UpdateItem<NetworkDomain,Map> update in updateList) {
            NetworkDomain existingItem = update.existingItem as NetworkDomain
            if(existingItem) {
                Boolean save = false
                if(!existingItem.externalId) {
                    existingItem.externalId = update.masterItem.dnszone_id
                    save = true
                }
                if(!existingItem.configuration) {
                    existingItem.configuration = update.masterItem.dns_id
                    save = true
                }
                if(!existingItem.refId) {
                    existingItem.refType = 'AccountIntegration'
                    existingItem.refId = poolServer.integration.id
                    existingItem.refSource = 'integration'
                    save = true
                }

                if(save) {
                    domainsToUpdate.add(existingItem)
                }
            }
        }
        if(domainsToUpdate.size() > 0) {
            morpheus.network.domain.save(domainsToUpdate).blockingGet()
        }
    }


    // Cache Zones methods
    def cacheZoneRecords(HttpApiClient client, NetworkPoolServer poolServer, Map opts=[:]) {
        morpheus.network.domain.listIdentityProjections(poolServer.integration.id).buffer(50).concatMap { Collection<NetworkDomainIdentityProjection> poolIdents ->
            return morpheus.network.domain.listById(poolIdents.collect{it.id})
        }.concatMap { NetworkDomain domain ->

            def listResults = listDnsResourceRecords(client,poolServer,opts + [queryParams:[WHERE:"dnszone_id=${domain.externalId}".toString()]])


            if (listResults.success) {
                List<Map> apiItems = listResults.data as List<Map>
                Observable<NetworkDomainRecordIdentityProjection> domainRecords = morpheus.network.domain.record.listIdentityProjections(domain,null)
                SyncTask<NetworkDomainRecordIdentityProjection, Map, NetworkDomainRecord> syncTask = new SyncTask<NetworkDomainRecordIdentityProjection, Map, NetworkDomainRecord>(domainRecords, apiItems)
                return syncTask.addMatchFunction {  NetworkDomainRecordIdentityProjection domainObject, Map apiItem ->
                    domainObject.externalId == apiItem.rr_id
                }.onDelete {removeItems ->
                    morpheus.network.domain.record.remove(domain, removeItems).blockingGet()
                }.onAdd { itemsToAdd ->
                    addMissingDomainRecords(domain, itemsToAdd)
                }.withLoadObjectDetails { List<SyncTask.UpdateItemDto<NetworkDomainRecordIdentityProjection,Map>> updateItems ->

                    Map<Long, SyncTask.UpdateItemDto<NetworkDomainRecordIdentityProjection, Map>> updateItemMap = updateItems.collectEntries { [(it.existingItem.id): it]}
                    return morpheus.network.domain.record.listById(updateItems.collect{it.existingItem.id} as Collection<Long>).map { NetworkDomainRecord domainRecord ->
                        SyncTask.UpdateItemDto<NetworkDomainRecordIdentityProjection, Map> matchItem = updateItemMap[domainRecord.id]
                        return new SyncTask.UpdateItem<NetworkDomainRecord,Map>(existingItem:domainRecord, masterItem:matchItem.masterItem)
                    }

                }.onUpdate { List<SyncTask.UpdateItem<NetworkDomainRecord,Map>> updateItems ->
                    updateMatchedDomainRecords(updateItems)
                }.observe()
            } else {
                return Single.just(false)
            }
        }.doOnError{ e ->
            log.error("cacheIpRecords error: ${e}", e)
        }.blockingSubscribe()

    }


    void updateMatchedDomainRecords(List<SyncTask.UpdateItem<NetworkDomainRecord, Map>> updateList) {
        def records = []
        updateList?.each { update ->
            NetworkDomainRecord existingItem = update.existingItem
            String recordType = update.masterItem.rr_type
            if(existingItem) {
                //update view ?
                def save = false
                if(update.masterItem.rr_all_value != existingItem.content) {
                    existingItem.setContent(update.masterItem.rr_all_value as String)
                    save = true
                }

                if(update.masterItem.rr_full_name_utf != existingItem.name) {
                    existingItem.name = update.masterItem.rr_full_name_utf
                    existingItem.fqdn = update.masterItem.rr_full_name_utf
                    save = true
                }

                if(save) {
                    records.add(existingItem)
                }
            }
        }
        if(records.size() > 0) {
            morpheus.network.domain.record.save(records).blockingGet()
        }
    }

    void addMissingDomainRecords(NetworkDomainIdentityProjection domain, Collection<Map> addList) {
        List<NetworkDomainRecord> records = []

        addList?.each {
            String recordType = it.rr_type
            def addConfig = [networkDomain: new NetworkDomain(id: domain.id), externalId:it.rr_id, name: it.rr_full_name_utf, fqdn: it.rr_full_name_utf, type: recordType, source: 'sync']
            def newObj = new NetworkDomainRecord(addConfig)
            newObj.ttl = it.ttl?.toInteger()
            newObj.setContent(it.rr_all_value)
            records.add(newObj)
        }
        morpheus.network.domain.record.create(domain,records).blockingGet()
    }


    /**
     * Called on the first save / update of a pool server integration. Used to do any initialization of a new integration
     * Often times this calls the periodic refresh method directly.
     * @param poolServer The Integration Object contains all the saved information regarding configuration of the IPAM Provider.
     * @param opts an optional map of parameters that could be sent. This may not currently be used and can be assumed blank
     * @return a ServiceResponse containing the success state of the initialization phase
     */
    @Override
    ServiceResponse initializeNetworkPoolServer(NetworkPoolServer poolServer, Map opts) {
        def rtn = new ServiceResponse()
        try {
            if(poolServer) {
                refresh(poolServer)
                rtn.data = poolServer
            } else {
                rtn.error = 'No pool server found'
            }
        } catch(e) {
            rtn.error = "initializeNetworkPoolServer error: ${e}"
            log.error("initializeNetworkPoolServer error: ${e}", e)
        }
        return rtn
    }

    /**
     * Creates a Host record on the target {@link NetworkPool} within the {@link NetworkPoolServer} integration.
     * @param poolServer The Integration Object contains all the saved information regarding configuration of the IPAM Provider.
     * @param networkPool the NetworkPool currently being operated on.
     * @param networkPoolIp The ip address and metadata related to it for allocation. It is important to create functionality such that
     *                      if the ip address property is blank on this record, auto allocation should be performed and this object along with the new
     *                      ip address be returned in the {@link ServiceResponse}
     * @param domain The domain with which we optionally want to create an A/PTR record for during this creation process.
     * @param createARecord configures whether or not the A record is automatically created
     * @param createPtrRecord configures whether or not the PTR record is automatically created
     * @return a ServiceResponse containing the success state of the create host record operation
     */
    @Override
    ServiceResponse createHostRecord(NetworkPoolServer poolServer, NetworkPool networkPool, NetworkPoolIp networkPoolIp, NetworkDomain domain, Boolean createARecord, Boolean createPtrRecord) {
        HttpApiClient client = new HttpApiClient();
        client.networkProxy = morpheusContext.services.setting.getGlobalNetworkProxy()
        try {
            String serviceUrl = poolServer.serviceUrl
            String findFreeAddressPath = "/rpc/ip_find_free_address"
            String ipAddPath = "/rest/ip_add"
            String ipAliasAddPath = "/rest/ip_alias_add"

            def hostname = networkPoolIp.hostname
            if (domain && hostname && !hostname.endsWith(domain.name)) {
                hostname = "${hostname}.${domain.name}"
            }
            def addQueryParams = [add_flag:'new_only', name: hostname.toString(), site_id: networkPool.configuration]
            def ipAddResults = null
            if(networkPoolIp.ipAddress) {
                addQueryParams.hostaddr = networkPoolIp.ipAddress
                ipAddResults = client.callJsonApi(serviceUrl, ipAddPath, poolServer.credentialData?.username as String ?: poolServer.serviceUsername, poolServer.credentialData?.password as String ?: poolServer.servicePassword, new HttpApiClient.RequestOptions(headers: ['Content-Type': 'application/json'], ignoreSSL: poolServer.ignoreSsl,
                        queryParams: addQueryParams), 'POST')

            } else {
                def queryParams = [:]
                if(networkPool.type.code == 'solidserver.pool') {
                    queryParams.pool_id = networkPool.externalId
                } else {
                    queryParams.subnet_id = networkPool.externalId
                }
                log.debug("getting Free Addresses ${queryParams}")
                def freeIpResults = client.callJsonApi(serviceUrl, findFreeAddressPath, poolServer.credentialData?.username as String ?: poolServer.serviceUsername, poolServer.credentialData?.password as String ?: poolServer.servicePassword, new HttpApiClient.RequestOptions(headers: ['Content-Type': 'application/json'], ignoreSSL: poolServer.ignoreSsl,
                        queryParams: queryParams), 'GET')
                log.debug("Free IP Results: ${freeIpResults}")
                def attempts = 0
                while (freeIpResults.success && (ipAddResults == null || !ipAddResults?.success)) {
                    attempts++
                    for(freeIp in freeIpResults.data) {
                        def siteId = freeIp.site_id
                        def hostAddr = freeIp.hostaddr
                        addQueryParams.site_id = siteId
                        addQueryParams.hostaddr = hostAddr
                        log.debug("attempting ip add for address ${hostAddr}")
                        ipAddResults = client.callJsonApi(serviceUrl, ipAddPath, poolServer.credentialData?.username as String ?: poolServer.serviceUsername, poolServer.credentialData?.password as String ?: poolServer.servicePassword, new HttpApiClient.RequestOptions(headers: ['Content-Type': 'application/json'], ignoreSSL: poolServer.ignoreSsl,
                                queryParams: addQueryParams), 'POST')
                        log.debug("ipAddResults: ${ipAddResults}")
                        if(ipAddResults.success) {
                            break
                        }
                    }
                    if(attempts > 5) {
                        break
                    }
                }
            }

            if(ipAddResults?.success) {
                def ipId = ipAddResults.data?.first()?.ret_oid
                networkPoolIp.externalId = ipId
                networkPoolIp.ipAddress = addQueryParams.hostaddr
                if(createARecord) {
                    networkPoolIp.domain = domain
                }
                if (networkPoolIp.id) {
                    networkPoolIp = morpheus.network.pool.poolIp.save(networkPoolIp)?.blockingGet()
                } else {
                    networkPoolIp = morpheus.network.pool.poolIp.create(networkPoolIp)?.blockingGet()
                }
                if (createARecord && domain) {
                    def domainRecord = new NetworkDomainRecord(networkDomain: domain,ttl:3600, networkPoolIp: networkPoolIp, name: hostname, fqdn: hostname, source: 'user', type: 'A',content: networkPoolIp.ipAddress)
                    def createRecordResults = createRecord(poolServer.integration,domainRecord,[:])
                    if(createRecordResults.success) {
                        morpheus.network.domain.record.create(createRecordResults.data).blockingGet()
                    }
                }

                return ServiceResponse.success(networkPoolIp)
            } else {
                return ServiceResponse.error("Error allocating host record to the specified ip", null, networkPoolIp)
            }
        } finally {
            client.shutdownClient()
        }
    }

    /**
     * Updates a Host record on the target {@link NetworkPool} if supported by the Provider. If not supported, send the appropriate
     * {@link ServiceResponse} such that the user is properly informed of the unavailable operation.
     * @param poolServer The Integration Object contains all the saved information regarding configuration of the IPAM Provider.
     * @param networkPool the NetworkPool currently being operated on.
     * @param networkPoolIp the changes to the network pool ip address that would like to be made. Most often this is just the host record name.
     * @return a ServiceResponse containing the success state of the update host record operation
     */
    @Override
    ServiceResponse updateHostRecord(NetworkPoolServer poolServer, NetworkPool networkPool, NetworkPoolIp networkPoolIp) {
        HttpApiClient client = new HttpApiClient();
        client.networkProxy = morpheusContext.services.setting.getGlobalNetworkProxy()
        try {
            String ipAddPath = "/rest/ip_add"
            def hostname = networkPoolIp.hostname

            def addQueryParams = [name: hostname.toString(), hostaddr: networkPoolIp.ipAddress, ip_id:networkPoolIp.externalId]

            def ipAddResults = client.callJsonApi(poolServer.serviceUrl, ipAddPath, poolServer.credentialData?.username as String ?: poolServer.serviceUsername, poolServer.credentialData?.password as String ?: poolServer.servicePassword, new HttpApiClient.RequestOptions(headers: ['Content-Type': 'application/json'], ignoreSSL: poolServer.ignoreSsl,
                    queryParams: addQueryParams), 'PUT')



            if(ipAddResults?.success) {
                def ipId = ipAddResults.data?.first()?.ret_oid
                networkPoolIp.externalId = ipId
                networkPoolIp.ipAddress = addQueryParams.hostaddr
                networkPoolIp.hostname = hostname
                if (networkPoolIp.id) {
                    networkPoolIp = morpheus.network.pool.poolIp.save(networkPoolIp)?.blockingGet()
                }
                return ServiceResponse.success(networkPoolIp)
            } else {
                return ServiceResponse.error("Error allocating host record to the specified ip", null, networkPoolIp)
            }
        } finally {
            client.shutdownClient()
        }
    }

    /**
     * Deletes a host record on the target {@link NetworkPool}. This is used for cleanup or releasing of an ip address on
     * the IPAM Provider.
     * @param networkPool the NetworkPool currently being operated on.
     * @param poolIp the record that is being deleted.
     * @param deleteAssociatedRecords determines if associated records like A/PTR records
     * @return a ServiceResponse containing the success state of the delete operation
     */
    @Override
    ServiceResponse deleteHostRecord(NetworkPool networkPool, NetworkPoolIp poolIp, Boolean deleteAssociatedRecords) {
        HttpApiClient client = new HttpApiClient();
        client.networkProxy = morpheusContext.services.setting.getGlobalNetworkProxy()
        def poolServer = morpheus.network.getPoolServerById(networkPool.poolServer.id).blockingGet()
        try {
            if(poolIp.externalId) {
                def results = client.callApi(poolServer.serviceUrl, "/rest/ip_delete", poolServer.credentialData?.username as String ?: poolServer.serviceUsername, poolServer.credentialData?.password as String ?: poolServer.servicePassword, new HttpApiClient.RequestOptions(headers:['Content-Type':'application/json'], ignoreSSL: poolServer.ignoreSsl,
                        contentType:ContentType.APPLICATION_JSON,queryParams: [ip_id:poolIp.externalId]), 'DELETE')
                if(results.success) {
                    return ServiceResponse.success(poolIp)
                } else {
                    return ServiceResponse.error(results.error ?: 'Error Deleting Host Record', null, poolIp)
                }
            } else {
                return ServiceResponse.error("Record not associated with corresponding record in target provider", null, poolIp)
            }
        } finally {
            client.shutdownClient()
        }
        return null
    }

    /**
     * Periodically called to refresh and sync data coming from the relevant integration. Most integration providers
     * provide a method like this that is called periodically (typically 5 - 10 minutes). DNS Sync operates on a 10min
     * cycle by default. Useful for caching DNS Records created outside of Morpheus.
     * NOTE: This method is unused when paired with a DNS Provider so simply return null
     * @param integration The Integration Object contains all the saved information regarding configuration of the DNS Provider.
     */
    @Override
    void refresh(AccountIntegration integration) {
        //NOOP
    }

    /**
     * Validation Method used to validate all inputs applied to the integration of an DNS Provider upon save.
     * If an input fails validation or authentication information cannot be verified, Error messages should be returned
     * via a {@link ServiceResponse} object where the key on the error is the field name and the value is the error message.
     * If the error is a generic authentication error or unknown error, a standard message can also be sent back in the response.
     * NOTE: This is unused when paired with an IPAMProvider interface
     * @param integration The Integration Object contains all the saved information regarding configuration of the DNS Provider.
     * @param opts any custom payload submission options may exist here
     * @return A response is returned depending on if the inputs are valid or not.
     */
    @Override
    ServiceResponse verifyAccountIntegration(AccountIntegration integration, Map opts) {
        //NOOP
        return null
    }

    /**
     * An IPAM Provider can register pool types for display and capability information when syncing IPAM Pools
     * @return a List of {@link NetworkPoolType} to be loaded into the Morpheus database.
     */
    @Override
    Collection<NetworkPoolType> getNetworkPoolTypes() {
        return [
                new NetworkPoolType(code:'solidserver.subnet', name:'SolidServer Subnet', creatable:false, description:'SolidServer Subnet', rangeSupportsCidr: false),
                new NetworkPoolType(code:'solidserver.pool', name:'SolidServer Pool', creatable:false, description:'SolidServer Pool', rangeSupportsCidr: false),
                new NetworkPoolType(code:'solidserver.subnetipv6', name:'SolidServer Subnet IPv6', creatable:false, description:'SolidServer Subnet IPv6', rangeSupportsCidr: false),
                new NetworkPoolType(code:'solidserver.poolipv6', name:'SolidServer Pool IPv6', creatable:false, description:'SolidServer Pool IPv6', rangeSupportsCidr: false)
        ];
    }

    /**
     * Provide custom configuration options when creating a new {@link AccountIntegration}
     * @return a List of OptionType
     */
    @Override
    List<OptionType> getIntegrationOptionTypes() {
        return [
                new OptionType(code: 'solidserver.serviceUrl', name: 'Service URL', inputType: OptionType.InputType.TEXT, fieldName: 'serviceUrl', fieldLabel: 'API Url', fieldContext: 'domain', helpBlock: 'Warning! Using HTTP URLS are insecure and not recommended.', displayOrder: 0),
                new OptionType(code: 'solidserver.credentials', name: 'Credentials', inputType: OptionType.InputType.CREDENTIAL, fieldName: 'type', fieldLabel: 'Credentials', fieldContext: 'credential', required: true, displayOrder: 1, defaultValue: 'local',optionSource: 'credentials',config: '{"credentialTypes":["username-password"]}'),
                new OptionType(code: 'solidserver.serviceUsername', name: 'Service Username', inputType: OptionType.InputType.TEXT, fieldName: 'serviceUsername', fieldLabel: 'Username', fieldContext: 'domain', displayOrder: 2, localCredential: true),
                new OptionType(code: 'solidserver.servicePassword', name: 'Service Password', inputType: OptionType.InputType.PASSWORD, fieldName: 'servicePassword', fieldLabel: 'Password', fieldContext: 'domain', displayOrder: 3, localCredential: true),
                new OptionType(code: 'solidserver.throttleRate', name: 'Throttle Rate', inputType: OptionType.InputType.NUMBER, defaultValue: 0, fieldName: 'serviceThrottleRate', fieldLabel: 'Throttle Rate', fieldContext: 'domain', displayOrder: 4),
                new OptionType(code: 'solidserver.ignoreSsl', name: 'Ignore SSL', inputType: OptionType.InputType.CHECKBOX, defaultValue: 0, fieldName: 'ignoreSsl', fieldLabel: 'Disable SSL SNI Verification', fieldContext: 'domain', displayOrder: 5),
                new OptionType(code: 'solidserver.inventoryExisting', name: 'Inventory Existing', inputType: OptionType.InputType.CHECKBOX, defaultValue: 0, fieldName: 'inventoryExisting', fieldLabel: 'Inventory Existing', fieldContext: 'config', displayOrder: 6)
        ]
    }

    /**
     * Returns the IPAM Integration logo for display when a user needs to view or add this integration
     * @since 0.12.3
     * @return Icon representation of assets stored in the src/assets of the project.
     */
    @Override
    Icon getIcon() {
        return new Icon(path:"efficientIP140-black.svg", darkPath: "efficientIP140-white.svg")
    }

    /**
     * Returns the Morpheus Context for interacting with data stored in the Main Morpheus Application
     *
     * @return an implementation of the MorpheusContext for running Future based rxJava queries
     */
    @Override
    MorpheusContext getMorpheus() {
        return morpheusContext
    }

    /**
     * Returns the instance of the Plugin class that this provider is loaded from
     * @return Plugin class contains references to other providers
     */
    @Override
    Plugin getPlugin() {
        return plugin
    }

    /**
     * A unique shortcode used for referencing the provided provider. Make sure this is going to be unique as any data
     * that is seeded or generated related to this provider will reference it by this code.
     * @return short code string that should be unique across all other plugin implementations.
     */
    @Override
    String getCode() {
        return "solidserver"
    }

    /**
     * Provides the provider name for reference when adding to the Morpheus Orchestrator
     * NOTE: This may be useful to set as an i18n key for UI reference and localization support.
     *
     * @return either an English name of a Provider or an i18n based key that can be scanned for in a properties file.
     */
    @Override
    String getName() {
        return "EfficientIP SolidServer"
    }


    private ServiceResponse listNetworkSubnets(HttpApiClient client, NetworkPoolServer poolServer, Map opts) {
        listObjects("ip_block_subnet_list",client,poolServer,opts)
    }



    private ServiceResponse listSpaces(HttpApiClient client, NetworkPoolServer poolServer, Map opts) {
        listObjects("ip_site_list",client,poolServer,opts)
    }

    private ServiceResponse listIpAddresses(HttpApiClient client, NetworkPoolServer poolServer, Map opts) {
        listObjects("ip_address_list",client,poolServer,opts)
    }

    private ServiceResponse listDnsZones(HttpApiClient client, NetworkPoolServer poolServer, Map opts) {
        listObjects("dns_zone_list",client,poolServer,opts)
    }

    private ServiceResponse listDnsResourceRecords(HttpApiClient client, NetworkPoolServer poolServer, Map opts) {
        listObjects("dns_rr_list",client,poolServer,opts)
    }

    private ServiceResponse listNetworkSubnetsAndPools(HttpApiClient client, NetworkPoolServer poolServer, Map opts) {
        ServiceResponse response = new ServiceResponse()
        def networkList = listObjects("ip_block_subnet_list",client,poolServer,opts)
        if(networkList.success) {
            def poolList = listObjects("ip_pool_list",client,poolServer,opts)
            if(poolList.success) {
                response.success = true
                response.data = []
                response.data += networkList.data?.findAll{it.is_terminal?.toInteger() == 1I}
                poolList.data?.each { pool ->
                    pool.type = 'pool'
                }
                response.data += poolList.data
            } else {
                return poolList
            }
        } else {
            return networkList
        }
        return response
    }

    /**
     * Standard method for calling SolidServer *_list REST Services. This auto pages the dataset in smaller chunks and returns all results
     * @param listService the service name from the EfficientIP SolidServer REST API
     * @param client the HttpClient being used during this operation for keep-alive
     * @param poolServer the PoolServer integration we are referencing for credentials and connectivity info
     * @param opts other options
     * @return
     */
    private ServiceResponse listObjects(String listService,HttpApiClient client, NetworkPoolServer poolServer, Map opts) {
        def rtn = new ServiceResponse(success: false)
        def serviceUrl = poolServer.serviceUrl
        def apiPath = "/rest/${listService}"
        def hasMore = true
        def maxResults = opts.maxResults ?: 100
        rtn.data = []
        Integer offset = 0
        def attempt = 0
        while(hasMore && attempt < 1000) {
            Map<String,String> pageQuery = [limit:maxResults.toString(),offset:offset.toString()] + (opts?.queryParams ?: [:])
            //load results
            def results = client.callJsonApi(serviceUrl, apiPath, poolServer.credentialData?.username as String ?: poolServer.serviceUsername, poolServer.credentialData?.password as String ?: poolServer.servicePassword, new HttpApiClient.RequestOptions(headers:['Content-Type':'application/json'], queryParams: pageQuery,
                    contentType: ContentType.APPLICATION_JSON, ignoreSSL: poolServer.ignoreSsl), 'GET')
            log.debug("listNetworkSubnets results: ${results.toMap()}")
            if(results?.success && !results?.hasErrors()) {
                rtn.success = true
                rtn.headers = results.headers
                def pageResults = results.data

                if(pageResults?.size() > 0) {
                    if(pageResults?.size() >= maxResults) {
                        offset += maxResults
                        hasMore = true
                    } else {
                        hasMore = false
                    }

                    if (rtn.data) {
                        rtn.data += pageResults
                    } else {
                        rtn.data = pageResults
                    }
                } else {
                    hasMore = false
                }
            } else {
                if(!rtn.success) {
                    rtn.msg = results.error
                }
                hasMore = false
            }
            attempt++
        }
        return rtn
    }
}
