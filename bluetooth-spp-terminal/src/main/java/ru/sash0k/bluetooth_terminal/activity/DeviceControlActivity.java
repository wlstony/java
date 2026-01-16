package ru.sash0k.bluetooth_terminal.activity;

import android.annotation.SuppressLint;
import android.app.ActionBar;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.location.Location;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.preference.PreferenceManager;
import android.text.Html;
import android.text.InputFilter;
import android.text.InputType;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;


import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Date;

import ru.sash0k.bluetooth_terminal.DeviceData;
import ru.sash0k.bluetooth_terminal.LocationHelper;
import ru.sash0k.bluetooth_terminal.R;
import ru.sash0k.bluetooth_terminal.Utils;
import ru.sash0k.bluetooth_terminal.bluetooth.DeviceConnector;
import ru.sash0k.bluetooth_terminal.bluetooth.DeviceListActivity;

public final class DeviceControlActivity extends BaseActivity {
    private static final String DEVICE_NAME = "DEVICE_NAME";
    private static final String LOG = "LOG";
    private static final String SppTag = "spp_debug";
    private static final String CRC_OK = "#FFFF00";
    private static final String CRC_BAD = "#FF0000";

    private static final SimpleDateFormat timeformat = new SimpleDateFormat("HH:mm:ss.SSS");

    private static String MSG_NOT_CONNECTED;
    private static String MSG_CONNECTING;
    private static String MSG_CONNECTED;

    private static DeviceConnector connector;
    private static BluetoothResponseHandler mHandler;

    private StringBuilder logHtml;
    private TextView logTextView;
    private EditText commandEditText;

    // Настройки приложения
    private int logLimitSize;
    private boolean hexMode, checkSum, needClean, logLimit;
    private boolean show_timings, show_direction;
    private String command_ending;
    private String deviceName;
    private LocationHelper locationHelper;
    private boolean isLoggingIn = false;
    private Handler loginHandler = new Handler(Looper.getMainLooper());
    private static final int LOGIN_TIMEOUT = 8000; // 8秒超时

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        PreferenceManager.setDefaultValues(this, R.xml.settings_activity, false);
        locationHelper = new LocationHelper(this);
        if (mHandler == null) mHandler = new BluetoothResponseHandler(this);
        else mHandler.setTarget(this);

        MSG_NOT_CONNECTED = getString(R.string.msg_not_connected);
        MSG_CONNECTING = getString(R.string.msg_connecting);
        MSG_CONNECTED = getString(R.string.msg_connected);

        setContentView(R.layout.activity_terminal);
        if (isConnected() && (savedInstanceState != null)) {
            setDeviceName(savedInstanceState.getString(DEVICE_NAME));
        } else getActionBar().setSubtitle(MSG_NOT_CONNECTED);

        this.logHtml = new StringBuilder();
        if (savedInstanceState != null) this.logHtml.append(savedInstanceState.getString(LOG));

        this.logTextView = (TextView) findViewById(R.id.log_textview);
        this.logTextView.setMovementMethod(new ScrollingMovementMethod());
        this.logTextView.setText(Html.fromHtml(logHtml.toString()));

