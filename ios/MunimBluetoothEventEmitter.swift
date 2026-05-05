//
//  MunimBluetoothEventEmitter.swift
//  munim-bluetooth
//
//  Event emitter for Bluetooth events
//

import Foundation
import React

@objc(MunimBluetoothEventEmitter)
class MunimBluetoothEventEmitter: RCTEventEmitter {
    
    public static var shared: MunimBluetoothEventEmitter?
    
    override init() {
        super.init()
        NSLog("[MunimBluetooth] MunimBluetoothEventEmitter init — previous shared was: %@", 
          MunimBluetoothEventEmitter.shared == nil ? "nil" : "EXISTS")
        MunimBluetoothEventEmitter.shared = self
        startObserving()
    }

    override func startObserving() {
        NSLog("[MunimBluetooth] startObserving called")
        super.startObserving()
    }

    override func stopObserving() {
        NSLog("[MunimBluetooth] stopObserving called")
        super.stopObserving()
    }
    
    override func supportedEvents() -> [String]! {
        return [
            "deviceFound",
            "onDeviceFound",
            "scanResult",
            "connectionStateChanged",
            "characteristicValueChanged",
            "peripheralStateChanged",
            "deviceConnected",
            "deviceDisconnected",
            "writeRequested",
        ]
    }
    
    override static func requiresMainQueueSetup() -> Bool {
        return false
    }

    static func emit(eventName: String, body: Any) {
        DispatchQueue.main.async {
            guard let emitter = MunimBluetoothEventEmitter.shared else {
                NSLog("[MunimBluetooth] ⚠️ Cannot emit %@ — shared emitter is nil", eventName)
                return
            }
            emitter.sendEvent(withName: eventName, body: body)
        }
    }
    
    func emitDeviceFound(_ deviceData: [String: Any]) {
        MunimBluetoothEventEmitter.emit(eventName: "deviceFound", body: deviceData)
    }
}