/**
 * Hexiwear application is used to pair with Hexiwear BLE devices
 * and send sensor readings to WolkSense sensor data cloud
 * <p>
 * Copyright (C) 2016 WolkAbout Technology s.r.o.
 * <p>
 * Hexiwear is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * <p>
 * Hexiwear is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * <p>
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.wolkabout.hexiwear.service;

import android.annotation.SuppressLint;
import android.app.Service;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;
import android.widget.Toast;

import com.wolkabout.hexiwear.R;
import com.wolkabout.hexiwear.model.Characteristic;
import com.wolkabout.hexiwear.model.ManufacturerInfo;
import com.wolkabout.hexiwear.model.Mode;
import com.wolkabout.hexiwear.util.DataConverter;

import org.androidannotations.annotations.EService;
import org.androidannotations.annotations.Receiver;
import org.androidannotations.annotations.UiThread;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;
import java.util.Queue;
import java.util.TimeZone;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;

/**
 * @author notzuonotdied
 */
@SuppressLint("Registered")
@EService
public class BluetoothService extends Service {

    public static final String SERVICES_AVAILABLE = "servicesAvailable";
    public static final String DATA_AVAILABLE = "dataAvailable";
    public static final String READING_TYPE = "readingType";
    public static final String CONNECTION_STATE_CHANGED = "ConnectionStateChange";
    public static final String CONNECTION_STATE = "connectionState";
    public static final String STRING_DATA = "stringData";
    public static final String STOP = "stop";
    public static final String ACTION_NEEDS_BOND = "noBond";
    public static final String MODE_CHANGED = "modeChanged";
    public static final String MODE = "mode";
    public static final String BLUETOOTH_SERVICE_STOPPED = "BLUETOOTH_SERVICE_STOPPED";
    public static final String SHOW_TIME_PROGRESS = "SHOW_TIME_PROGRESS";
    public static final String HIDE_TIME_PROGRESS = "HIDE_TIME_PROGRESS";
    private static final String TAG = BluetoothService.class.getSimpleName();

    private static final byte WRITE_NOTIFICATION = 1;
    private static final byte WRITE_TIME = 3;

    private static final Map<String, BluetoothGattCharacteristic> READABLE_CHARACTERISTICS = new HashMap<>();
    private static final ManufacturerInfo MANUFACTURER_INFO = new ManufacturerInfo();
    private static final Queue<String> READING_QUEUE = new ArrayBlockingQueue<>(12);
    private static final Queue<byte[]> NOTIFICATIONS_QUEUE = new LinkedBlockingDeque<>();

    private volatile boolean shouldUpdateTime;
    private volatile boolean isConnected;
    private BluetoothDevice bluetoothDevice;
    private BluetoothGattCharacteristic alertIn;
    private BluetoothGatt bluetoothGatt;
    private Mode mode;

