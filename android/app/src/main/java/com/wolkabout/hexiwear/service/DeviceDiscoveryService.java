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
import android.annotation.TargetApi;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;

import com.wolkabout.hexiwear.model.BluetoothDeviceWrapper;

import org.androidannotations.annotations.Background;
import org.androidannotations.annotations.EBean;
import org.androidannotations.annotations.RootContext;
import org.androidannotations.api.BackgroundExecutor;
import org.parceler.Parcels;

import java.lang.reflect.Method;
import java.util.Set;

/**
 * @author notzuonotdied
 */
@EBean
public class DeviceDiscoveryService {

    public static final String SCAN_STARTED = "scanStarted";
    public static final String SCAN_STOPPED = "scanStopped";
    public static final String DEVICE_DISCOVERED = "deviceDiscovered";
    private static final String WRAPPER = "wrapper";
    private static final String TAG = DeviceDiscoveryService.class.getSimpleName();
    private static final long SCAN_PERIOD = 5000;
    private static final String SCAN_TASK = "scan";
    private static final String HEXIWEAR_TAG = "hexiwear";
    private static final String HEXI_OTAP_TAG = "hexiotap";
    private static final BluetoothAdapter BLUETOOTH_ADAPTER = BluetoothAdapter.getDefaultAdapter();
    private static ScanCallback lolipopScanCallback;
    private static BluetoothAdapter.LeScanCallback kitKatScanCallback;
    @RootContext
    Context context;

    public void startScan() {
        if (!isEnabled()) {
            return;
        }

        if (getBltList()) {
            Log.i(TAG, "************连接到已经匹配的设备*************");
            return;
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            startKitKatScan();
        } else {
            startLolipopScan();
        }

        setScanTimeLimit();
        sendBroadcast(new Intent(SCAN_STARTED));
        Log.i(TAG, "Bluetooth device discovery started.");
    }

