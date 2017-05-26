/**
 *  Copyright 2017 Rainlake
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 *	Kasa Service Manager
 *
 *	Author: rainlake
 *	Date: 2017-05-25
 *
 *  Last Modification:
 */

import groovy.json.JsonSlurper
import groovy.json.JsonOutput

definition(
		name: "Kasa(Connect)",
		namespace: "rainlake",
		author: "RainLake",
		description: "Connect your Kasa devices to SmartThings.",
		category: "SmartThings Labs",
		iconUrl: "https://raw.githubusercontent.com/rainlake/smartthings/master/smartapps/kasa/kasa.jpg",
		iconX2Url: "https://raw.githubusercontent.com/rainlake/smartthings/master/smartapps/kasa/kasa.jpg",
		singleInstance: true
) {
	
}

preferences {
	page(name: "discoverDevices", title: "DiscoverDevices")
    page(name: "loginToKasa", title: "Kasa")
}

def discoverDevices() {
	if(!atomicState.terminalUUID) {
		atomicState.terminalUUID = UUID.randomUUID().toString()
	}
    if(!settings.username || !settings.password) {
    	return loginToKasa()
    } else {
    	if(!atomicState.token && !login()) {
        	return loginToKasa()
        }
    	def devices = getDeviceList()
        def numFound = devices.size()
        def map = [:]
        devices.each { entry ->
        	map[entry.key] = entry.value.name
        }
        return dynamicPage(name:"discoverDevices", title:"Discovery!", nextPage:"", install: true, uninstall: true) {
            section("Select your device below.") {
                input(name: "selectedDevices", type: "enum", required: false, title: "Select Kasa devices to add ({{numFound}} found)", messageArgs: [numFound: numFound], multiple: true, submitOnChange: true, options: map)
            }
        }
    }
}
def loginToKasa() {
	return dynamicPage(name: "loginToKasa", title: "", nextPage: "discoverDevices", uninstall:false, install:false) {
		section("SmartThings Hub") {
        	input "hostHub", "hub", title: "Select Hub", multiple: false, required: true
        }
		section("Log in to Kasa") {
			input "username", "text", title: "Username", required: true, autoCorrect:false
			input "password", "password", title: "Password", required: true, autoCorrect:false
		}
	}
}
def getDeviceType(kasa) {
	if(kasa.deviceType == "IOT.SMARTPLUGSWITCH") {
    	return "Wi-Fi Smart Light Switch"
    }
    return kasa.deviceName
}

def addDevices() {
	// remove non-exists devices
    log.debug "addDevices"
    getChildDevices()?.findAll {selectedDevices == null || !selectedDevices.contains(it.deviceNetworkId) }.each {
    	log.debug "deleting ${it.deviceNetworkId}"
        unsubscribe(it)
    	deleteChildDevice(it.deviceNetworkId)
    }
	selectedDevices?.each {
    	def device = getChildDevice(it)
    	if(device == null) {
        	log.debug "add device ${atomicState.devices[it]}"
            def state = getDeviceState(it)
        	device = addChildDevice("rainlake", getDeviceType(atomicState.devices[it]), it, hostHub.id, [
            	name: atomicState.devices[it].name,
                label: atomicState.devices[it].name,
            	completedSetup: true
            ])
            device.sendEvent(name: "switch", value: state)
        }
        subscribe(device, "switch", switchHandler)
    }
}
def switchHandler(evt) {
	def device = evt.getDevice()
     turnDevice(device.deviceNetworkId, evt.value)
}

def installed() {
	initialize()
}

def updated() {
	initialize()
}

def initialize() {
	log.debug "initialize----------"
    unschedule()
    unsubscribe()
    if(!atomicState.token) {
    	
    } else {
    	addDevices()
        runEvery1Minute("poll")
        log.debug "run every 5 minutes"
    }
    

}

