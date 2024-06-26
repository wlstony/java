package ru.sash0k.bluetooth_terminal.activity;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.NonNull;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;

import java.util.Map;

import ru.sash0k.bluetooth_terminal.R;
import ru.sash0k.bluetooth_terminal.Utils;

/**
 * Общий базовый класс. Инициализация BT-адаптера
 * Created by sash0k on 09.12.13.
 */
public abstract class BaseActivity extends Activity {

    // Intent request codes
    static final int REQUEST_CONNECT_DEVICE = 1;
    static final int REQUEST_ENABLE_BT = 2;

    static final  int REQUEST_ENABLE_LOCATION = 3;
    static final  int REQUEST_ENABLE_COARSE = 4;


    // Message types sent from the DeviceConnector Handler
    public static final int MESSAGE_STATE_CHANGE = 1;
    public static final int MESSAGE_READ = 2;
    public static final int MESSAGE_WRITE = 3;
    public static final int MESSAGE_DEVICE_NAME = 4;
    public static final int MESSAGE_TOAST = 5;

    BluetoothAdapter btAdapter;

    private static final String SAVED_PENDING_REQUEST_ENABLE_BT = "PENDING_REQUEST_ENABLE_BT";
    // do not resend request to enable Bluetooth
    // if there is a request already in progress
    // See: https://code.google.com/p/android/issues/detail?id=24931#c1
    boolean pendingRequestEnableBt = false;

    // ==========================================================================

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getActionBar().setHomeButtonEnabled(false);

        checkBluetoothPermission(REQUEST_ENABLE_BT);

        if (state != null) {
            pendingRequestEnableBt = state.getBoolean(SAVED_PENDING_REQUEST_ENABLE_BT);
        }
        btAdapter = BluetoothAdapter.getDefaultAdapter();
        if (btAdapter == null) {
            final String no_bluetooth = getString(R.string.no_bt_support);
            showAlertDialog(no_bluetooth, true);
            Utils.log(no_bluetooth);
        }
    }
    // ==========================================================================

    protected boolean checkBluetoothPermission(int requestCode) {
        final String permission;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            permission = Manifest.permission.BLUETOOTH_CONNECT;
        else
            permission = Manifest.permission.BLUETOOTH;

        if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{permission}, requestCode);
            return false;
        }

        return true;
    }

    protected boolean checkLocationPermission() {
        String permission = Manifest.permission.ACCESS_FINE_LOCATION;

        if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{permission}, BaseActivity.REQUEST_ENABLE_LOCATION);
            return false;
        }
        return true;
    }
    protected boolean checkCoarsePermission() {
        String permission = Manifest.permission.ACCESS_COARSE_LOCATION;
        if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{permission}, BaseActivity.REQUEST_ENABLE_COARSE);
            return false;
        }
        return true;
    }
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        int grantResult;
        if (grantResults.length > 0)
            grantResult = grantResults[0];
        else grantResult = PackageManager.PERMISSION_DENIED;

    if (requestCode == REQUEST_ENABLE_BT && grantResult != PackageManager.PERMISSION_GRANTED) {
            final String message = getString(R.string.no_permission);
            showAlertDialog(message, true);
            Utils.log(message);
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        if (btAdapter == null) return;

        if (!btAdapter.isEnabled() && !pendingRequestEnableBt) {
            pendingRequestEnableBt = true;
            Intent enableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            if (checkBluetoothPermission(REQUEST_ENABLE_BT)) startActivityForResult(enableIntent, REQUEST_ENABLE_BT);
        }
    }
    // ==========================================================================


    @Override
    public synchronized void onResume() {
        super.onResume();
    }
    // ==========================================================================


    @Override
    public synchronized void onPause() {
        super.onPause();
    }
    // ==========================================================================


    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(SAVED_PENDING_REQUEST_ENABLE_BT, pendingRequestEnableBt);
    }
    // ==========================================================================


    /**
     * Проверка адаптера
     *
     * @return - true, если готов к работе
     */
    boolean isAdapterReady() {
        return (btAdapter != null) && (btAdapter.isEnabled());
    }
    // ==========================================================================


    /**
     * Показывает диалоговое окно с предупреждением.
     * TODO: При переконфигурациях будет теряться
     *
     * @param message - сообщение
     */
    void showAlertDialog(String message, boolean finishFlag) {
        AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.app_name))
                .setMessage(message);

        AlertDialog alertDialog = alertDialogBuilder.create();
        alertDialog.setCancelable(false);
        alertDialog.setButton(DialogInterface.BUTTON_NEUTRAL, getString(android.R.string.ok), new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialogInterface, int i) {
                if (finishFlag){
                    finish();
                }
            }
        });

        alertDialog.show();
    }
}
