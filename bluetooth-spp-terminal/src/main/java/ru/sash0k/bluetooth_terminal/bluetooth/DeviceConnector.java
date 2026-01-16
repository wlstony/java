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

import java.util.regex.Pattern;

import ru.sash0k.bluetooth_terminal.DeviceData;
import ru.sash0k.bluetooth_terminal.Utils;
import ru.sash0k.bluetooth_terminal.activity.DeviceControlActivity;


public class DeviceConnector {
    private static final String TAG = "debug";
    private static final boolean D = true;

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
        Utils.log("connect to: " + connectedDevice);
        if (mState == STATE_CONNECTING) {
            if (mConnectThread != null) {
                Utils.log("cancel mConnectThread");
                mConnectThread.cancel();
                mConnectThread = null;
            }
        }

        if (mConnectedThread != null) {
            Utils.log("cancel mConnectedThread");
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
        Utils.log("stop");
        if (mConnectThread != null) {
            Utils.log("cancel mConnectThread");
            mConnectThread.cancel();
            mConnectThread = null;
        }

        if (mConnectedThread != null) {
            Utils.log("cancel mConnectedThread");
            mConnectedThread.cancel();
            mConnectedThread = null;
        }
        DeviceControlActivity.AlreadyWakedUp = false;
        DeviceControlActivity.AlreadyLogged = false;
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
        Utils.log("connected");

        // Cancel the thread that completed the connection
        if (mConnectThread != null) {
            Utils.log("cancel mConnectThread");
            mConnectThread.cancel();
            mConnectThread = null;
        }

        if (mConnectedThread != null) {
            Utils.log("cancel mConnectedThread");
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
        private static final String TAG = "debug";
        private static final boolean D = true;

        private final BluetoothSocket mmSocket;
        private final BluetoothDevice mmDevice;

        public ConnectThread(BluetoothDevice device) {
            Utils.log("create ConnectThread");
            mmDevice = device;
            mmSocket = BluetoothUtils.createRfcommSocket(mmDevice);
        }
        // ==========================================================================

        public void run() {
            Utils.log("ConnectThread run");
            if (mmSocket == null) {
                Utils.log("unable to connect to device, socket isn't created");
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
                    Utils.loge("unable to close() socket during connection failure", e2);
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
        private static final String TAG = "debug";
        private static final boolean D = true;

        private final BluetoothSocket mmSocket;
        private final InputStream mmInStream;
        private final OutputStream mmOutStream;

        public ConnectedThread(BluetoothSocket socket) {
            Utils.log("create ConnectedThread");

            mmSocket = socket;
            InputStream tmpIn = null;
            OutputStream tmpOut = null;

            // Get the BluetoothSocket input and output streams
            try {
                tmpIn = socket.getInputStream();
                tmpOut = socket.getOutputStream();
            } catch (IOException e) {
                Utils.loge("temp sockets not created", e);
            }

            mmInStream = tmpIn;
            mmOutStream = tmpOut;
        }

        public void run() {
            Utils.log("ConnectedThread run");
            byte[] buffer = new byte[1024];
            int bytes;
            StringBuilder line = new StringBuilder();

            while (true) {
                try {
                    bytes = mmInStream.read(buffer);

                    if (bytes > 0) {
                        for (int i = 0; i < bytes; i++) {
                            byte b = buffer[i];

                            if (b == '\n') {
                                // 处理完整行
                                String completeLine = line.toString().trim();
                                if (!completeLine.isEmpty()) {
                                    // 过滤控制字符和ANSI转义序列
                                    String cleanMessage = filterControlChars(completeLine);
                                    // 去掉回显
                                    cleanMessage = filterAllEcho(cleanMessage,lastCommand);
                                    cleanMessage = cleanMessage.trim();
                                    // 如果是自动登录的，去掉其它字符
                                    if (lastCommand == null) {
                                        cleanMessage = extractConnectStatus(cleanMessage);
                                    }
                                    if (!cleanMessage.isEmpty()) {
                                        mHandler.obtainMessage(DeviceControlActivity.MESSAGE_READ,
                                                -1, -1, cleanMessage).sendToTarget();
                                    }
                                }
                                line.setLength(0);
                            }
                            else if (b >= 32 && b < 127) {
                                // 可打印字符
                                line.append((char) b);
                            }
                            // 其他控制字符被忽略
                        }
                    } else if (bytes == -1) {
                        break;
                    }
                } catch (IOException e) {
                    connectionLost();
                    break;
                }
            }
        }

        /**
         * 只提取 CONNECTING 和 CONNECTED 状态信息
         */
        private String extractConnectStatus(String line) {
            Utils.log("line:"+line);
            // 检查是否包含连接状态关键字
            if (line.contains("CONNECTING") || line.contains("CONNECTED")) {
                // 可以进一步清理，只保留状态信息
                return line;
            }
            if (line.equals("#") || line.equals("# CONNECTED")) {
                Utils.log("set logged true");
                DeviceControlActivity.AlreadyLogged = true;
            }
            if (line.contains("Username:")) {
                Utils.log("set wakeup true");
                DeviceControlActivity.AlreadyWakedUp = true;
                return line;
            }
            // 如果不是连接状态信息，返回空字符串
            return "";
        }

        private String filterAllEcho(String message, String lastCommand) {
            if (message == null || message.isEmpty()) return "";
            if (lastCommand == null || lastCommand.isEmpty()) return message;

            // 常见的提示符模式
            String[] prompts = {"#", ">", "$", ">", ":", "\\[.*\\]"};

            String msgLower = message.toLowerCase().trim();
            String cmdLower = lastCommand.toLowerCase().trim();

            // 1. 检查是否是直接的命令回显（带或不带提示符）
            for (String prompt : prompts) {
                String echoPattern1 = "^" + prompt + "\\s*" + Pattern.quote(cmdLower) + "$";
                String echoPattern2 = "^" + prompt + "\\s*" + cmdLower + "$";

                if (msgLower.matches(echoPattern1) || msgLower.matches(echoPattern2)) {
                    Utils.log("过滤带提示符回显: " + message);
                    return "";
                }
            }

            // 2. 处理带错误前缀的回显（如 sho show ver）
            // 构建各种可能的错误回显模式
            String[] errorPrefixes = {
                    "sho", "sh", "show", "showw", "she", "s"
            };

            String cmdBody = extractCommandBody(cmdLower);

            for (String prompt : prompts) {
                for (String prefix1 : errorPrefixes) {
                    for (String prefix2 : errorPrefixes) {
                        // 模式如: # sho show ver
                        String pattern = "^" + prompt + "\\s*" + prefix1 + "\\s+" + prefix2 + "\\s+" + cmdBody + "$";
                        if (msgLower.matches(pattern)) {
                            Utils.log("过滤错误前缀回显: " + message);
                            return "";
                        }
                    }
                }
            }

            // 专门过滤 # sh show show xxx 模式
            if (message.trim().matches("^#\\s*sh\\s+show\\s+show\\s+.*")) {
                // 提取命令部分
                String cmdPart = message.replaceFirst("^#\\s*sh\\s+show\\s+show\\s+", "");

                // 如果是最近发送的命令，过滤掉
                {
                    cmdBody = lastCommand.replaceFirst("^(sh|show)\\s+", "");
                    if (cmdPart.equalsIgnoreCase(cmdBody)) {
                        Utils.log("过滤 # sh show show 回显: " + message);
                        return "";
                    }
                }
            }

            // 3. 专门过滤 show show inter show inter * only 这种复杂回显
            if (msgLower.contains("show show") && lastCommand.toLowerCase().contains("show")) {
                // 提取消息中最后一个 "show" 之后的部分
                int lastShowIndex = msgLower.lastIndexOf("show");
                if (lastShowIndex != -1) {
                    String afterLastShow = msgLower.substring(lastShowIndex + 4).trim(); // +4 是 "show" 的长度

                    // 提取命令中 "show" 之后的部分
                    int cmdShowIndex = cmdLower.indexOf("show");
                    String cmdAfterShow = "";
                    if (cmdShowIndex != -1) {
                        cmdAfterShow = cmdLower.substring(cmdShowIndex + 4).trim();
                    }

                    // 如果消息的最后部分与命令的 show 之后的部分相同，过滤掉
                    if (!cmdAfterShow.isEmpty() && afterLastShow.contains(cmdAfterShow)) {
                        Utils.log("过滤复杂 show 回显: " + message);
                        return "";
                    }
                }

                // 更简单的检查：如果消息中出现两次以上的 "show"，且包含命令内容
                int showCount = countOccurrences(msgLower, "show");
                if (showCount >= 2 && msgLower.contains(cmdBody)) {
                    Utils.log("过滤多次 show 回显: " + message);
                    return "";
                }
            }

            return message;
        }

        // 辅助方法：计算字符串出现次数
        private int countOccurrences(String str, String sub) {
            int count = 0;
            int idx = 0;
            while ((idx = str.indexOf(sub, idx)) != -1) {
                count++;
                idx += sub.length();
            }
            return count;
        }

        private String extractCommandBody(String fullCommand) {
            // 移除命令中的 "show " 或 "sh " 前缀
            return fullCommand.replaceFirst("^(sh|show|sho|she|showw)\\s+", "");
        }
        private String filterControlChars(String input) {
            if (input == null || input.isEmpty()) return "";

            // 方法1：使用正则表达式过滤ANSI转义序列和控制字符
            String filtered = input
                    // 移除ANSI转义序列（ESC [ ...）
                    .replaceAll("\\x1B\\[[\\x30-\\x3F]*[\\x20-\\x2F]*[\\x40-\\x7E]", "")
                    // 移除其他控制字符（包括^C）
                    .replaceAll("[\\x00-\\x1F\\x7F]", "")
                    // 移除残留的[字符（ANSI序列残留）
                    .replaceAll("\\[[A-Za-z]", "")
                    // 合并多个空格
                    .replaceAll("\\s+", " ")
                    .trim();

            return filtered;
        }

        /**
         * 记录最后发送的命令
         */
        private String lastCommand;
        public void writeData(byte[] chunk) {
            try {
                // byte[] 转 String
                String command = new String(chunk, "UTF-8");  // 使用UTF-8编码
                // 如果需要去除空白字符
                command = command.trim();
                // 或者去除回车换行符
                command = command.replace("\r", "").replace("\n", "").trim();
                lastCommand = command;
                mmOutStream.write(chunk);
                mmOutStream.flush();
                // Share the sent message back to the UI Activity
                mHandler.obtainMessage(DeviceControlActivity.MESSAGE_WRITE, -1, -1, chunk).sendToTarget();
            } catch (IOException e) {
                if (D) Log.e(TAG, "Exception during write", e);
            }
        }

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