    @Receiver(actions = BluetoothDevice.ACTION_BOND_STATE_CHANGED)
    void onBondStateChanged(Intent intent) {
        final BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
        final int bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1);
        final int previousBondState = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, -1);

        Log.d(TAG, "Bond state changed for: " + device.getAddress() +
                " new state: " + bondState + " previous: " + previousBondState);

        if (bondState == BluetoothDevice.BOND_BONDED) {
            Log.i(TAG, "Bonded");
            createGATT(device);
        } else if (bondState == BluetoothDevice.BOND_NONE) {
            device.createBond();
        }
    }

    @Receiver(actions = STOP)
    void onStopCommand() {
        Log.i(TAG, "Stop command received.");
        stopForeground(true);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "Stopping service...");
        if (bluetoothGatt != null) {
            bluetoothGatt.close();
        }

        Log.d(TAG, "onDestroy: sending intent that bt service stopped");
        final Intent intent = new Intent(BLUETOOTH_SERVICE_STOPPED);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    public void startReading(BluetoothDevice device) {
        Log.i(TAG, "Starting to read data for device: " + device.getName());
        bluetoothDevice = device;
        createGATT(device);
    }

    private void createGATT(final BluetoothDevice device) {
        bluetoothGatt = device.connectGatt(this, false, new BluetoothGattCallback() {
            @Override
            public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
                isConnected = BluetoothProfile.STATE_CONNECTED == newState;
                if (isConnected) {
                    Log.i(TAG, "GATT connected.");
                    gatt.discoverServices();
                } else {
                    Log.i(TAG, "GATT disconnected.");
                    gatt.connect();
                }

                final Intent connectionStateChanged = new Intent(CONNECTION_STATE_CHANGED);
                connectionStateChanged.putExtra(CONNECTION_STATE, isConnected);
                sendBroadcast(connectionStateChanged);
            }

            @Override
            public void onServicesDiscovered(BluetoothGatt gatt, int status) {
                Log.i(TAG, "Services discovered.");
                if (status == BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION) {
                    handleAuthenticationError(gatt);
                    return;
                }

                discoverCharacteristics(gatt);
            }

            @Override
            public void onCharacteristicWrite(BluetoothGatt gatt,
                                              BluetoothGattCharacteristic characteristic,
                                              int status) {
                Log.i(TAG, "Characteristic written: " + status);

                if (status == BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION) {
                    handleAuthenticationError(gatt);
                    return;
                }

                final byte command = characteristic.getValue()[0];
                switch (command) {
                    case WRITE_TIME:
                        Log.i(TAG, "Time written.");
                        showToast(R.string.readings_time_set_success);
                        final Intent intent = new Intent(HIDE_TIME_PROGRESS);
                        sendBroadcast(intent);

                        final BluetoothGattCharacteristic batteryCharacteristic =
                                READABLE_CHARACTERISTICS.get(Characteristic.BATTERY.getUuid());
                        gatt.setCharacteristicNotification(batteryCharacteristic, true);
                        for (BluetoothGattDescriptor descriptor : batteryCharacteristic.getDescriptors()) {
                            if (descriptor.getUuid().toString().startsWith("00002904")) {
                                descriptor.setValue(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE);
                                gatt.writeDescriptor(descriptor);
                            }
                        }
                        break;
                    // 这里是发送指令到设备中，让设备将指定的数据发送过来
                    case WRITE_NOTIFICATION:
                        readNextCharacteristics(gatt);
                        break;
                    default:
                        Log.w(TAG, "No such ALERT IN command: " + command);
                        break;
                }
            }

            @Override
            public void onDescriptorWrite(BluetoothGatt gatt,
                                          BluetoothGattDescriptor descriptor,
                                          int status) {
                readCharacteristic(gatt, Characteristic.MANUFACTURER);
            }

            @Override
            public void onCharacteristicRead(BluetoothGatt gatt,
                                             final BluetoothGattCharacteristic gattCharacteristic,
                                             int status) {
                if (status == BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION) {
                    handleAuthenticationError(gatt);
                    return;
                }

                final String characteristicUuid = gattCharacteristic.getUuid().toString();
                final Characteristic characteristic = Characteristic.byUuid(characteristicUuid);
                assert characteristic != null;
                switch (characteristic) {
                    case MANUFACTURER:
                        MANUFACTURER_INFO.manufacturer = gattCharacteristic.getStringValue(0);
                        readCharacteristic(gatt, Characteristic.FW_REVISION);
                        break;
                    case FW_REVISION:
                        MANUFACTURER_INFO.firmwareRevision = gattCharacteristic.getStringValue(0);
                        readCharacteristic(gatt, Characteristic.MODE);
                        break;
                    default:
                        Log.v(TAG, "Characteristic read: " + characteristic.name());
                        if (characteristic == Characteristic.MODE) {
                            final Mode newMode = Mode.bySymbol(gattCharacteristic.getValue()[0]);
                            if (mode != newMode) {
                                onModeChanged(newMode);
                            }
                        } else {
                            onBluetoothDataReceived(characteristic, gattCharacteristic.getValue());
                        }

                        if (shouldUpdateTime) {
                            updateTime();
                        }

                        if (NOTIFICATIONS_QUEUE.isEmpty()) {
                            readNextCharacteristics(gatt);
                        } else {
                            alertIn.setValue(NOTIFICATIONS_QUEUE.poll());
                            gatt.writeCharacteristic(alertIn);
                        }

                        break;
                }
            }

            @Override
            public void onCharacteristicChanged(BluetoothGatt gatt,
                                                BluetoothGattCharacteristic gattCharacteristic) {
                final String characteristicUuid = gattCharacteristic.getUuid().toString();
                final Characteristic characteristic = Characteristic.byUuid(characteristicUuid);
                Log.d(TAG, "Characteristic changed: " + characteristic);

                if (characteristic == Characteristic.BATTERY) {
                    onBluetoothDataReceived(Characteristic.BATTERY, gattCharacteristic.getValue());
                }
            }
        });
    }

    private void onModeChanged(final Mode newMode) {
        Log.i(TAG, "Mode changed. New mode is: " + mode);
        mode = newMode;

        setReadingQueue();

        final Intent modeChanged = new Intent(MODE_CHANGED);
        modeChanged.putExtra(MODE, newMode);
        LocalBroadcastManager.getInstance(this).sendBroadcast(modeChanged);
    }

    private void setReadingQueue() {
        READING_QUEUE.clear();
        READING_QUEUE.add(Characteristic.MODE.name());
        READING_QUEUE.add(Characteristic.ACCELERATION.name());
        READING_QUEUE.add(Characteristic.GYRO.name());
        READING_QUEUE.add(Characteristic.MAGNET.name());
        READING_QUEUE.add(Characteristic.LIGHT.name());
        READING_QUEUE.add(Characteristic.TEMPERATURE.name());
        READING_QUEUE.add(Characteristic.HUMIDITY.name());
        READING_QUEUE.add(Characteristic.PRESSURE.name());
        READING_QUEUE.add(Characteristic.BATTERY.name());
        READING_QUEUE.add(Characteristic.HEARTRATE.name());
        READING_QUEUE.add(Characteristic.STEPS.name());
        READING_QUEUE.add(Characteristic.CALORIES.name());
    }

    private void onBluetoothDataReceived(final Characteristic type, final byte[] data) {
        final Intent dataRead = new Intent(DATA_AVAILABLE);
        dataRead.putExtra(READING_TYPE, type.getUuid());
        dataRead.putExtra(STRING_DATA, DataConverter.parseBluetoothData(type, data));
        sendBroadcast(dataRead);
    }

    void readNextCharacteristics(final BluetoothGatt gatt) {
        final String characteristicUuid = READING_QUEUE.poll();
        READING_QUEUE.add(characteristicUuid);
        readCharacteristic(gatt, Characteristic.valueOf(characteristicUuid));
    }

    private void readCharacteristic(final BluetoothGatt gatt, final Characteristic characteristic) {
        if (!isConnected) {
            return;
        }

        final BluetoothGattCharacteristic gattCharacteristic = READABLE_CHARACTERISTICS.get(characteristic.getUuid());
        if (gattCharacteristic != null) {
            gatt.readCharacteristic(gattCharacteristic);
        }
    }

    private void discoverCharacteristics(final BluetoothGatt gatt) {
        if (gatt.getServices().size() == 0) {
            Log.i(TAG, "No services found.");
        }

        for (BluetoothGattService gattService : gatt.getServices()) {
            storeCharacteristicsFromService(gattService);
        }

        sendBroadcast(new Intent(SERVICES_AVAILABLE));
    }

    private void storeCharacteristicsFromService(BluetoothGattService gattService) {
        for (BluetoothGattCharacteristic gattCharacteristic : gattService.getCharacteristics()) {
            final String characteristicUuid = gattCharacteristic.getUuid().toString();
            final Characteristic characteristic = Characteristic.byUuid(characteristicUuid);

            if (characteristic == Characteristic.ALERT_IN) {
                Log.d(TAG, "ALERT_IN DISCOVERED");
                alertIn = gattCharacteristic;
                setTime();
                updateTime();
            } else if (characteristic != null) {
                Log.v(TAG, characteristic.getType() + ": " + characteristic.name());
                READABLE_CHARACTERISTICS.put(characteristicUuid, gattCharacteristic);
            } else {
                Log.v(TAG, "UNKNOWN: " + characteristicUuid);
            }
        }
    }

    public void setTime() {
        Log.d(TAG, "Setting time...");
        if (!isConnected || alertIn == null) {
            Log.w(TAG, "Time not set.");
            return;
        }

        shouldUpdateTime = true;
    }

    void updateTime() {
        shouldUpdateTime = false;

        final byte[] time = new byte[20];
        final long currentTime = System.currentTimeMillis();
        final long currentTimeWithTimeZoneOffset = (currentTime + TimeZone.getDefault().getOffset(currentTime)) / 1000;

        final ByteBuffer buffer = ByteBuffer.allocate(8);
        buffer.order(ByteOrder.LITTLE_ENDIAN).asLongBuffer().put(currentTimeWithTimeZoneOffset);
        final byte[] utcBytes = buffer.array();

        final byte length = 0x04;

        time[0] = WRITE_TIME;
        time[1] = length;
        time[2] = utcBytes[0];
        time[3] = utcBytes[1];
        time[4] = utcBytes[2];
        time[5] = utcBytes[3];

        alertIn.setValue(time);
        alertIn.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        bluetoothGatt.writeCharacteristic(alertIn);
        final Intent intent = new Intent(SHOW_TIME_PROGRESS);
        sendBroadcast(intent);
        showToast(R.string.readings_setting_time);
    }

    @UiThread
    void showToast(int messageRes) {
        Toast.makeText(getApplicationContext(), messageRes, Toast.LENGTH_SHORT).show();
    }

    public boolean isConnected() {
        return isConnected;
    }

    public Mode getCurrentMode() {
        return mode;
    }

    public BluetoothDevice getCurrentDevice() {
        return bluetoothDevice;
    }

    private void handleAuthenticationError(final BluetoothGatt gatt) {
        gatt.close();
        sendBroadcast(new Intent(BluetoothService.ACTION_NEEDS_BOND));
        gatt.getDevice().createBond();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return new ServiceBinder(this);
    }

    @Override
    public void sendBroadcast(Intent intent) {
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    public class ServiceBinder extends Binder {

        private BluetoothService service;

        public ServiceBinder(BluetoothService service) {
            this.service = service;
        }

        public BluetoothService getService() {
            return service;
        }
    }
}
