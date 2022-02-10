/*

Copyright 2021 Microoled
Licensed under the Apache License, Version 2.0 (the “License”);
you may not use this file except in compliance with the License.
You may obtain a copy of the License at
    http://www.apache.org/licenses/LICENSE-2.0
Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an “AS IS” BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.

*/
package com.activelook.activelooksdk.core.ble;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.util.Log;

import androidx.core.util.Consumer;

import com.activelook.activelooksdk.Glasses;
import com.activelook.activelooksdk.core.Command;
import com.activelook.activelooksdk.types.DeviceInformation;
import com.activelook.activelooksdk.types.FlowControlStatus;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import timber.log.Timber;

class GlassesGattCallbackImpl extends GlassesGatt {

    private final DeviceInformation deviceInfo;
    private final ConcurrentLinkedDeque<byte []> pendingWriteRxCharacteristic;
    private final AtomicBoolean flowControlCanSend;
    private final AtomicBoolean isWritingCommand;
    private final BluetoothGatt gatt;
    private int mtu;
    //TODO  possède une Glasses
    private GlassesImpl glasses;
    private Consumer<GlassesImpl> onConnected;
    private Consumer<Glasses> onDisconnected;
    private Runnable onConnectionFail;
    private byte[] pendingBuffer;
    private Consumer<Integer> onBatteryLevelEvent;
    private Consumer<FlowControlStatus> onFlowControlEvent;
    private Runnable onSensorInterfaceEvent;
    private ScheduledFuture<?> repairFlowControl;

    GlassesGattCallbackImpl(BluetoothDevice device, GlassesImpl bleGlasses,
                            Consumer<GlassesImpl> onConnected,
                            Runnable onConnectionFail,
                            Consumer<Glasses> onDisconnected) {
        super(BleSdkSingleton.getInstance().getContext(), device, true);
        this.deviceInfo = new DeviceInformation();
        this.pendingWriteRxCharacteristic = new ConcurrentLinkedDeque<>();
        this.flowControlCanSend = new AtomicBoolean(true);
        this.isWritingCommand = new AtomicBoolean(false);
        this.mtu = 23;
        this.glasses = bleGlasses;
        this.onBatteryLevelEvent = null;
        this.onFlowControlEvent = null;
        this.onSensorInterfaceEvent = null;
        this.repairFlowControl = null;
        final SdkImpl sdk = BleSdkSingleton.getInstance();
        this.setOnConnect(onConnected);
        this.setOnConnectionFail(onConnectionFail);
        this.setOnDisconnected(onDisconnected);
        this.gatt = this.gattDelegate;
        sdk.registerConnectedGlasses(this.glasses);
    }

    private void optimizeMtu(final int optimalMtu) {
        if (optimalMtu > this.mtu) {
            if (!this.gatt.requestMtu(optimalMtu)) {
                this.optimizeMtu(optimalMtu - 1);
            }
        } else if (!this.gatt.discoverServices()) {
            this.optimizeMtu(optimalMtu);
        }
    }

    private void logStatusStateChanged(int status) {

        switch (status) {
            case BluetoothStatusCodes
                    .SUCCESS: Log.e("logStatusStateChanged", "SUCCESS");
                break;
            case BluetoothStatusCodes
                    .ERROR_BLUETOOTH_NOT_ALLOWED: Log.e("logStatusStateChanged", "ERROR_BLUETOOTH_NOT_ALLOWED");
                break;
            case BluetoothStatusCodes
                    .ERROR_BLUETOOTH_NOT_ENABLED: Log.e("logStatusStateChanged", "ERROR_BLUETOOTH_NOT_ENABLED");
                break;
            case BluetoothStatusCodes
                    .ERROR_DEVICE_NOT_BONDED: Log.e("logStatusStateChanged", "ERROR_DEVICE_NOT_BONDED");
                break;
            case BluetoothStatusCodes
                    .ERROR_MISSING_BLUETOOTH_CONNECT_PERMISSION: Log.e("logStatusStateChanged", "ERROR_MISSING_BLUETOOTH_CONNECT_PERMISSION");
                break;
            case BluetoothStatusCodes
                    .ERROR_UNKNOWN: Log.e("logStatusStateChanged", "ERROR_UNKNOWN");
                break;
            default:
                Log.e("logStatusStateChanged", "ERROR DEFAULT");
                break;
        }
    }


