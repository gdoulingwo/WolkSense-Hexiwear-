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

package com.wolkabout.hexiwear.activity;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.support.design.widget.Snackbar;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.txusballesteros.SnakeView;
import com.wolkabout.hexiwear.R;
import com.wolkabout.hexiwear.model.Characteristic;
import com.wolkabout.hexiwear.model.Mode;
import com.wolkabout.hexiwear.service.BluetoothService;
import com.wolkabout.hexiwear.service.BluetoothService_;
import com.wolkabout.hexiwear.view.Reading;
import com.wolkabout.hexiwear.view.SingleReading;
import com.wolkabout.hexiwear.view.TripleReading;

import org.androidannotations.annotations.AfterInject;
import org.androidannotations.annotations.AfterViews;
import org.androidannotations.annotations.EActivity;
import org.androidannotations.annotations.Extra;
import org.androidannotations.annotations.OptionsMenu;
import org.androidannotations.annotations.Receiver;
import org.androidannotations.annotations.ViewById;

import java.lang.reflect.InvocationTargetException;

/**
 * 将手环中的数据读取出来
 *
 * @author notzuonotdied
 */
@SuppressLint("Registered")
@EActivity(R.layout.activity_readings)
@OptionsMenu(R.menu.menu_readings)
public class ReadingsActivity extends AppCompatActivity implements ServiceConnection {

    private static final String TAG = ReadingsActivity.class.getSimpleName();

    @Extra
    BluetoothDevice device;

    @ViewById
    View coordinator;

    @ViewById
    Toolbar toolbar;

    @ViewById
    SingleReading readingBattery;

    @ViewById
    SingleReading readingTemperature;

    @ViewById
    SingleReading readingHumidity;

    @ViewById
    SingleReading readingPressure;

    @ViewById
    SingleReading readingHeartRate;

    @ViewById
    SingleReading readingLight;

    @ViewById
    SingleReading readingSteps;

    @ViewById
    SingleReading readingCalories;

    @ViewById
    TripleReading readingAcceleration;

    @ViewById
    TripleReading readingMagnet;

    @ViewById
    TripleReading readingGyro;

    @ViewById
    TextView connectionStatus;

    @ViewById
    ProgressBar progressBar;

    @ViewById
    LinearLayout readings;

    @ViewById
    SnakeView snake;

    private boolean isBound;
    private Mode mode = Mode.IDLE;
    private boolean shouldUnpair;

    @AfterInject
    void startService() {
        BluetoothService_.intent(this).start();
        isBound = bindService(BluetoothService_.intent(this).get(), this, BIND_AUTO_CREATE);
    }

