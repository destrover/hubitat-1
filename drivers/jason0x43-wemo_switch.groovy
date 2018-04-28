/**
 * WeMo Switch driver
 *
 * Author: Jason Cheatham
 * Last updated: 2018-04-28, 12:32:25-0400
 *
 * Based on the original Wemo Switch driver by Juan Risso at SmartThings,
 * 2015-10-11.
 *
 * Copyright 2015 SmartThings
 *
 * Licensed under the Apache License, Version 2.0 (the 'License'); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at:
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 * on an 'AS IS' BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 * for the specific language governing permissions and limitations under the License.
 */

metadata {
    definition(
        name: 'Wemo Switch',
        namespace: 'jason0x43',
        author: 'Jason Cheatham'
    ) {
        capability 'Actuator'
        capability 'Switch'
        capability 'Polling'
        capability 'Refresh'
        capability 'Sensor'

        command 'subscribe'
        command 'unsubscribe'
        command 'resubscribe'
    }
}

def on() {
    log.debug 'on()'
    setBinaryState('1')
}

def off() {
    log.debug 'off()'
    setBinaryState('0')
}

def parse(description) {
    log.trace "Parsing '${description}'"

    def msg = parseLanMessage(description)
    def headerString = msg.header

    if (headerString?.contains('SID: uuid:')) {
        log.trace 'Header string: ' + headerString
        def sid = (headerString =~ /SID: uuid:.*/) ?
            (headerString =~ /SID: uuid:.*/)[0] :
            '0'
        sid -= 'SID: uuid:'.trim()
        log.trace 'Updating subscriptionId to ' + sid
        updateDataValue('subscriptionId', sid)
     }

    def result = []
    def bodyString = msg.body
    if (bodyString) {
        try {
            unschedule('setOffline')
        } catch (e) {
            log.error 'unschedule("setOffline")'
        }

        log.trace 'body: ' + bodyString
        def body = new XmlSlurper().parseText(bodyString)
        
         if (body?.property?.TimeSyncRequest?.text()) {
            log.trace 'Got TimeSyncRequest'
            result << timeSyncResponse()
        } else if (body?.Body?.SetBinaryStateResponse?.BinaryState?.text()) {
            def rawValue = body.Body.SetBinaryStateResponse.BinaryState.text()
            log.trace "Got SetBinaryStateResponse = ${rawValue}"
            result << createBinaryStateEvent(rawValue)
        } else if (body?.property?.BinaryState?.text()) {
            def rawValue = body.property.BinaryState.text()
            log.trace "Notify: BinaryState = ${rawValue}"
            result << createBinaryStateEvent(rawValue)
        } else if (body?.property?.TimeZoneNotification?.text()) {
            log.debug "Notify: TimeZoneNotification = ${body.property.TimeZoneNotification.text()}"
        } else if (body?.Body?.GetBinaryStateResponse?.BinaryState?.text()) {
            def rawValue = body.Body.GetBinaryStateResponse.BinaryState.text()
            log.trace "GetBinaryResponse: BinaryState = ${rawValue}"
            result << createBinaryStateEvent(rawValue)
        }
    }

    result
}

def poll() {
    log.debug 'Executing poll()'
    if (device.currentValue('switch') != 'offline') {
        runIn(30, setOffline)
    }
    return new hubitat.device.HubSoapAction(
        path: '/upnp/control/basicevent1',
        urn: 'urn:Belkin:service:basicevent:1',
        action: 'GetBinaryState',
        headers: [
            HOST: getHostAddress()
        ]
    )
}

def refresh() {
     log.debug 'refresh()'
    [subscribe(), timeSyncResponse(), poll()]
}

def resubscribe() {
    log.debug 'resubscribe()'
    def sid = getDeviceDataByName('subscriptionId')
    new hubitat.device.HubAction(
        method: 'SUBSCRIBE',
        path: '/upnp/event/basicevent1',
        headers: [
            HOST: getHostAddress(),
            SID: "uuid:${sid}",
            TIMEOUT: "Second-${60 * (parent.interval ?: 5)}"
        ]
    )
}