        this.commandEditText = (EditText) findViewById(R.id.command_edittext);
        // soft-keyboard send button
        this.commandEditText.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    sendCommand(null);
                    return true;
                }
                return false;
            }
        });
        // hardware Enter button
        this.commandEditText.setOnKeyListener(new View.OnKeyListener() {
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    switch (keyCode) {
                        case KeyEvent.KEYCODE_ENTER:
                            sendCommand(null);
                            return true;
                        default:
                            break;
                    }
                }
                return false;
            }
        });

        Intent intent = getIntent();
        String action = intent.getAction();
        String type = intent.getType();

        if (Intent.ACTION_SEND.equals(action) && type != null) {
            if ("text/plain".equals(type)) {
                String sharedText = intent.getStringExtra(Intent.EXTRA_TEXT);
                if (sharedText != null) {
                    EditText commandEditText = findViewById(R.id.command_edittext);
                    commandEditText.setText(sharedText);
                }
            }
        }
    }
    // ==========================================================================

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(DEVICE_NAME, deviceName);
        if (logTextView != null) {
            outState.putString(LOG, logHtml.toString());
        }
    }
    // ============================================================================


    /**
     * Проверка готовности соединения
     */
    private boolean isConnected() {
        return (connector != null) && (connector.getState() == DeviceConnector.STATE_CONNECTED);
    }
    // ==========================================================================


    /**
     * Разорвать соединение
     */
    private void stopConnection() {
        if (connector != null) {
            connector.stop();
            connector = null;
            deviceName = null;
        }
    }
    // ==========================================================================


    /**
     * Список устройств для подключения
     */
    private void startDeviceListActivity() {
        stopConnection();
        Intent serverIntent = new Intent(this, DeviceListActivity.class);
        startActivityForResult(serverIntent, REQUEST_CONNECT_DEVICE);
    }
    // ============================================================================


    /**
     * Обработка аппаратной кнопки "Поиск"
     *
     * @return
     */
    @Override
    public boolean onSearchRequested() {
        if (super.isAdapterReady()) startDeviceListActivity();
        return false;
    }
    // ==========================================================================


    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.device_control_activity, menu);
        final MenuItem bluetooth = menu.findItem(R.id.menu_search);
        if (bluetooth != null) bluetooth.setIcon(this.isConnected() ?
                R.drawable.ic_action_device_bluetooth_connected :
                R.drawable.ic_action_device_bluetooth);
        return true;
    }
    // ============================================================================


    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {

            case R.id.menu_search:
                if (super.isAdapterReady()) {
                    if (isConnected()) stopConnection();
                    else startDeviceListActivity();
                } else {
                    Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                    if (checkBluetoothPermission(REQUEST_ENABLE_BT))
                        startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
                }
                return true;

            case R.id.menu_clear:
                if (logTextView != null) logTextView.setText("");
                return true;

            case R.id.menu_send:
                if (logTextView != null) {
                    final String msg = logTextView.getText().toString();
                    final Intent intent = new Intent(Intent.ACTION_SEND);
                    intent.setType("text/plain");
                    intent.putExtra(Intent.EXTRA_TEXT, msg);
                    startActivity(Intent.createChooser(intent, getString(R.string.menu_send)));
                }
                return true;

            case R.id.menu_settings:
                final Intent intent = new Intent(this, SettingsActivity.class);
                startActivity(intent);
                return true;

            default:
                return super.onOptionsItemSelected(item);
        }
    }
    // ============================================================================


    @Override
    public void onStart() {
        super.onStart();

        // hex mode
        final String mode = Utils.getPrefence(this, getString(R.string.pref_commands_mode));
        this.hexMode = "HEX".equals(mode);
        if (hexMode) {
            commandEditText.setInputType(InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
            commandEditText.setFilters(new InputFilter[]{new Utils.InputFilterHex()});
        } else {
            commandEditText.setInputType(InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            commandEditText.setFilters(new InputFilter[]{});
        }

        // checksum
        final String checkSum = Utils.getPrefence(this, getString(R.string.pref_checksum_mode));
        this.checkSum = "Modulo 256".equals(checkSum);

        this.command_ending = getCommandEnding();

        this.show_timings = Utils.getBooleanPrefence(this, getString(R.string.pref_log_timing));
        this.show_direction = Utils.getBooleanPrefence(this, getString(R.string.pref_log_direction));
        this.needClean = Utils.getBooleanPrefence(this, getString(R.string.pref_need_clean));
        this.logLimit = Utils.getBooleanPrefence(this, getString(R.string.pref_log_limit));
        this.logLimitSize = Utils.formatNumber(Utils.getPrefence(this, getString(R.string.pref_log_limit_size)));
    }
    // ============================================================================
    private String getCommandEnding() {
        String result = Utils.getPrefence(this, getString(R.string.pref_commands_ending));
        if (result.equals("\\r\\n")) result = "\r\n";
        else if (result.equals("\\n")) result = "\n";
        else if (result.equals("\\r")) result = "\r";
        else result = "";
        return result;
    }
    // ============================================================================


    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        switch (requestCode) {
            case REQUEST_CONNECT_DEVICE:
                // When DeviceListActivity returns with a device to connect
                if (resultCode == Activity.RESULT_OK) {
                    String address = data.getStringExtra(DeviceListActivity.EXTRA_DEVICE_ADDRESS);
                    BluetoothDevice device = btAdapter.getRemoteDevice(address);
                    if (super.isAdapterReady() && (connector == null)) setupConnector(device);
                }
                break;
            case REQUEST_ENABLE_BT:
                // When the request to enable Bluetooth returns
                super.pendingRequestEnableBt = false;
                if (resultCode != Activity.RESULT_OK) {
                    Utils.log("BT not enabled");
                }
                break;
        }
    }
    // ==========================================================================
    private void setupConnector(BluetoothDevice connectedDevice) {
        stopConnection();
        try {
            String emptyName = getString(R.string.empty_device_name);
            DeviceData data = new DeviceData(connectedDevice, emptyName);
            connector = new DeviceConnector(data, mHandler);
            connector.connect();
        } catch (IllegalArgumentException e) {
            Utils.log("setupConnector failed: " + e.getMessage());
        }
    }
    // ==========================================================================


    // 发送指令
    public void sendCommand(View view) {
        if (commandEditText != null) {
            String commandString = commandEditText.getText().toString();
            if (commandString.isEmpty()) return;
           sendStringCommand(commandString, true);
        }
    }
    // ==========================================================================

    public void sendDefaultCommand(View view) {
        String commandString = view.getTag().toString();
        sendStringCommand(commandString, false);
    }
    public void sendStringCommand(String commandString, boolean appendLogFlag) {
        if (checkSum) {
            commandString += Utils.calcModulo256(commandString);
        }
        String strWithColor = "<font color='green'>"+commandString+"</font>";
        Utils.log("sendStringCommand:" + commandString);
        byte[] command = (hexMode ? Utils.toHex(commandString) : commandString.getBytes());
        if (command_ending != null) command = Utils.concat(command, command_ending.getBytes());
        if (isConnected()) {
            connector.write(command);
        }
        if (appendLogFlag) {
            appendLog(strWithColor, hexMode, true, needClean);
        }
    }
    public void sendLogin(View view) throws InterruptedException {
        String username = ((EditText)findViewById(R.id.username)).getText().toString();
        String passwd = ((EditText)findViewById(R.id.password)).getText().toString();
        if (username.isEmpty() || passwd.isEmpty())  {
            showAlertDialog("user and passwd can not be empty", false);
            return;
        }
        if (isConnected()) {
            showAlertDialog("connection is already established", false);
            return;
        }
//        sendStringCommand(username, false);
//        Thread.sleep(50);
//        sendStringCommand(passwd, false);

        // 开始登录
        startAutoLogin(username, passwd);
    }
    // ==================== 核心登录流程 ====================
    private void startAutoLogin(final String username, final String password) {
        isLoggingIn = true;
        appendLog("[APP] Starting login...", false, true, false);

        // 设置登录超时
        loginHandler.postDelayed(() -> {
            if (isLoggingIn) {
                Toast.makeText(this, "Login timeout", Toast.LENGTH_SHORT).show();
                isLoggingIn = false;
            }
        }, LOGIN_TIMEOUT);

        // 第一步：发送用户名
        loginHandler.postDelayed(() -> {
            if (!isLoggingIn) return;

            appendLog("Sending username: " + username, false, true, false);
            sendStringCommand(username, false);

            // 第二步：延迟发送密码
            loginHandler.postDelayed(() -> {
                if (!isLoggingIn) return;

                appendLog("Sending password: ******", false, true, false);
                sendStringCommand(password, false);

                // 第三步：登录完成，等待验证
                loginHandler.postDelayed(() -> {
                    if (isLoggingIn) {
                        appendLog("Login completed, waiting for response", false, false, false);
                    }
                }, 500);

            }, 500); // 用户名和密码间隔500ms

        }, 500); // 连接后延迟500ms开始
    }
    public void sendGps(View view) throws InterruptedException {
        if (!checkLocationPermission() || !checkCoarsePermission()) {
            showAlertDialog("no location permission", false);
        }
        // 更新位置
        if (!locationHelper.locationUpdateFlag) {
            locationHelper.requestLocationUpdates();
        }
        Location lastLocation = locationHelper.getLastKnownLocation();
        if (lastLocation == null) {
            showAlertDialog("Location is null", false);
            return;
        }
        double latitude = lastLocation.getLatitude();
        double longitude = lastLocation.getLongitude();
        // 在这里处理经纬度数据
        @SuppressLint("DefaultLocale") String[] gpsCommand = {
                "configure terminal",
                "gps latitude north " + String.format("%.6f", latitude),
                "gps longitude east " + String.format("%.6f", longitude),
                "exit"
        };
        for (String s : gpsCommand) {
            // 延迟50ms
            Thread.sleep(50);
           sendStringCommand(s,false);
        }
    }


    void appendLog(String message, boolean hexMode, boolean outgoing, boolean clean) {
        if (this.logLimit && this.logLimitSize > 0 && logTextView.getLineCount() > this.logLimitSize) {
            logTextView.setText("");
        }

        StringBuilder msg = new StringBuilder();
        if (show_timings) msg.append("[").append(timeformat.format(new Date())).append("]");
        if (show_direction) {
            final String arrow = (outgoing ? " << " : " >> ");
            msg.append(arrow);
        } else msg.append(" ");
        String crc = "";
        boolean crcOk = false;
        if (checkSum) {
            int crcPos = message.length() - 2;
            crc = message.substring(crcPos);
            message = message.substring(0, crcPos);
            crcOk = outgoing || crc.equals(Utils.calcModulo256(message).toUpperCase());
            if (hexMode) crc = Utils.printHex(crc.toUpperCase());
        }
        if (!outgoing && isLoggingIn) {
            checkLoginResponse(message);
        }
        msg.append("<b>")
                .append(hexMode ? Utils.printHex(message) : message)
                .append(checkSum ? Utils.mark(crc, crcOk ? CRC_OK : CRC_BAD) : "")
                .append("</b>")
                .append("<br>");

        logHtml.append(msg);
        logTextView.append(Html.fromHtml(msg.toString()));

        final int scrollAmount = logTextView.getLayout().getLineTop(logTextView.getLineCount()) - logTextView.getHeight();
        logTextView.scrollTo(0, Math.max(scrollAmount, 0));

        if (clean) commandEditText.setText("");
    }
    // =========================================================================
    private void checkLoginResponse(String response) {
        if (!isLoggingIn) return;

        // 检查登录成功的关键词
        if (response.contains("#") ||
                response.contains(">") ||
                response.contains("$") ||
                response.contains("Welcome") ||
                response.contains("Last login") ||
                response.toLowerCase().contains("success")) {

            // 登录成功
            loginHandler.removeCallbacksAndMessages(null);
            isLoggingIn = false;

            runOnUiThread(() -> {
                Toast.makeText(this, "Login successful", Toast.LENGTH_SHORT).show();
                appendLog("[APP] ✓ Login successful!", false, false, false);
            });
        }

        // 检查登录失败的关键词
        else if (response.contains("Error") ||
                response.contains("Invalid") ||
                response.contains("Access denied") ||
                response.contains("Login incorrect") ||
                response.toLowerCase().contains("fail")) {

            // 登录失败
            loginHandler.removeCallbacksAndMessages(null);
            isLoggingIn = false;

            runOnUiThread(() -> {
                Toast.makeText(this, "Login failed", Toast.LENGTH_SHORT).show();
                appendLog("[APP] ✗ Login failed: " + response, false, false, false);
            });
        }
    }

    void setDeviceName(String deviceName) {
        this.deviceName = deviceName;
        getActionBar().setSubtitle(deviceName);
    }
    // ==========================================================================

    private static class BluetoothResponseHandler extends Handler {
        private WeakReference<DeviceControlActivity> mActivity;

        public BluetoothResponseHandler(DeviceControlActivity activity) {
            mActivity = new WeakReference<DeviceControlActivity>(activity);
        }

        public void setTarget(DeviceControlActivity target) {
            mActivity.clear();
            mActivity = new WeakReference<DeviceControlActivity>(target);
        }

        @Override
        public void handleMessage(Message msg) {
            DeviceControlActivity activity = mActivity.get();
            if (activity != null) {
                switch (msg.what) {
                    case MESSAGE_STATE_CHANGE:
                        Utils.log("MESSAGE_STATE_CHANGE: " + msg.arg1);
                        final ActionBar bar = activity.getActionBar();
                        switch (msg.arg1) {
                            case DeviceConnector.STATE_CONNECTED:
                                bar.setSubtitle(MSG_CONNECTED);
                                break;
                            case DeviceConnector.STATE_CONNECTING:
                                bar.setSubtitle(MSG_CONNECTING);
                                break;
                            case DeviceConnector.STATE_NONE:
                                bar.setSubtitle(MSG_NOT_CONNECTED);
                                break;
                        }
                        activity.invalidateOptionsMenu();
                        break;

                    case MESSAGE_READ:
                        final String readMessage = (String) msg.obj;
                        Utils.log("MESSAGE Read: " +readMessage);

                        if (readMessage != null) {
                            activity.appendLog(readMessage, false, false, false);
                        }
                        break;

                    case MESSAGE_DEVICE_NAME:
                        activity.setDeviceName((String) msg.obj);
                        break;

                    case MESSAGE_WRITE:
                        // stub
                        break;

                    case MESSAGE_TOAST:
                        // stub
                        break;
                }
            }
        }
    }
    // ==========================================================================
}