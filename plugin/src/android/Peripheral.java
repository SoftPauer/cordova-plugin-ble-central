// (c) 2104 Don Coleman
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.megster.cordova.ble.central;

import android.app.Activity;

import android.bluetooth.*;
import org.apache.cordova.CallbackContext;
import org.apache.cordova.LOG;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Peripheral wraps the BluetoothDevice and provides methods to convert to JSON.
 */
public class Peripheral extends BluetoothGattCallback {

    // 0x2902 org.bluetooth.descriptor.gatt.client_characteristic_configuration.xml
    public final static UUID CLIENT_CHARACTERISTIC_CONFIGURATION_UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB");
    private static final String TAG = "Peripheral";

    private BluetoothDevice device;
    private byte[] advertisingData;
    private int advertisingRSSI;
    private boolean connected = false;
    private ConcurrentLinkedQueue<BLECommand> commandQueue = new ConcurrentLinkedQueue<BLECommand>();
    private boolean bleProcessing;

    BluetoothGatt gatt;

    private CallbackContext connectCallback;
    private CallbackContext readCallback;
    private CallbackContext writeCallback;

    private Map<String, CallbackContext> notificationCallbacks = new HashMap<String, CallbackContext>();

    public Peripheral(BluetoothDevice device, int advertisingRSSI, byte[] scanRecord) {

        this.device = device;
        this.advertisingRSSI = advertisingRSSI;
        this.advertisingData = scanRecord;

    }

    public void connect(CallbackContext callbackContext, Activity activity) {
        BluetoothDevice device = getDevice();
        connectCallback = callbackContext;
        gatt = device.connectGatt(activity, false, this);

        PluginResult result = new PluginResult(PluginResult.Status.NO_RESULT);
        result.setKeepCallback(true);
        callbackContext.sendPluginResult(result);
    }

    public void disconnect() {
        connectCallback = null;
        connected = false;
        if (gatt != null) {
            gatt.close();
            gatt = null;
        }
    }

    public JSONObject asJSONObject()  {

        JSONObject json = new JSONObject();

        try {
            json.put("name", device.getName());
            json.put("id", device.getAddress()); // mac address
            json.put("advertising", byteArrayToJSON(advertisingData));
            // TODO real RSSI if we have it, else
            json.put("rssi", advertisingRSSI);
        } catch (JSONException e) { // this shouldn't happen
            e.printStackTrace();
        }

        return json;
    }

    static JSONArray byteArrayToJSON(byte[] bytes) {
        JSONArray json = new JSONArray();
        for (byte aByte : bytes) {
            json.put(aByte);
        }
        return json;
    }

    public boolean isConnected() {
        return connected;
    }

    public BluetoothDevice getDevice() {
        return device;
    }

    @Override
    public void onServicesDiscovered(BluetoothGatt gatt, int status) {
        super.onServicesDiscovered(gatt, status);

        if (status == BluetoothGatt.GATT_SUCCESS) {
            PluginResult result = new PluginResult(PluginResult.Status.OK);
            result.setKeepCallback(true);
            connectCallback.sendPluginResult(result);
        } else {
            connectCallback.error("Service discovery failed. status = " + status);
            disconnect();
        }
    }

    @Override
    public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {

        this.gatt = gatt;

        if (newState == BluetoothGatt.STATE_CONNECTED) {

            connected = true;
            gatt.discoverServices();

        } else {

            connected = false;
            if (connectCallback != null) {
                connectCallback.error("Disconnected");
                connectCallback = null;
            }
        }

    }

    @Override
    public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
        super.onCharacteristicChanged(gatt, characteristic);
        LOG.d(TAG, "onCharacteristicChanged " + characteristic);

        CallbackContext callback = notificationCallbacks.get(generateHashKey(characteristic));

        if (callback != null) {
            PluginResult result = new PluginResult(PluginResult.Status.OK, characteristic.getValue());
            result.setKeepCallback(true);
            callback.sendPluginResult(result);
        }
    }

    @Override
    public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
        super.onCharacteristicRead(gatt, characteristic, status);
        LOG.d(TAG, "onCharacteristicRead " + characteristic);

        if (readCallback != null) {

            if (status == BluetoothGatt.GATT_SUCCESS) {
                readCallback.success(characteristic.getValue());
            } else {
                readCallback.error("Error reading " + characteristic.getUuid() + " status=" + status);
            }

            readCallback = null;

        }

        commandCompleted();
    }

    @Override
    public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
        super.onCharacteristicWrite(gatt, characteristic, status);
        LOG.d(TAG, "onCharacteristicWrite " + characteristic);
        // for reliable writes we're supposed to compare the peripheral value to our desired value and confirm it is correct. RTFM.

        if (writeCallback != null) {

            if (status == BluetoothGatt.GATT_SUCCESS) {
                writeCallback.success();
            } else {
                writeCallback.error(status);
            }

            writeCallback = null;

        }

        commandCompleted();
    }

