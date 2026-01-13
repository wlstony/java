/*
 * Copyright (C) 2009 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ru.sash0k.bluetooth_terminal.bluetooth;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import java.util.ArrayList;
import java.util.List;
import ru.sash0k.bluetooth_terminal.DeviceData;
import ru.sash0k.bluetooth_terminal.activity.DeviceControlActivity;


public class DeviceConnector {
    private static final String TAG = "DeviceConnector";
    private static final boolean D = false;

    // Constants that indicate the current connection state
    public static final int STATE_NONE = 0;       // we're doing nothing
    public static final int STATE_CONNECTING = 1; // now initiating an outgoing connection
    public static final int STATE_CONNECTED = 2;  // now connected to a remote device

    private int mState;

    private final BluetoothAdapter btAdapter;
    private final BluetoothDevice connectedDevice;
    private ConnectThread mConnectThread;
    private ConnectedThread mConnectedThread;
    private final Handler mHandler;
    private final String deviceName;
    // ==========================================================================


    public DeviceConnector(DeviceData deviceData, Handler handler) {
        mHandler = handler;
        btAdapter = BluetoothAdapter.getDefaultAdapter();
        connectedDevice = btAdapter.getRemoteDevice(deviceData.getAddress());
        deviceName = (deviceData.getName() == null) ? deviceData.getAddress() : deviceData.getName();
        mState = STATE_NONE;
    }
    // ==========================================================================


    /**
     * Запрос на соединение с устойством
     */
    public synchronized void connect() {
        if (D) Log.d(TAG, "connect to: " + connectedDevice);

        if (mState == STATE_CONNECTING) {
            if (mConnectThread != null) {
                if (D) Log.d(TAG, "cancel mConnectThread");
                mConnectThread.cancel();
                mConnectThread = null;
            }
        }

        if (mConnectedThread != null) {
            if (D) Log.d(TAG, "cancel mConnectedThread");
            mConnectedThread.cancel();
            mConnectedThread = null;
        }

        // Start the thread to connect with the given device
        mConnectThread = new ConnectThread(connectedDevice);
        mConnectThread.start();
        setState(STATE_CONNECTING);
    }
    // ==========================================================================

    /**
     * Завершение соединения
     */
    public synchronized void stop() {
        if (D) Log.d(TAG, "stop");

        if (mConnectThread != null) {
            if (D) Log.d(TAG, "cancel mConnectThread");
            mConnectThread.cancel();
            mConnectThread = null;
        }

        if (mConnectedThread != null) {
            if (D) Log.d(TAG, "cancel mConnectedThread");
            mConnectedThread.cancel();
            mConnectedThread = null;
        }

        setState(STATE_NONE);
    }
    // ==========================================================================


    /**
     * Установка внутреннего состояния устройства
     *
     * @param state - состояние
     */
    private synchronized void setState(int state) {
        if (D) Log.d(TAG, "setState() " + mState + " -> " + state);
        mState = state;
        mHandler.obtainMessage(DeviceControlActivity.MESSAGE_STATE_CHANGE, state, -1).sendToTarget();
    }
    // ==========================================================================


    /**
     * Получение состояния устройства
     */
    public synchronized int getState() {
        return mState;
    }
    // ==========================================================================


    public synchronized void connected(BluetoothSocket socket) {
        if (D) Log.d(TAG, "connected");

        // Cancel the thread that completed the connection
        if (mConnectThread != null) {
            if (D) Log.d(TAG, "cancel mConnectThread");
            mConnectThread.cancel();
            mConnectThread = null;
        }

        if (mConnectedThread != null) {
            if (D) Log.d(TAG, "cancel mConnectedThread");
            mConnectedThread.cancel();
            mConnectedThread = null;
        }

        setState(STATE_CONNECTED);

        // Send the name of the connected device back to the UI Activity
        Message msg = mHandler.obtainMessage(DeviceControlActivity.MESSAGE_DEVICE_NAME, deviceName);
        mHandler.sendMessage(msg);

        // Start the thread to manage the connection and perform transmissions
        mConnectedThread = new ConnectedThread(socket);
        mConnectedThread.start();
    }
    // ==========================================================================


    public void write(byte[] data) {
        ConnectedThread r;
        // Synchronize a copy of the ConnectedThread
        synchronized (this) {
            if (mState != STATE_CONNECTED) return;
            r = mConnectedThread;
        }

        // Perform the write unsynchronized
        if (data.length == 1) r.write(data[0]);
        else r.writeData(data);
    }
    // ==========================================================================


    private void connectionFailed() {
        if (D) Log.d(TAG, "connectionFailed");

        // Send a failure message back to the Activity
        Message msg = mHandler.obtainMessage(DeviceControlActivity.MESSAGE_TOAST);
        Bundle bundle = new Bundle();
        msg.setData(bundle);
        mHandler.sendMessage(msg);
        setState(STATE_NONE);
    }
    // ==========================================================================


    private void connectionLost() {
        // Send a failure message back to the Activity
        Message msg = mHandler.obtainMessage(DeviceControlActivity.MESSAGE_TOAST);
        Bundle bundle = new Bundle();
        msg.setData(bundle);
        mHandler.sendMessage(msg);
        setState(STATE_NONE);
    }
    // ==========================================================================


    // ==========================================================================
    private class ConnectThread extends Thread {
        private static final String TAG = "ConnectThread";
        private static final boolean D = true;

        private final BluetoothSocket mmSocket;
        private final BluetoothDevice mmDevice;

        public ConnectThread(BluetoothDevice device) {
            if (D) Log.d(TAG, "create ConnectThread");
            mmDevice = device;
            mmSocket = BluetoothUtils.createRfcommSocket(mmDevice);
        }
        // ==========================================================================

        public void run() {
            if (D) Log.d(TAG, "ConnectThread run");
            if (mmSocket == null) {
                if (D) Log.d(TAG, "unable to connect to device, socket isn't created");
                connectionFailed();
                return;
            }

            // Make a connection to the BluetoothSocket
            try {
                // This is a blocking call and will only return on a
                // successful connection or an exception
                mmSocket.connect();
            } catch (IOException e) {
                // Close the socket
                try {
                    mmSocket.close();
                } catch (IOException e2) {
                    if (D) Log.e(TAG, "unable to close() socket during connection failure", e2);
                }
                connectionFailed();
                return;
            }

            // Reset the ConnectThread because we're done
            synchronized (DeviceConnector.this) {
                mConnectThread = null;
            }

            // Start the connected thread
            connected(mmSocket);
        }
        // ==========================================================================


        /**
         * Отмена соединения
         */
        public void cancel() {
            if (D) Log.d(TAG, "ConnectThread cancel");

            if (mmSocket == null) {
                if (D) Log.d(TAG, "unable to close null socket");
                return;
            }
            try {
                mmSocket.close();
            } catch (IOException e) {
                if (D) Log.e(TAG, "close() of connect socket failed", e);
            }
        }
        // ==========================================================================
    }
    // ==========================================================================


    // ==========================================================================
    private class ConnectedThread extends Thread {
        private static final String TAG = "ConnectedThread";
        private static final boolean D = true;

        private final BluetoothSocket mmSocket;
        private final InputStream mmInStream;
        private final OutputStream mmOutStream;

        // 存储最近发送的命令
        private final List<String> sentCommands = new ArrayList<>();

        public ConnectedThread(BluetoothSocket socket) {
            if (D) Log.d(TAG, "create ConnectedThread");

            mmSocket = socket;
            InputStream tmpIn = null;
            OutputStream tmpOut = null;

            // Get the BluetoothSocket input and output streams
            try {
                tmpIn = socket.getInputStream();
                tmpOut = socket.getOutputStream();
            } catch (IOException e) {
                if (D) Log.e(TAG, "temp sockets not created", e);
            }

            mmInStream = tmpIn;
            mmOutStream = tmpOut;
        }
        // ==========================================================================
        public void run() {
            Log.i(TAG, "ConnectedThread run");
            byte[] buffer = new byte[1024];
            int bytes;
            StringBuilder ciscoOutput = new StringBuilder();

            while (true) {
                try {
                    bytes = mmInStream.read(buffer);
                    if (bytes <= 0) {
                        if (bytes == -1) break;
                        continue;
                    }

                    String rawData = new String(buffer, 0, bytes, "UTF-8");

                    // 处理思科设备输出
                    processCiscoOutput(rawData);

                } catch (IOException e) {
                    Log.e(TAG, "disconnected", e);
                    connectionLost();
                    break;
                }
            }
        }

        /**
         * 处理思科设备输出
         */
        private void processCiscoOutput(String data) {
            if (data == null || data.isEmpty()) return;

            // 按行分割
            String[] lines = data.split("\n");

            for (String line : lines) {
                String trimmedLine = line.trim();

                if (trimmedLine.isEmpty()) continue;

                // 过滤思科命令回显
                if (isCiscoCommandEcho(trimmedLine)) {
                    Log.d(TAG, "Filtered Cisco echo: " + trimmedLine);
                    continue;
                }

                // 发送到UI
                mHandler.obtainMessage(DeviceControlActivity.MESSAGE_READ,
                        -1, -1, trimmedLine).sendToTarget();
            }
        }


        /**
         * 判断是否是思科命令回显
         */
        private boolean isCiscoCommandEcho(String line) {
            if (line == null || line.isEmpty()) return false;

            // 转换为小写便于比较
            String lowerLine = line.toLowerCase();

            // 思科常见命令（最全面的列表）
            String[] ciscoCommands = {
                    // 显示命令
                    "show ", "sh ", "display ", "disp ",

                    // 配置命令
                    "configure ", "conf ", "config ",

                    // 模式切换
                    "enable", "en", "disable", "dis",

                    // 退出命令
                    "exit", "end",

                    // 文件操作
                    "copy ", "write ", "wr ", "erase ", "delete ", "del ",

                    // 网络测试
                    "ping ", "traceroute ", "trace ", "telnet ", "ssh ",

                    // 接口配置
                    "interface ", "int ", "vlan ", "port-channel ",

                    // IP配置
                    "ip ", "ipv6 ", "ip route ", "ip address ",

                    // 路由协议
                    "router ", "route ", "ospf ", "eigrp ", "bgp ", "rip ",

                    // 访问控制
                    "access-list ", "acl ", "ip access-list ",

                    // 用户管理
                    "username ", "password ", "enable secret ", "enable password ",

                    // 线路配置
                    "line ", "console ", "vty ", "aux ",

                    // 服务管理
                    "service ", "no ", "hostname ", "description ",

                    // 物理接口
                    "speed ", "duplex ", "shutdown ", "no shutdown ",

                    // 其他常用
                    "clock ", "ntp ", "logging ", "snmp ", "cdp ", "lldp ",

                    // 您的特定命令
                    "gps ", "configure terminal", "show inter * only"
            };

            // 检查是否是思科命令
            for (String cmd : ciscoCommands) {
                if (lowerLine.startsWith(cmd)) {
                    return true;
                }
            }

            // 检查是否是最近发送的命令（修复：先检查lastCommand是否为null）
            if (lastCommand != null) {
                if (line.equals(lastCommand) ||
                        line.startsWith(lastCommand) ||
                         lastCommand.startsWith(line)) {
                    return true;
                }
            }
            // 检查思科提示符
            return isCiscoPrompt(line);
        }

        /**
         * 判断是否是思科提示符
         */
        private boolean isCiscoPrompt(String line) {
            // 思科提示符格式:
            // Router>        用户模式
            // Router#        特权模式
            // Router(config)# 配置模式
            // Router(config-if)# 接口模式
            // Router(config-line)# 线路模式

            return line.matches("^[A-Za-z0-9_-]+[#>]$") ||
                    line.matches("^[A-Za-z0-9_-]+\\(config[-\\)]*\\)#$") ||
                    line.equals("#") || line.equals(">");
        }

        /**
         * 记录最后发送的命令
         */

        /**
         * 清理思科命令字符串
         */
        private String cleanCiscoCommand(String command) {
            if (command == null) return "";

            // 去除空白字符
            command = command.trim();

            // 去除回车换行符
            command = command.replace("\r", "").replace("\n", "");

            // 如果是配置终端命令，标准化
            if (command.equals("conf t") || command.equals("config t")) {
                command = "configure terminal";
            }

            // 如果是显示接口命令，标准化
            if (command.startsWith("sh int") || command.startsWith("sh interface")) {
                command = "show interface";
            }

            return command;
        }


        private String lastCommand;
        public void writeData(byte[] chunk) {
            try {
                // byte[] 转 String
                String command = new String(chunk, "UTF-8");  // 使用UTF-8编码
                // 如果需要去除空白字符
                command = command.trim();

                // 清理命令字符串
                command = cleanCiscoCommand(command);
                lastCommand = command;

                // 或者去除回车换行符
                command = command.replace("\r", "").replace("\n", "").trim();

                // 记录命令
                lastCommand = command;


                mmOutStream.write(chunk);
                mmOutStream.flush();
                // Share the sent message back to the UI Activity
                mHandler.obtainMessage(DeviceControlActivity.MESSAGE_WRITE, -1, -1, chunk).sendToTarget();
            } catch (IOException e) {
                if (D) Log.e(TAG, "Exception during write", e);
            }
        }
        // ==========================================================================



        public void write(byte command) {
            byte[] buffer = new byte[1];
            buffer[0] = command;

            try {
                mmOutStream.write(buffer);

                // Share the sent message back to the UI Activity
                mHandler.obtainMessage(DeviceControlActivity.MESSAGE_WRITE, -1, -1, buffer).sendToTarget();
            } catch (IOException e) {
                if (D) Log.e(TAG, "Exception during write", e);
            }
        }
        // ==========================================================================



        public void cancel() {
            try {
                mmSocket.close();
            } catch (IOException e) {
                if (D) Log.e(TAG, "close() of connect socket failed", e);
            }
        }
        // ==========================================================================
    }
    // ==========================================================================
}