    /**
     * 获得系统保存的配对成功过的设备，并尝试连接
     */
    private boolean getBltList() {
        if (BLUETOOTH_ADAPTER == null) {
            return false;
        }
        // 获得已配对的远程蓝牙设备的集合
        Set<BluetoothDevice> devices = BLUETOOTH_ADAPTER.getBondedDevices();
        if (devices.size() > 0) {
            for (BluetoothDevice device : devices) {
                // 自动连接已有蓝牙设备
                if (device.getBondState() == BluetoothDevice.BOND_NONE) {
                    // 如果这个设备取消了配对，则尝试配对
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                        if (device.createBond()) {
                            return true;
                        }
                    }
                } else if (device.getBondState() == BluetoothDevice.BOND_BONDED) {
                    final BluetoothDeviceWrapper wrapper = new BluetoothDeviceWrapper();
                    wrapper.setDevice(device);
                    wrapper.setSignalStrength(66);
                    onDeviceDiscovered(wrapper);
                }
            }
        }
        return false;
    }

    @SuppressWarnings("deprecation")
    @TargetApi(Build.VERSION_CODES.KITKAT)
    private void startKitKatScan() {
        kitKatScanCallback = new BluetoothAdapter.LeScanCallback() {
            @Override
            public void onLeScan(BluetoothDevice device, int rssi, byte[] scanRecord) {
                final BluetoothDeviceWrapper wrapper = new BluetoothDeviceWrapper();
                wrapper.setDevice(device);
                wrapper.setSignalStrength(rssi);
                onDeviceDiscovered(wrapper);
            }
        };
        BLUETOOTH_ADAPTER.startLeScan(kitKatScanCallback);
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private void startLolipopScan() {
        lolipopScanCallback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                super.onScanResult(callbackType, result);
                final BluetoothDeviceWrapper wrapper = new BluetoothDeviceWrapper();
                wrapper.setDevice(result.getDevice());
                wrapper.setSignalStrength(result.getRssi());
                onDeviceDiscovered(wrapper);
            }
        };
        BLUETOOTH_ADAPTER.getBluetoothLeScanner().startScan(lolipopScanCallback);
    }

    /**
     * 输入mac地址进行自动配对
     * 前提是系统保存了该地址的对象
     *
     * @param address 地址
     */
    private boolean autoConnect(String address) {
        if (BLUETOOTH_ADAPTER.isDiscovering()) {
            BLUETOOTH_ADAPTER.cancelDiscovery();
        }
        BluetoothDevice btDev = BLUETOOTH_ADAPTER.getRemoteDevice(address);
        final BluetoothDeviceWrapper wrapper = new BluetoothDeviceWrapper();
        wrapper.setDevice(btDev);
        wrapper.setSignalStrength(66);
        onDeviceDiscovered(wrapper);
        return true;
    }

    private void onDeviceDiscovered(final BluetoothDeviceWrapper wrapper) {
        final BluetoothDevice device = wrapper.getDevice();
        Log.i(TAG, "Discovered device: " + device.getName() + "(" + device.getAddress() + ")");
        final String name = wrapper.getDevice().getName();
        if (HEXI_OTAP_TAG.equalsIgnoreCase(name)) {
            wrapper.setInOtapMode(true);
        } else if (!HEXIWEAR_TAG.equalsIgnoreCase(name)) {
            return;
        }

        final Intent deviceDiscovered = new Intent(DEVICE_DISCOVERED);
        deviceDiscovered.putExtra(WRAPPER, Parcels.wrap(wrapper));
        sendBroadcast(deviceDiscovered);
    }

    @Background(id = SCAN_TASK, delay = SCAN_PERIOD)
    void setScanTimeLimit() {
        cancelScan();
    }

    @SuppressWarnings("deprecation")
    public void cancelScan() {
        if (!isEnabled()) {
            return;
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            BLUETOOTH_ADAPTER.stopLeScan(kitKatScanCallback);
        } else {
            BLUETOOTH_ADAPTER.getBluetoothLeScanner().stopScan(lolipopScanCallback);
        }

        sendBroadcast(new Intent(SCAN_STOPPED));
        Log.i(TAG, "Bluetooth device discovery canceled");
        BackgroundExecutor.cancelAll(SCAN_TASK, true);
    }

    private boolean isEnabled() {
        if (BLUETOOTH_ADAPTER == null) {
            Log.e(TAG, "Bluetooth not supported");
            return false;
        } else if (!BLUETOOTH_ADAPTER.isEnabled()) {
            context.startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
            return false;
        } else {
            Log.v(TAG, "Bluetooth is enabled and functioning properly.");
            return true;
        }
    }

    private void sendBroadcast(Intent intent) {
        LocalBroadcastManager.getInstance(context).sendBroadcast(intent);
    }

    /**
     * 获取已经配对完成的设备列表
     */
    private BluetoothDevice getHasPairDevices() {
        // 得到BluetoothAdapter的Class对象
        Class<BluetoothAdapter> bluetoothAdapterClass = BluetoothAdapter.class;
        try {// 得到连接状态的方法
            Method method = bluetoothAdapterClass.getDeclaredMethod("getConnectionState", (Class[]) null);
            // 打开权限
            method.setAccessible(true);
            int state = (int) method.invoke(BLUETOOTH_ADAPTER, (Object[]) null);

            if (state == BluetoothAdapter.STATE_CONNECTED) {
                Log.i("BLUETOOTH", "BluetoothAdapter.STATE_CONNECTED");
                Set<BluetoothDevice> devices = BLUETOOTH_ADAPTER.getBondedDevices();
                Log.i("BLUETOOTH", "devices:" + devices.size());

                for (BluetoothDevice device : devices) {
                    @SuppressLint("PrivateApi") Method isConnectedMethod = BluetoothDevice.class
                            .getDeclaredMethod("isConnected", (Class[]) null);
                    method.setAccessible(true);
                    boolean isConnected = (boolean) isConnectedMethod.invoke(device, (Object[]) null);
                    if (isConnected) {
                        Log.i("BLUETOOTH", "connected:" + device.getName());
                        return device;
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }
}