//    @Override
//    public void onDescriptorRead(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
//        super.onDescriptorRead(gatt, descriptor, status);
//    }

    @Override
    public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
        super.onDescriptorWrite(gatt, descriptor, status);
        LOG.d(TAG, "onDescriptorWrite " + descriptor);
        commandCompleted();
    }


//    It looks like this is for batch writes, started with beginReliableWrite(), setCharacteristic(), ... executeReliableWrite();
//    @Override
//    public void onReliableWriteCompleted(BluetoothGatt gatt, int status) {
//        super.onReliableWriteCompleted(gatt, status);
//        LOG.d(TAG, "onReliableWriteCompleted");
//
//        if (writeCallback != null) {
//
//            if (status == BluetoothGatt.GATT_SUCCESS) {
//                writeCallback.success();
//            } else {
//                writeCallback.error(status);
//            }
//
//            writeCallback = null;
//
//        }
//
//        commandCompleted();
//    }

    public void updateRssi(int rssi) {
        advertisingRSSI = rssi;
    }

    // This seems way too complicated
    private void registerNotifyCallback(CallbackContext callbackContext, UUID serviceUUID, UUID characteristicUUID) {

        boolean success = false;

        try {
            if (gatt == null) {
                callbackContext.error("BluetoothGatt is null");
                return;
            }

            BluetoothGattService service = gatt.getService(serviceUUID);
            BluetoothGattCharacteristic characteristic = service.getCharacteristic(characteristicUUID);
            String key = generateHashKey(serviceUUID, characteristic);

            if (characteristic != null) {

                notificationCallbacks.put(key, callbackContext);

                if (gatt.setCharacteristicNotification(characteristic, true)) {

                    // Why doesn't setCharacteristicNotification write the descriptor?
                    BluetoothGattDescriptor descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIGURATION_UUID);
                    if (descriptor != null) {
                        descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);

                        if (gatt.writeDescriptor(descriptor)) {
                            success = true;
                        } else {
                            callbackContext.error("Failed to set client characteristic notification for " + characteristicUUID);
                        }

                    } else {
                        callbackContext.error("Set notification failed for " + characteristicUUID);
                    }

                } else {
                    callbackContext.error("Failed to register notification for " + characteristicUUID);
                }

            } else {
                callbackContext.error("Characteristic " + characteristicUUID + " not found");
            }
        } finally {
            if (!success) {
                commandCompleted();
            }
        }
    }

    private void readCharacteristic(CallbackContext callbackContext, UUID serviceUUID, UUID characteristicUUID) {

        boolean success = false;

        try {
            if (gatt == null) {
                callbackContext.error("BluetoothGatt is null");
                return;
            }

            BluetoothGattService service = gatt.getService(serviceUUID);
            BluetoothGattCharacteristic characteristic = service.getCharacteristic(characteristicUUID);

            if (characteristic == null) {
                callbackContext.error("Characteristic " + characteristicUUID + " not found.");
            } else {

                readCallback = callbackContext;

                if (gatt.readCharacteristic(characteristic)) {

                    success = true;

                } else {

                    readCallback = null;
                    callbackContext.error("Read failed");

                }
            }
        } finally {

            if (!success) {
                commandCompleted();
            }
        }

    }

    // TODO probably combine with the other write
    // BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
    private void writeNoResponse(CallbackContext callbackContext, UUID serviceUUID, UUID characteristicUUID, byte[] data) {

        boolean success = false;

        try {

            if (gatt == null) {
                callbackContext.error("BluetoothGatt is null");
                return;
            }

            BluetoothGattService service = gatt.getService(serviceUUID);
            BluetoothGattCharacteristic characteristic = service.getCharacteristic(characteristicUUID);

            if (characteristic == null) {
                callbackContext.error("Characteristic " + characteristicUUID + " not found.");
            } else {
                characteristic.setValue(data);
                characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);

                if (gatt.writeCharacteristic(characteristic)) {
                    writeCallback = callbackContext;
                    success = true;
                } else {
                    callbackContext.error("Write failed");
                }
            }

        } finally {
            if (!success) {
                commandCompleted();
            }
        }

    }

    // BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
    private void writeCharacteristic(CallbackContext callbackContext, UUID serviceUUID, UUID characteristicUUID, byte[] data) {

        if (gatt == null) {
            callbackContext.error("BluetoothGatt is null");
            return;
        }

        boolean success = false;

        BluetoothGattService service = gatt.getService(serviceUUID);
        BluetoothGattCharacteristic characteristic = service.getCharacteristic(characteristicUUID);

        if (characteristic == null) {
            callbackContext.error("Characteristic " + characteristicUUID + " not found.");
        } else {
            characteristic.setValue(data);
            characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);

            if (gatt.writeCharacteristic(characteristic)) {
                writeCallback = callbackContext;
                success = true;
            } else {
                callbackContext.error("Write failed");
            }
        }

        if (!success) {
            commandCompleted();
        }

    }

    public void queueRead(CallbackContext callbackContext, UUID serviceUUID, UUID characteristicUUID) {
        BLECommand command = new BLECommand(callbackContext, serviceUUID, characteristicUUID, BLECommand.READ);
        queueCommand(command);
    }

    public void queueWrite(CallbackContext callbackContext, UUID serviceUUID, UUID characteristicUUID, byte[] data, int writeType) {
        BLECommand command = new BLECommand(callbackContext, serviceUUID, characteristicUUID, data, writeType);
        queueCommand(command);
    }

    public void queueRegisterNotifyCallback(CallbackContext callbackContext, UUID serviceUUID, UUID characteristicUUID) {
        BLECommand command = new BLECommand(callbackContext, serviceUUID, characteristicUUID, BLECommand.REGISTER_NOTIFY);
        queueCommand(command);
    }

    // add a new command to the queue
    private void queueCommand(BLECommand command) {
        LOG.d(TAG,"Queuing Command " + command);
        commandQueue.add(command);

        PluginResult result = new PluginResult(PluginResult.Status.NO_RESULT);
        result.setKeepCallback(true);
        command.getCallbackContext().sendPluginResult(result);

        if (!bleProcessing) {
            LOG.d(TAG,"BLE Available. Processing.");
            processCommands();
        } else {
            LOG.d(TAG,"BLE is busy. Not processing.");
        }
    }

    // command finished, queue the next command
    private void commandCompleted() {
        LOG.d(TAG,"Processing Complete");
        bleProcessing = false;
        processCommands();
    }

    // process the queue
    private void processCommands() {
        LOG.d(TAG,"Processing Commands");

        if (bleProcessing) { return; }

        BLECommand command = commandQueue.poll();
        if (command != null) {
            if (command.getType() == BLECommand.READ) {
                LOG.d(TAG,"Read " + command.getCharacteristicUUID());
                bleProcessing = true;
                readCharacteristic(command.getCallbackContext(), command.getServiceUUID(), command.getCharacteristicUUID());
            } else if (command.getType() == BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) {
                LOG.d(TAG,"Write " + command.getCharacteristicUUID());
                bleProcessing = true;
                writeCharacteristic(command.getCallbackContext(), command.getServiceUUID(), command.getCharacteristicUUID(), command.getData());
            } else if (command.getType() == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) {
                LOG.d(TAG,"Write No Response " + command.getCharacteristicUUID());
                bleProcessing = true;
                writeNoResponse(command.getCallbackContext(), command.getServiceUUID(), command.getCharacteristicUUID(), command.getData());
            } else if (command.getType() == BLECommand.REGISTER_NOTIFY) {
                LOG.d(TAG,"Register Notify " + command.getCharacteristicUUID());
                bleProcessing = true;
                registerNotifyCallback(command.getCallbackContext(), command.getServiceUUID(), command.getCharacteristicUUID());
            } else {
                // this shouldn't happen
                throw new RuntimeException("Unexpected BLE Command type " + command.getType());
            }
        } else {
            LOG.d(TAG, "Command Queue is empty.");
        }

    }

    private String generateHashKey(BluetoothGattCharacteristic characteristic) {
        return generateHashKey(characteristic.getService().getUuid(), characteristic);
    }

    private String generateHashKey(UUID serviceUUID, BluetoothGattCharacteristic characteristic) {
        return String.valueOf(serviceUUID) + "|" + characteristic.getUuid() + "|" + characteristic.getInstanceId();
    }

}