    @Override
    public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
        super.onConnectionStateChange(gatt, status, newState);

        logStatusStateChanged(status);

        if (newState == BluetoothProfile.STATE_CONNECTED) {
            this.optimizeMtu(512);
        } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
            if (this.onConnectionFail != null) {
                this.onConnectionFail.run();
            } else if (this.onDisconnected != null) {
                this.onDisconnected.accept(this.glasses);
            }
            this.disconnect();
        }
    }

    @Override
    public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
        super.onMtuChanged(gatt, mtu, status);
        if (status == BluetoothGatt.GATT_SUCCESS) {
            this.mtu = mtu;
        }
        this.optimizeMtu(mtu);
    }

    @Override
    public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
        super.onCharacteristicRead(gatt, characteristic, status);
        final BluetoothGattService diService = this.gatt.getService(BleUUID.DeviceInformationService);
        if (characteristic.getUuid().equals(BleUUID.ManufacturerNameCharacteristic)) {
            this.deviceInfo.setManufacturerName(
                    new String(characteristic.getValue(), StandardCharsets.UTF_8));
            this.gatt.readCharacteristic(diService.getCharacteristic(BleUUID.ModelNumberCharacteristic));
        } else if (characteristic.getUuid().equals(BleUUID.ModelNumberCharacteristic)) {
            this.deviceInfo.setModelNumber(
                    new String(characteristic.getValue(), StandardCharsets.UTF_8));
            this.gatt.readCharacteristic(diService.getCharacteristic(BleUUID.SerialNumberCharacteristic));
        } else if (characteristic.getUuid().equals(BleUUID.SerialNumberCharacteristic)) {
            this.deviceInfo.setSerialNumber(
                    new String(characteristic.getValue(), StandardCharsets.UTF_8));
            this.gatt.readCharacteristic(diService.getCharacteristic(BleUUID.HardwareVersionCharacteristic));
        } else if (characteristic.getUuid().equals(BleUUID.HardwareVersionCharacteristic)) {
            this.deviceInfo.setHardwareVersion(
                    new String(characteristic.getValue(), StandardCharsets.UTF_8));
            this.gatt.readCharacteristic(diService.getCharacteristic(BleUUID.FirmwareVersionCharacteristic));
        } else if (characteristic.getUuid().equals(BleUUID.FirmwareVersionCharacteristic)) {
            this.deviceInfo.setFirmwareVersion(
                    new String(characteristic.getValue(), StandardCharsets.UTF_8));
            this.gatt.readCharacteristic(diService.getCharacteristic(BleUUID.SoftwareVersionCharacteristic));
        } else if (characteristic.getUuid().equals(BleUUID.SoftwareVersionCharacteristic)) {
            this.deviceInfo.setSoftwareVersion(
                    new String(characteristic.getValue(), StandardCharsets.UTF_8));
            this.setOnConnectionFail(null);
            if (this.onConnected != null) {
                this.onConnected.accept(this.glasses);
            }
        }
    }

    @Override
    public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
        //super.onCharacteristicWrite(gatt, characteristic, status);
        StringBuilder message = new StringBuilder();
        for (Byte b : characteristic.getValue()) {
            message.append(String.format("%02X", b));
        }
        Timber.e("SENT GlassesCallbackImpl %s", message);
        messageLog.postValue("SENT GlassesCallbackImpl:" + message + getDevice());
        gatt.readRemoteRssi();
        if (characteristic.equals(this.getRxCharacteristic())) {
            this.isWritingCommand.set(false);
            this.unstackWriteRxCharacteristic();
        }
    }

    @Override
    public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
        //super.onCharacteristicChanged(gatt, characteristic);

        StringBuilder message = new StringBuilder();
        for (Byte b : characteristic.getValue()) {
            message.append(String.format("%02X", b));
        }

        Timber.e("RECEIVED GlassesCallbackImpl %s", message);
        messageLog.postValue("RECEIVED GlassesCallbackImpl: " + message);

        gatt.readRemoteRssi();

        if (characteristic.getUuid().equals(BleUUID.ActiveLookTxCharacteristic)) {
            byte[] buffer = characteristic.getValue();
            if (this.pendingBuffer != null) {
                this.addPendingBuffer(buffer);
                buffer = this.pendingBuffer;
                if (Command.isValidBuffer(buffer)) {
                    this.pendingBuffer = null;
                    final Command command = new Command(buffer);
                    this.glasses.callCallback(command);
                }
            } else if (Command.isValidBuffer(buffer)) {
                final Command command = new Command(buffer);
                this.glasses.callCallback(command);
            } else {
                this.addPendingBuffer(buffer);
            }
        } else if (characteristic.getUuid().equals(BleUUID.BatteryLevelCharacteristic)) {
            if (this.onBatteryLevelEvent != null) {
                //TODO voir pour ajouter un log specific pour les consumer ici onBatteryLevelEvent
                
                this.onBatteryLevelEvent.accept((int) characteristic.getValue()[0]);
            }
        } else if (characteristic.getUuid().equals(BleUUID.ActiveLookSensorInterfaceCharacteristic)) {
            if (this.onSensorInterfaceEvent != null) {
                this.onSensorInterfaceEvent.run();
            }
        } else if (characteristic.getUuid().equals(BleUUID.ActiveLookFlowControlCharacteristic)) {
            final byte state = characteristic.getValue()[0];
            if (state == (byte) 0x01) {
                if (this.repairFlowControl != null) {
                    this.repairFlowControl.cancel(false);
                    this.repairFlowControl = null;
                }
                if (this.flowControlCanSend.compareAndSet(false, true)) {
                    this.unstackWriteRxCharacteristic();
                }
            } else if (state == (byte) 0x02) {
                this.flowControlCanSend.set(false);
                if (this.repairFlowControl != null) {
                    this.repairFlowControl.cancel(true);
                }
                final ScheduledExecutorService executorService = Executors.newSingleThreadScheduledExecutor();
                this.repairFlowControl = executorService.schedule(() -> {
                    GlassesGattCallbackImpl.this.repairFlowControl = null;
                    if (GlassesGattCallbackImpl.this.flowControlCanSend.compareAndSet(false, true)) {
                        GlassesGattCallbackImpl.this.unstackWriteRxCharacteristic();
                    }
                }, 1000, TimeUnit.MILLISECONDS);
            } else if (this.onFlowControlEvent != null) {
                if (state == (byte) 0x03) {
                    this.onFlowControlEvent.accept(FlowControlStatus.CMD_ERROR);
                } else if (state == (byte) 0x04) {
                    this.onFlowControlEvent.accept(FlowControlStatus.OVERFLOW);
                } else if (state == (byte) 0x06) {
                    this.onFlowControlEvent.accept(FlowControlStatus.MISSING_CONFIG_ID);
                } else { // if (state == (byte) 0x05) {
                    this.onFlowControlEvent.accept(FlowControlStatus.RESERVED);
                }
            }
        } else {
            Log.w("onCharacteristicChanged", String.format("%s: %s", characteristic.getUuid(), Command.bytesToStr(characteristic.getValue())));
        }
    }

    @Override
    public void onDescriptorRead(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
        super.onDescriptorRead(gatt, descriptor, status);
    }

    @Override
    public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
        super.onDescriptorWrite(gatt, descriptor, status);
        if (descriptor.getCharacteristic().getUuid().equals(BleUUID.ActiveLookFlowControlCharacteristic)) {
            this.activateNotification(this.getTxCharacteristic());
        } else if (descriptor.getCharacteristic().getUuid().equals(BleUUID.ActiveLookTxCharacteristic)) {
            this.activateNotification(this.getUiCharacteristic());
        } else if (descriptor.getCharacteristic().getUuid().equals(BleUUID.ActiveLookUICharacteristic)) {
            this.activateNotification(this.getBatteryCharacteristic());
        } else if (descriptor.getCharacteristic().getUuid().equals(BleUUID.BatteryLevelCharacteristic)) {
            this.activateNotification(this.getSensorCharacteristic());
        } else if (descriptor.getCharacteristic().getUuid().equals(BleUUID.ActiveLookSensorInterfaceCharacteristic)) {
            final BluetoothGattService diService = this.gatt.getService(BleUUID.DeviceInformationService);
            this.gatt.readCharacteristic(diService.getCharacteristic(BleUUID.ManufacturerNameCharacteristic));
        }
    }

    @Override
    public void onServicesDiscovered(BluetoothGatt gatt, int status) {
        super.onServicesDiscovered(gatt, status);
        if (status == BluetoothGatt.GATT_SUCCESS) {
            this.activateNotification(this.getFlowControlCharacteristic());
        }
    }

    /*
    Package protected
     */

    void updateRef(GlassesImpl bleGlasses) {
        this.glasses = bleGlasses;
    }

    void setOnDisconnected(Consumer<Glasses> onDisconnected) {
        this.onDisconnected = onDisconnected;
    }

    void setOnConnectionFail(Runnable onConnectionFail) {
        this.onConnectionFail = onConnectionFail;
    }

    void setOnConnect(Consumer<GlassesImpl> onConnected) {
        this.onConnected = onConnected;
    }

    void writeRxCharacteristic(byte[] bytes) {
        this.pendingWriteRxCharacteristic.add(bytes);
        this.unstackWriteRxCharacteristic();
    }

    synchronized void unstackWriteRxCharacteristic() {
        if (this.flowControlCanSend.get() && this.pendingWriteRxCharacteristic.size() > 0 && this.isWritingCommand.compareAndSet(false, true)) {
            final ConcurrentLinkedDeque<byte []> stack = new ConcurrentLinkedDeque<>();
            final int writeMTU = this.mtu - 3;
            int stackSize = 0;
            while (stackSize < writeMTU && this.pendingWriteRxCharacteristic.size() > 0 && stack.size() < 1) {
                final byte [] buffer = this.pendingWriteRxCharacteristic.poll();
                if (buffer.length > 0) {
                    stack.add(buffer);
                    stackSize += buffer.length;
                }
            }
            final byte [] payload;
            if (stackSize > writeMTU) {
                payload = new byte [writeMTU];
                final int sizeOutOfPayload = stackSize - writeMTU;
                final byte[] remainingBuffer = new byte [sizeOutOfPayload];
                final byte[] incompleteBuffer = stack.pollLast();
                final int sizeInPayload = incompleteBuffer.length - sizeOutOfPayload;
                final int incompleteBufferOffset = writeMTU - sizeInPayload;
                System.arraycopy(incompleteBuffer, 0, payload, incompleteBufferOffset, sizeInPayload);
                System.arraycopy(incompleteBuffer, sizeInPayload, remainingBuffer, 0, sizeOutOfPayload);
                this.pendingWriteRxCharacteristic.addFirst(remainingBuffer);
            } else {
                payload = new byte [stackSize];
            }
            int offset = 0;
            while (!stack.isEmpty()) {
                final byte [] buffer = stack.poll();
                System.arraycopy(buffer, 0, payload, offset, buffer.length);
                offset += buffer.length;
            }
            if (this.flowControlCanSend.get()) {
                if (!this.getRxCharacteristic().setValue(payload)) {
                    this.pendingWriteRxCharacteristic.addFirst(payload);
                    this.isWritingCommand.set(false);
                    this.unstackWriteRxCharacteristic();
                } else {
                    this.getRxCharacteristic().setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                    if (!this.gatt.writeCharacteristic(this.getRxCharacteristic())) {
                        this.pendingWriteRxCharacteristic.addFirst(payload);
                        this.isWritingCommand.set(false);
                        this.unstackWriteRxCharacteristic();
                    }
                }
            } else {
                this.pendingWriteRxCharacteristic.addFirst(payload);
                this.isWritingCommand.set(false);
                this.unstackWriteRxCharacteristic();
            }
        }
    }

    void disconnect() {
        this.gatt.disconnect();
        this.gatt.close();
        BleSdkSingleton.getInstance().unregisterConnectedGlasses(this.glasses);
    }

    /*
    Helpers
     */
    private void addPendingBuffer(final byte[] buffer) {
        if (this.pendingBuffer == null) {
            this.pendingBuffer = buffer;
        } else {
            byte[] newPending = new byte[this.pendingBuffer.length + buffer.length];
            System.arraycopy(this.pendingBuffer, 0, newPending, 0, this.pendingBuffer.length);
            System.arraycopy(buffer, 0, newPending, this.pendingBuffer.length, buffer.length);
            this.pendingBuffer = newPending;
        }
    }

    private void activateNotification(final BluetoothGattCharacteristic characteristic) {
        this.gatt.setCharacteristicNotification(characteristic, true);
        final BluetoothGattDescriptor descriptor = characteristic.getDescriptor(BleUUID.BleNotificationDescriptor);
        descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
        this.gatt.writeDescriptor(descriptor);
    }

    private BluetoothGattService getCommandInterfaceService() {
        return this.gatt.getService(BleUUID.ActiveLookCommandsInterfaceService);
    }

    private BluetoothGattCharacteristic getFlowControlCharacteristic() {
        return this.getCommandInterfaceService().getCharacteristic(BleUUID.ActiveLookFlowControlCharacteristic);
    }

    private BluetoothGattCharacteristic getTxCharacteristic() {
        return this.getCommandInterfaceService().getCharacteristic(BleUUID.ActiveLookTxCharacteristic);
    }

    private BluetoothGattCharacteristic getUiCharacteristic() {
        return this.getCommandInterfaceService().getCharacteristic(BleUUID.ActiveLookUICharacteristic);
    }

    private BluetoothGattCharacteristic getSensorCharacteristic() {
        return this.getCommandInterfaceService().getCharacteristic(BleUUID.ActiveLookSensorInterfaceCharacteristic);
    }

    private BluetoothGattCharacteristic getRxCharacteristic() {
        return this.getCommandInterfaceService().getCharacteristic(BleUUID.ActiveLookRxCharacteristic);
    }

    private BluetoothGattCharacteristic getBatteryCharacteristic() {
        return this.gatt.getService(BleUUID.BatteryService).getCharacteristic(BleUUID.BatteryLevelCharacteristic);
    }

    public DeviceInformation getDeviceInformation() {
        return this.deviceInfo;
    }

    public void subscribeToBatteryLevelNotifications(Consumer<Integer> onEvent) {
        this.onBatteryLevelEvent = onEvent;
    }

    public void subscribeToFlowControlNotifications(Consumer<FlowControlStatus> onEvent) {
        this.onFlowControlEvent = onEvent;
    }

    public void subscribeToSensorInterfaceNotifications(Runnable onEvent) {
        this.onSensorInterfaceEvent = onEvent;
    }

    @Override
    public void onReadRemoteRssi(BluetoothGatt gatt, int rssi, int status){
        if (status == BluetoothGatt.GATT_SUCCESS) {
            Timber.e(String.format("BluetoothGatt ReadRssi[%d]", rssi));
            messageLog.postValue("RSSI" + rssi);
        }
    }
}