    @AfterViews
    void setViews() {
        toolbar.setTitle(getString(R.string.app_name));
        snake.setMinValue(0);
        snake.setMaxValue(222);
        setSupportActionBar(toolbar);
        progressBar.setVisibility(View.VISIBLE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        shouldUnpair = false;
        invalidateOptionsMenu();
        setReadingVisibility(mode);
    }

    @Receiver(actions = BluetoothService.MODE_CHANGED, local = true)
    void onModeChanged(@Receiver.Extra final Mode mode) {
        this.mode = mode;
        connectionStatus.setText(mode.getStringResource());

        if (mode == Mode.IDLE) {
            showInfo(R.string.readings_idle_mode);
        }

        setReadingVisibility(mode);
    }

    @Receiver(actions = BluetoothService.BLUETOOTH_SERVICE_STOPPED, local = true)
    void onBluetoothServiceDestroyed() {
        if (!shouldUnpair) {
            return;
        }

        try {
            device.getClass().getMethod("removeBond", (Class[]) null).invoke(device, (Object[]) null);
            showInfo(R.string.device_unpaired);
            finish();
        } catch (IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
            e.printStackTrace();
            showInfo(R.string.failed_to_unpair);
        }
    }

    @Receiver(actions = BluetoothService.SHOW_TIME_PROGRESS, local = true)
    void showProgressForSettingTime() {
        if (progressBar == null) {
            return;
        }

        progressBar.setVisibility(View.VISIBLE);
    }

    @Receiver(actions = BluetoothService.HIDE_TIME_PROGRESS, local = true)
    void hideProgressForSettingTime() {
        if (progressBar == null) {
            return;
        }
        progressBar.setVisibility(View.INVISIBLE);
    }

    /**
     * 根据当前的模式选择显示的数据
     *
     * @param mode 模式
     */
    private void setReadingVisibility(final Mode mode) {
        for (int i = 0; i < readings.getChildCount(); i++) {
            final View view = readings.getChildAt(i);
            if (view instanceof SnakeView) {
                snake.setVisibility(mode.hasCharacteristic(Characteristic.HEARTRATE) ? View.VISIBLE : View.GONE);
            } else {
                final Reading reading = (Reading) view;
                final Characteristic readingType = reading.getReadingType();
                reading.setVisibility(mode.hasCharacteristic(readingType) ? View.VISIBLE : View.GONE);
            }
        }
    }

    @Override
    public void onServiceConnected(final ComponentName name, final IBinder service) {
        final BluetoothService.ServiceBinder binder = (BluetoothService.ServiceBinder) service;
        BluetoothService bluetoothService = binder.getService();
        if (!bluetoothService.isConnected()) {
            bluetoothService.startReading(device);
        }
        final Mode mode = bluetoothService.getCurrentMode();
        if (mode != null) {
            onModeChanged(mode);
        }
    }

    @Override
    public void onServiceDisconnected(final ComponentName name) {
        // Something terrible happened.
    }

    @Override
    protected void onDestroy() {
        if (isBound) {
            unbindService(this);
            isBound = false;
        }
        super.onDestroy();
    }

    @Receiver(actions = BluetoothService.ACTION_NEEDS_BOND, local = true)
    void onBondRequested() {
        connectionStatus.setText(R.string.discovery_pairing);
        Snackbar.make(coordinator, R.string.discovery_pairing, Snackbar.LENGTH_LONG).show();
    }

    @Receiver(actions = BluetoothService.CONNECTION_STATE_CHANGED, local = true)
    void onConnectionStateChanged(@Receiver.Extra final boolean connectionState) {
        connectionStatus.setText(connectionState ?
                R.string.readings_connection_connected :
                R.string.readings_connection_reconnecting);
    }

    @Receiver(actions = BluetoothService.DATA_AVAILABLE, local = true)
    void onDataAvailable(Intent intent) {
        progressBar.setVisibility(View.INVISIBLE);

        final String uuid = intent.getStringExtra(BluetoothService.READING_TYPE);
        final String data = intent.getStringExtra(BluetoothService.STRING_DATA);

        if (data.isEmpty()) {
            return;
        }

        final Characteristic characteristic = Characteristic.byUuid(uuid);
        if (characteristic == null) {
            Log.w(TAG, "UUID " + uuid + " is unknown. Skipping.");
            return;
        }

        switch (characteristic) {
            case BATTERY:
                readingBattery.setValue(data);
                break;
            case TEMPERATURE:
                readingTemperature.setValue(data);
                break;
            case HUMIDITY:
                readingHumidity.setValue(data);
                break;
            case PRESSURE:
                readingPressure.setValue(data);
                break;
            case HEARTRATE:
                readingHeartRate.setValue(data);
                snake.addValue(Integer.parseInt(data.replace("bpm", "").trim()));
                break;
            case LIGHT:
                readingLight.setValue(data);
                break;
            case STEPS:
                readingSteps.setValue(data);
                break;
            case CALORIES:
                readingCalories.setValue(data);
                break;
            case ACCELERATION:
                final String[] accelerationReadings = data.split(";");
                readingAcceleration.setFirstValue(accelerationReadings[0]);
                readingAcceleration.setSecondValue(accelerationReadings[1]);
                readingAcceleration.setThirdValue(accelerationReadings[2]);
                break;
            case MAGNET:
                final String[] magnetReadings = data.split(";");
                readingMagnet.setFirstValue(magnetReadings[0]);
                readingMagnet.setSecondValue(magnetReadings[1]);
                readingMagnet.setThirdValue(magnetReadings[2]);
                break;
            case GYRO:
                final String[] gyroscopeReadings = data.split(";");
                readingGyro.setFirstValue(gyroscopeReadings[0]);
                readingGyro.setSecondValue(gyroscopeReadings[1]);
                readingGyro.setThirdValue(gyroscopeReadings[2]);
                break;
            default:
                break;
        }
    }

    @Receiver(actions = BluetoothService.STOP)
    void onStopReading() {
        Log.i(TAG, "Stop command received. Finishing...");
        finish();
    }

    @Override
    public void onBackPressed() {
        BluetoothService_.intent(this).stop();
        if (isTaskRoot()) {
            FindDeviceActivity_.intent(this).start();
        }
        super.onBackPressed();
    }

    private void showInfo(int messageId) {
        Toast.makeText(this, getString(messageId), Toast.LENGTH_SHORT).show();
    }
}