def login() {
	def params = [
    	uri: "https://wap.tplinkcloud.com",
        body: [
            method: "login",
            params: [
                cloudUserName: settings.username,
                appType: "Kasa_iOS",
                terminalUUID: atomicState.terminalUUID,
                cloudPassword: settings.password
            ]
        ]
    ]
    try {
        httpPostJson(params) { resp ->
        	def jsonSlurper = new JsonSlurper()
			def data = jsonSlurper.parse(resp.data)
            if(data.error_code == 0) {
            	atomicState.token = data.result.token
                return true
            } else {
            	log.debug data
                return false
            }
        }
    } catch (e) {
        log.debug "something went wrong: $e"
        return false
    }
}
void poll() {
	updateDeviceState()
}
Map getDeviceList() {
	log.debug "getDeviceList"
	def params = [
    	uri: "https://wap.tplinkcloud.com/?token=${atomicState.token}",
        body: [
            method: "getDeviceList"
        ]
    ]
    try {
        httpPostJson(params) { resp ->
        	def jsonSlurper = new JsonSlurper()
			def data = jsonSlurper.parse(resp.data)
            def map = [:]
            if(data.error_code == 0) {
            	data.result.deviceList.each {
                	def key = "${it.deviceId}"
                    map["${key}"] = [
                    	deviceName: it.deviceName,
                        deviceType: it.deviceType,
                        status: it.status,
                        name: it.alias,
                        appServerUrl: it.appServerUrl,
                        deviceMac: it.deviceMac
                    ]
                }
            } else {
            	log.debug data
            }
            atomicState.devices = map
            map
        }
    } catch (e) {
        log.debug "something went wrong: $e"
    }
}
def turnDevice(deviceid, state) {
	def device = atomicState.devices.find { it.key == deviceid }
    if(device != null) {
    	device = device.value
    	def params = [
            uri: "${device.appServerUrl}/?token=${atomicState.token}",
            body: [
                method: "passthrough",
                params: [
                    requestData: '{\"system\":{\"set_relay_state\":{\"state\":' + (state == 'on' ? 1 : 0) + '}}}}',
                    deviceId : deviceid
                ]
            ]
        ]
        //log.debug JsonOutput.toJson(params)
        try {
            httpPostJson(params) { resp ->
                def jsonSlurper = new JsonSlurper()
                def data = jsonSlurper.parse(resp.data)
                //log.debug "registerDeviceType: response data: ${resp.data}"
                if(data.error_code == 0) {
                    
                } else {
                    log.debug data
                }
            }
        } catch (e) {
            log.debug "something went wrong: $e"
        }
    }
}
def getDeviceState(deviceId) {
	def device = atomicState.devices[deviceId]
    if(device != null) {
        def params = [
            uri: "${device.appServerUrl}/?token=${atomicState.token}",
            body: [
                method: "passthrough",
                params: [
                    requestData: '{\"system\":{\"get_sysinfo\":{}}}',
                    deviceId : deviceId
                ]
            ]
        ]
        try {
            httpPostJson(params) { resp ->
                def jsonSlurper = new JsonSlurper()
                def data = jsonSlurper.parse(resp.data)
                if(data.error_code == 0) {
                    data = jsonSlurper.parseText(data.result.responseData)
                    return data.system.get_sysinfo.relay_state == 0 ? 'off' : 'on'
                } else {
                	return 'offline'
                }
            }
        } catch (e) {
            log.debug "something went wrong: $e"
            return 'offline'
        }
    }
}
def updateDeviceState() {
	log.debug "updating device state"
	getChildDevices().each {
    	def deviceNetworkId = it.deviceNetworkId
    	def device = atomicState.devices[deviceNetworkId]
        if(device != null) {
            def newSwitchState = getDeviceState(deviceNetworkId)
            if (newSwitchState != it.currentSwitch) {
            	it.sendEvent(name: "switch", value: newSwitchState)
            }            
        }    	   	
    }
}