def setOffline() {
    sendEvent(
        name: 'switch',
        value: 'offline',
        descriptionText: 'The device is offline'
    )
}

def subscribe() {
    log.debug 'subscribe()'
    new hubitat.device.HubAction(
        method: 'SUBSCRIBE',
        path: '/upnp/event/basicevent1',
        headers: [
            HOST: getHostAddress(),
            CALLBACK: "<http://${getCallBackAddress()}/>",
            NT: 'upnp:event',
            TIMEOUT: "Second-${60 * (parent.interval ?: 5)}"
        ]
    )
}

def sync(ip, port) {
    log.trace "Syncing to ${ip}:${port}"
    def existingIp = getDataValue('ip')
    def existingPort = getDataValue('port')

    if (ip && ip != existingIp) {
        log.trace "Updating IP from ${existingIp} to ${ip}"
        updateDataValue('ip', ip)
    }

    if (port && port != existingPort) {
        log.trace "Updating port from $existingPort to $port"
        updateDataValue('port', port)
    }

    subscribe()
}

def timeSyncResponse() {
    log.debug 'Executing timeSyncResponse()'
    new hubitat.device.HubSoapAction(
        path: '/upnp/control/timesync1',
        url: 'urn:Belkin:service:timesync:1',
        action: 'TimeSync',
        body: [
            //TODO: Use UTC Timezone
            UTC: getTime(),
            TimeZone: '-05.00',
            dst: 1,
            DstSupported: 1
        ],
        headers: [
            HOST: getHostAddress()
        ]
    )
}

def unsubscribe() {
    log.debug 'unsubscribe()'
    new hubitat.device.HubAction(
        method: 'UNSUBSCRIBE',
        path: '/upnp/event/basicevent1',
        headers: [
            HOST: getHostAddress(),
            SID: "uuid:${getDeviceDataByName('subscriptionId')}"
        ]
    )
}

def updated() {
	log.debug 'Updated'
    refresh()
}

private convertHexToInt(hex) {
     Integer.parseInt(hex,16)
}

private convertHexToIP(hex) {
     [
        convertHexToInt(hex[0..1]),
        convertHexToInt(hex[2..3]),
        convertHexToInt(hex[4..5]),
        convertHexToInt(hex[6..7])
    ].join('.')
}

private createBinaryStateEvent(rawValue) {
    def value = rawValue == '1' ? 'on' : 'off'
    createEvent(
        name: 'switch',
        value: value,
        descriptionText: "Switch is ${value}"
    )
}

private getCallBackAddress() {
    def localIp = device.hub.getDataValue('localIP')
    def localPort = device.hub.getDataValue('localSrvPortTCP')
    "${localIp}:${localPort}"
}

private getHostAddress() {
     def ip = getDataValue('ip')
     def port = getDataValue('port')

     if (!ip || !port) {
         def parts = device.deviceNetworkId.split(':')
         if (parts.length == 2) {
             ip = parts[0]
             port = parts[1]
         } else {
             log.warn "Can't figure out ip and port for device: ${device.id}"
         }
     }

     "${convertHexToIP(ip)}:${convertHexToInt(port)}"
}

private getTime() {
    // This is essentially System.currentTimeMillis()/1000, but System is
    // disallowed by the sandbox.
    ((new GregorianCalendar().time.time / 1000l).toInteger()).toString()
}

private setBinaryState(newState) {
    new hubitat.device.HubSoapAction(
        path: '/upnp/control/basicevent1',
        urn: 'urn:Belkin:service:basicevent:1',
        action: 'SetBinaryState',
        body: [
            BinaryState: newState
        ],
        headers: [
            Host: getHostAddress()
        ]
    )
}
