/*
* Copyright (C) 2014 Samsung System LSI
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

package com.android.bluetooth.map;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

import javax.obex.ServerSession;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.IBluetoothMap;
import android.bluetooth.BluetoothUuid;
import android.bluetooth.BluetoothMap;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter.MalformedMimeTypeException;
import android.os.Handler;
import android.os.Message;
import android.os.PowerManager;
import android.os.ParcelUuid;
import android.os.RemoteException;
import android.text.TextUtils;
import android.util.Log;
import android.util.SparseArray;
import android.provider.Settings;
import android.provider.Telephony.Sms;
import android.content.IntentFilter;
import android.content.BroadcastReceiver;
import android.database.ContentObserver;

import com.android.bluetooth.R;
import com.android.bluetooth.Utils;
import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.btservice.ProfileService;
import com.android.bluetooth.btservice.ProfileService.IProfileServiceBinder;
import com.android.bluetooth.opp.BluetoothOppTransferHistory;
import com.android.bluetooth.opp.BluetoothShare;
import com.android.bluetooth.opp.Constants;

public class BluetoothMapService extends ProfileService {
    private static final String TAG = "BluetoothMapService";

    /**
     * To enable MAP DEBUG/VERBOSE logging - run below cmd in adb shell, and
     * restart com.android.bluetooth process. only enable DEBUG log:
     * "setprop log.tag.BluetoothMapService DEBUG"; enable both VERBOSE and
     * DEBUG log: "setprop log.tag.BluetoothMapService VERBOSE"
     */

    public static final boolean DEBUG = true; //TODO: set to false

    public static final boolean VERBOSE = true; //TODO: set to false

    /**
     * Intent indicating timeout for user confirmation, which is sent to
     * BluetoothMapActivity
     */
    public static final String USER_CONFIRM_TIMEOUT_ACTION =
            "com.android.bluetooth.map.USER_CONFIRM_TIMEOUT";
    private static final int USER_CONFIRM_TIMEOUT_VALUE = 25000;

    /** Intent indicating that the email settings activity should be opened*/
    public static final String ACTION_SHOW_MAPS_EMAIL_SETTINGS = "android.btmap.intent.action.SHOW_MAPS_EMAIL_SETTINGS";

    public static final int MSG_SERVERSESSION_CLOSE = 5000;

    public static final int MSG_SESSION_ESTABLISHED = 5001;

    public static final int MSG_SESSION_DISCONNECTED = 5002;

    public static final int MSG_MAS_CONNECT = 5003; // Send at MAS connect, including the MAS_ID
    public static final int MSG_MAS_CONNECT_CANCEL = 5004; // Send at auth. declined

    public static final int MSG_ACQUIRE_WAKE_LOCK = 5005;

    public static final int MSG_RELEASE_WAKE_LOCK = 5006;

    private static final String BLUETOOTH_PERM = android.Manifest.permission.BLUETOOTH;

    private static final String BLUETOOTH_ADMIN_PERM = android.Manifest.permission.BLUETOOTH_ADMIN;

    private static final int START_LISTENER = 1;

    private static final int USER_TIMEOUT = 2;

    private static final int DISCONNECT_MAP = 3;

    private static final int SHUTDOWN = 4;

    private static final int RELEASE_WAKE_LOCK_DELAY = 10000;

    private PowerManager.WakeLock mWakeLock = null;

    private static final int UPDATE_MAS_INSTANCES = 5;

    public static final int UPDATE_MAS_INSTANCES_ACCOUNT_ADDED = 0;
    public static final int UPDATE_MAS_INSTANCES_ACCOUNT_REMOVED = 1;
    public static final int UPDATE_MAS_INSTANCES_ACCOUNT_RENAMED = 2;
    public static final int UPDATE_MAS_INSTANCES_ACCOUNT_DISCONNECT = 3;

    private static final int MAS_ID_SMS_MMS = 0;

    private BluetoothAdapter mAdapter;

    private BluetoothMnsObexClient mBluetoothMnsObexClient = null;

    /* mMasInstances: A list of the active MasInstances with the key being the MasId */
    private SparseArray<BluetoothMapMasInstance> mMasInstances =
            new SparseArray<BluetoothMapMasInstance>(1);
    /* mMasInstanceMap: A list of the active MasInstances with the key being the account */
    private HashMap<BluetoothMapEmailSettingsItem, BluetoothMapMasInstance> mMasInstanceMap =
            new HashMap<BluetoothMapEmailSettingsItem, BluetoothMapMasInstance>(1);

    private BluetoothDevice mRemoteDevice = null; // The remote connected device - protect access

    private ArrayList<BluetoothMapEmailSettingsItem> mEnabledAccounts = null;
    private static String sRemoteDeviceName = null;

    private int mState;
    private BluetoothMapEmailAppObserver mAppObserver = null;
    private AlarmManager mAlarmManager = null;

    private boolean mIsWaitingAuthorization = false;
    private boolean mRemoveTimeoutMsg = false;
    private boolean mTrust = false; // Temp. fix for missing BluetoothDevice.getTrustState()
    private boolean mAccountChanged = false;

    // package and class name to which we send intent to check phone book access permission
    private static final String ACCESS_AUTHORITY_PACKAGE = "com.android.settings";
    private static final String ACCESS_AUTHORITY_CLASS =
        "com.android.settings.bluetooth.BluetoothPermissionRequest";

    private static final ParcelUuid[] MAP_UUIDS = {
        BluetoothUuid.MAP,
        BluetoothUuid.MNS,
    };

    public BluetoothMapService() {
        mState = BluetoothMap.STATE_DISCONNECTED;

    }


    private final void closeService() {
        if (DEBUG) Log.d(TAG, "MAP Service closeService in");

        if (mBluetoothMnsObexClient != null) {
            mBluetoothMnsObexClient.shutdown();
            mBluetoothMnsObexClient = null;
        }

        for(int i=0, c=mMasInstances.size(); i < c; i++) {
            mMasInstances.valueAt(i).shutdown();
        }
        mMasInstances.clear();

        if (mSessionStatusHandler != null) {
            mSessionStatusHandler.removeCallbacksAndMessages(null);
        }

        mIsWaitingAuthorization = false;
        mTrust = false;
        setState(BluetoothMap.STATE_DISCONNECTED);

        if (mWakeLock != null) {
            mWakeLock.release();
            if(VERBOSE)Log.i(TAG, "CloseService(): Release Wake Lock");
            mWakeLock = null;
        }
        mRemoteDevice = null;

        if (VERBOSE) Log.v(TAG, "MAP Service closeService out");
    }

    /**
     * Starts the RFComm listerner threads for each MAS
     * @throws IOException
     */
    private final void startRfcommSocketListeners() {
        for(int i=0, c=mMasInstances.size(); i < c; i++) {
            mMasInstances.valueAt(i).startRfcommSocketListener();
        }
    }

    /**
     * Start a MAS instance for SMS/MMS and each e-mail account.
     */
    private final void startObexServerSessions() {
        if (DEBUG) Log.d(TAG, "Map Service START ObexServerSessions()");

        // acquire the wakeLock before start Obex transaction thread
        if (mWakeLock == null) {
            PowerManager pm = (PowerManager)getSystemService(Context.POWER_SERVICE);
            mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                    "StartingObexMapTransaction");
            mWakeLock.setReferenceCounted(false);
            mWakeLock.acquire();
            if(VERBOSE)Log.i(TAG, "startObexSessions(): Acquire Wake Lock");
        }

        if(mBluetoothMnsObexClient == null) {
            mBluetoothMnsObexClient = new BluetoothMnsObexClient(mRemoteDevice, mSessionStatusHandler);
        }

        boolean connected = false;
        for(int i=0, c=mMasInstances.size(); i < c; i++) {
            try {
                if(mMasInstances.valueAt(i)
                        .startObexServerSession(mBluetoothMnsObexClient) == true) {
                    connected = true;
                }
            } catch (IOException e) {
                Log.w(TAG,"IOException occured while starting an obexServerSession restarting the listener",e);
                mMasInstances.valueAt(i).restartObexServerSession();
            } catch (RemoteException e) {
                Log.w(TAG,"RemoteException occured while starting an obexServerSession restarting the listener",e);
                mMasInstances.valueAt(i).restartObexServerSession();
            }
        }
        if(connected) {
            setState(BluetoothMap.STATE_CONNECTED);
        }

        mSessionStatusHandler.removeMessages(MSG_RELEASE_WAKE_LOCK);
        mSessionStatusHandler.sendMessageDelayed(mSessionStatusHandler
                .obtainMessage(MSG_RELEASE_WAKE_LOCK), RELEASE_WAKE_LOCK_DELAY);

        if (VERBOSE) {
            Log.v(TAG, "startObexServerSessions() success!");
        }
    }

    public Handler getHandler() {
        return mSessionStatusHandler;
    }

    /**
     * Restart a MAS instances.
     * @param masId use -1 to stop all instances
     */
    private void stopObexServerSessions(int masId) {
        if (DEBUG) Log.d(TAG, "MAP Service STOP ObexServerSessions()");

        boolean lastMasInst = true;

        if(masId != -1) {
            for(int i=0, c=mMasInstances.size(); i < c; i++) {
                BluetoothMapMasInstance masInst = mMasInstances.valueAt(i);
                if(masInst.getMasId() != masId && masInst.isStarted()) {
                    lastMasInst = false;
                }
            }
        } // Else just close down it all

        /* Shutdown the MNS client - currently must happen before MAS close */
        if(mBluetoothMnsObexClient != null && lastMasInst) {
            mBluetoothMnsObexClient.shutdown();
            mBluetoothMnsObexClient = null;
        }

        BluetoothMapMasInstance masInst = mMasInstances.get(masId); // returns null for -1
        if(masInst != null) {
            masInst.restartObexServerSession();
        } else {
            for(int i=0, c=mMasInstances.size(); i < c; i++) {
                mMasInstances.valueAt(i).restartObexServerSession();
            }
        }

        if(lastMasInst) {
            setState(BluetoothMap.STATE_DISCONNECTED);
            mTrust = false;
            mRemoteDevice = null;
            if(mAccountChanged) {
                updateMasInstances(UPDATE_MAS_INSTANCES_ACCOUNT_DISCONNECT);
            }
        }

        // Release the wake lock at disconnect
        if (mWakeLock != null && lastMasInst) {
            mSessionStatusHandler.removeMessages(MSG_ACQUIRE_WAKE_LOCK);
            mSessionStatusHandler.removeMessages(MSG_RELEASE_WAKE_LOCK);
            mWakeLock.release();
            if(VERBOSE)Log.i(TAG, "stopObexServerSessions(): Release Wake Lock");
        }
    }

    private final Handler mSessionStatusHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            if (VERBOSE) Log.v(TAG, "Handler(): got msg=" + msg.what);

            switch (msg.what) {
                case UPDATE_MAS_INSTANCES:
                    updateMasInstancesHandler();
                    break;
                case START_LISTENER:
                    if (mAdapter.isEnabled()) {
                        startRfcommSocketListeners();
                    }
                    break;
                case MSG_MAS_CONNECT:
                    onConnectHandler(msg.arg1);
                    break;
                case MSG_MAS_CONNECT_CANCEL:
                    stopObexServerSessions(-1);
                    break;
                case USER_TIMEOUT:
                    if(mIsWaitingAuthorization){
                        Intent intent = new Intent(BluetoothDevice.ACTION_CONNECTION_ACCESS_CANCEL);
                        intent.setClassName(ACCESS_AUTHORITY_PACKAGE, ACCESS_AUTHORITY_CLASS);
                        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, mRemoteDevice);
                        intent.putExtra(BluetoothDevice.EXTRA_ACCESS_REQUEST_TYPE,
                                        BluetoothDevice.REQUEST_TYPE_MESSAGE_ACCESS);
                        sendBroadcast(intent);
                        cancelUserTimeoutAlarm();
                        mIsWaitingAuthorization = false;
                        stopObexServerSessions(-1);
                    }
                    break;
                case MSG_SERVERSESSION_CLOSE:
                    stopObexServerSessions(msg.arg1);
                    break;
                case MSG_SESSION_ESTABLISHED:
                    break;
                case MSG_SESSION_DISCONNECTED:
                    // handled elsewhere
                    break;
                case DISCONNECT_MAP:
                    disconnectMap((BluetoothDevice)msg.obj);
                    break;
                case SHUTDOWN:
                    /* Ensure to call close from this handler to avoid starting new stuff
                       because of pending messages */
                    closeService();
                    break;
                case MSG_ACQUIRE_WAKE_LOCK:
                    if(VERBOSE)Log.i(TAG, "Acquire Wake Lock request message");
                    if (mWakeLock == null) {
                        PowerManager pm = (PowerManager)getSystemService(
                                          Context.POWER_SERVICE);
                        mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                                    "StartingObexMapTransaction");
                        mWakeLock.setReferenceCounted(false);
                    }
                    if(!mWakeLock.isHeld()) {
                        mWakeLock.acquire();
                        if(DEBUG)Log.i(TAG, "  Acquired Wake Lock by message");
                    }
                    mSessionStatusHandler.removeMessages(MSG_RELEASE_WAKE_LOCK);
                    mSessionStatusHandler.sendMessageDelayed(mSessionStatusHandler
                      .obtainMessage(MSG_RELEASE_WAKE_LOCK), RELEASE_WAKE_LOCK_DELAY);
                    break;
                case MSG_RELEASE_WAKE_LOCK:
                    if(VERBOSE)Log.i(TAG, "Release Wake Lock request message");
                    if (mWakeLock != null) {
                        mWakeLock.release();
                        if(DEBUG) Log.i(TAG, "  Released Wake Lock by message");
                    }
                    break;
                default:
                    break;
            }
        }
    };

    private void onConnectHandler(int masId) {
        if(mIsWaitingAuthorization == true || mRemoteDevice == null) {
            return;
        }
        BluetoothMapMasInstance masInst = mMasInstances.get(masId);
        // getTrustState() is not implemented, use local cache
        // boolean trust = mRemoteDevice.getTrustState(); // Need to ensure we are still trusted
        boolean trust = mTrust;
        if (DEBUG) Log.d(TAG, "GetTrustState() = " + trust);

        if (trust) {
            try {
                if (DEBUG) Log.d(TAG, "incoming connection accepted from: "
                    + sRemoteDeviceName + " automatically as trusted device");
                if(mBluetoothMnsObexClient != null
                   && masInst != null) {
                    masInst.startObexServerSession(mBluetoothMnsObexClient);
                } else {
                    startObexServerSessions();
                }
            } catch (IOException ex) {
                Log.e(TAG, "catch IOException starting obex server session", ex);
            } catch (RemoteException ex) {
                Log.e(TAG, "catch RemoteException starting obex server session", ex);
            }
        }
    }
    public int getState() {
        return mState;
    }

    public BluetoothDevice getRemoteDevice() {
        return mRemoteDevice;
    }
    private void setState(int state) {
        setState(state, BluetoothMap.RESULT_SUCCESS);
    }

    private synchronized void setState(int state, int result) {
        if (state != mState) {
            if (DEBUG) Log.d(TAG, "Map state " + mState + " -> " + state + ", result = "
                    + result);
            int prevState = mState;
            mState = state;
            Intent intent = new Intent(BluetoothMap.ACTION_CONNECTION_STATE_CHANGED);
            intent.putExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, prevState);
            intent.putExtra(BluetoothProfile.EXTRA_STATE, mState);
            intent.putExtra(BluetoothDevice.EXTRA_DEVICE, mRemoteDevice);
            sendBroadcast(intent, BLUETOOTH_PERM);
            AdapterService s = AdapterService.getAdapterService();
            if (s != null) {
                s.onProfileConnectionStateChanged(mRemoteDevice, BluetoothProfile.MAP,
                        mState, prevState);
            }
        }
    }

    public static String getRemoteDeviceName() {
        return sRemoteDeviceName;
    }

    public boolean disconnect(BluetoothDevice device) {
        mSessionStatusHandler.sendMessage(mSessionStatusHandler.obtainMessage(DISCONNECT_MAP, 0, 0, device));
        return true;
    }

    public boolean disconnectMap(BluetoothDevice device) {
        boolean result = false;
        if (DEBUG) Log.d(TAG, "disconnectMap");
        if (getRemoteDevice().equals(device)) {
            switch (mState) {
                case BluetoothMap.STATE_CONNECTED:
                    sendShutdownMessage();
                    result = true;
                    break;
                default:
                    break;
                }
        }
        return result;
    }

    public List<BluetoothDevice> getConnectedDevices() {
        List<BluetoothDevice> devices = new ArrayList<BluetoothDevice>();
        synchronized(this) {
            if (mState == BluetoothMap.STATE_CONNECTED && mRemoteDevice != null) {
                devices.add(mRemoteDevice);
            }
        }
        return devices;
    }

    public List<BluetoothDevice> getDevicesMatchingConnectionStates(int[] states) {
        List<BluetoothDevice> deviceList = new ArrayList<BluetoothDevice>();
        Set<BluetoothDevice> bondedDevices = mAdapter.getBondedDevices();
        int connectionState;
        synchronized (this) {
            for (BluetoothDevice device : bondedDevices) {
                ParcelUuid[] featureUuids = device.getUuids();
                if (!BluetoothUuid.containsAnyUuid(featureUuids, MAP_UUIDS)) {
                    continue;
                }
                connectionState = getConnectionState(device);
                for(int i = 0; i < states.length; i++) {
                    if (connectionState == states[i]) {
                        deviceList.add(device);
                    }
                }
            }
        }
        return deviceList;
    }

    public int getConnectionState(BluetoothDevice device) {
        synchronized(this) {
            if (getState() == BluetoothMap.STATE_CONNECTED && getRemoteDevice().equals(device)) {
                return BluetoothProfile.STATE_CONNECTED;
            } else {
                return BluetoothProfile.STATE_DISCONNECTED;
            }
        }
    }

    public boolean setPriority(BluetoothDevice device, int priority) {
        Settings.Global.putInt(getContentResolver(),
            Settings.Global.getBluetoothMapPriorityKey(device.getAddress()),
            priority);
        if (DEBUG) Log.d(TAG, "Saved priority " + device + " = " + priority);
        return true;
    }

    public int getPriority(BluetoothDevice device) {
        int priority = Settings.Global.getInt(getContentResolver(),
            Settings.Global.getBluetoothMapPriorityKey(device.getAddress()),
            BluetoothProfile.PRIORITY_UNDEFINED);
        return priority;
    }

    @Override
    protected IProfileServiceBinder initBinder() {
        return new BluetoothMapBinder(this);
    }

    @Override
    protected boolean start() {
        if (DEBUG) Log.d(TAG, "start()");
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_CONNECTION_ACCESS_REPLY);
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        filter.addAction(ACTION_SHOW_MAPS_EMAIL_SETTINGS);
        filter.addAction(USER_CONFIRM_TIMEOUT_ACTION);

        // We need two filters, since Type only applies to the ACTION_MESSAGE_SENT
        IntentFilter filterMessageSent = new IntentFilter();
        filterMessageSent.addAction(BluetoothMapContentObserver.ACTION_MESSAGE_SENT);
        try{
            filterMessageSent.addDataType("message/*");
        } catch (MalformedMimeTypeException e) {
            Log.e(TAG, "Wrong mime type!!!", e);
        }

        try {
            registerReceiver(mMapReceiver, filter);
            registerReceiver(mMapReceiver, filterMessageSent);
        } catch (Exception e) {
            Log.w(TAG,"Unable to register map receiver",e);
        }
        mAdapter = BluetoothAdapter.getDefaultAdapter();
        mAppObserver = new BluetoothMapEmailAppObserver(this, this);

        mEnabledAccounts = mAppObserver.getEnabledAccountItems();
        // Uses mEnabledAccounts, hence getEnabledAccountItems() must be called before this.
        createMasInstances();

        // start RFCOMM listener
        mSessionStatusHandler.sendMessage(mSessionStatusHandler
                .obtainMessage(START_LISTENER));

        return true;
    }

    /**
     * Call this to trigger an update of the MAS instance list.
     * No changes will be applied unless in disconnected state
     */
    public void updateMasInstances(int action) {
            mSessionStatusHandler.obtainMessage (UPDATE_MAS_INSTANCES,
                    action, 0).sendToTarget();
    }

    /**
     * Update the active MAS Instances according the difference between mEnabledDevices
     * and the current list of accounts.
     * Will only make changes if state is disconnected.
     *
     * How it works:
     * 1) Build lists of account changes from last update of mEnabledAccounts.
     *      newAccounts - accounts that have been enabled since mEnabledAccounts
     *                    was last updated.
     *      removedAccounts - Accounts that is on mEnabledAccounts, but no longer
     *                        enabled.
     *      enabledAccounts - A new list of all enabled accounts.
     * 2) Stop and remove all MasInstances on the remove list
     * 3) Add and start MAS instances for accounts on the new list.
     * Called at:
     *  - Each change in accounts
     *  - Each disconnect - before MasInstances restart.
     *
     * @return true is any changes are made, false otherwise.
     */
    private boolean updateMasInstancesHandler(){
        if(DEBUG)Log.d(TAG,"updateMasInstancesHandler() state = " + getState());
        boolean changed = false;

        if(getState() == BluetoothMap.STATE_DISCONNECTED) {
            ArrayList<BluetoothMapEmailSettingsItem> newAccountList = mAppObserver.getEnabledAccountItems();
            ArrayList<BluetoothMapEmailSettingsItem> newAccounts = null;
            ArrayList<BluetoothMapEmailSettingsItem> removedAccounts = null;
            newAccounts = new ArrayList<BluetoothMapEmailSettingsItem>();
            removedAccounts = mEnabledAccounts; // reuse the current enabled list, to track removed accounts
            for(BluetoothMapEmailSettingsItem account: newAccountList) {
                if(!removedAccounts.remove(account)) {
                    newAccounts.add(account);
                }
            }

            if(removedAccounts != null) {
                /* Remove all disabled/removed accounts */
                for(BluetoothMapEmailSettingsItem account : removedAccounts) {
                    BluetoothMapMasInstance masInst = mMasInstanceMap.remove(account);
                    if(DEBUG)Log.d(TAG,"  Removing account: " + account + " masInst = " + masInst);
                    if(masInst != null) {
                        masInst.shutdown();
                        mMasInstances.remove(masInst.getMasId());
                        changed = true;
                    }
                }
            }

            if(newAccounts != null) {
                /* Add any newly created accounts */
                for(BluetoothMapEmailSettingsItem account : newAccounts) {
                    if(DEBUG)Log.d(TAG,"  Adding account: " + account);
                    int masId = getNextMasId();
                    BluetoothMapMasInstance newInst =
                            new BluetoothMapMasInstance(this,
                                    this,
                                    account,
                                    masId,
                                    false);
                    mMasInstances.append(masId, newInst);
                    mMasInstanceMap.put(account, newInst);
                    changed = true;
                    /* Start the new instance */
                    if (mAdapter.isEnabled()) {
                        newInst.startRfcommSocketListener();
                    }
                }
            }
            mEnabledAccounts = newAccountList;
            if(VERBOSE) {
                Log.d(TAG,"  Enabled accounts:");
                for(BluetoothMapEmailSettingsItem account : mEnabledAccounts) {
                    Log.d(TAG, "   " + account);
                }
                Log.d(TAG,"  Active MAS instances:");
                for(int i=0, c=mMasInstances.size(); i < c; i++) {
                    BluetoothMapMasInstance masInst = mMasInstances.valueAt(i);
                    Log.d(TAG, "   " + masInst);
                }
            }
            mAccountChanged = false;
        } else {
            mAccountChanged = true;
        }
        return changed;
    }

    /**
     * Will return the next MasId to use.
     * Will ensure the key returned is greater than the largest key in use.
     * Unless the key 255 is in use, in which case the first free masId
     * will be returned.
     * @return
     */
    private int getNextMasId() {
        /* Find the largest masId in use */
        int largestMasId = 0;
        for(int i=0, c=mMasInstances.size(); i < c; i++) {
            int masId = mMasInstances.keyAt(i);
            if(masId > largestMasId) {
                largestMasId = masId;
            }
        }
        if(largestMasId < 0xff) {
            return largestMasId + 1;
        }
        /* If 0xff is already in use, wrap and choose the first free
         * MasId. */
        for(int i = 1; i <= 0xff; i++) {
            if(mMasInstances.get(i) == null) {
                return i;
            }
        }
        return 0xff; // This will never happen, as we only allow 10 e-mail accounts to be enabled
    }

    private void createMasInstances() {
        int masId = MAS_ID_SMS_MMS;

        // Add the SMS/MMS instance
        BluetoothMapMasInstance smsMmsInst =
                new BluetoothMapMasInstance(this,
                        this,
                        null,
                        masId,
                        true);
        mMasInstances.append(masId, smsMmsInst);
        mMasInstanceMap.put(null, smsMmsInst);

        // get list of accounts already set to be visible through MAP
        for(BluetoothMapEmailSettingsItem account : mEnabledAccounts) {
            masId++;  // SMS/MMS is masId=0, increment before adding next
            BluetoothMapMasInstance newInst =
                    new BluetoothMapMasInstance(this,
                            this,
                            account,
                            masId,
                            false);
            mMasInstances.append(masId, newInst);
            mMasInstanceMap.put(account, newInst);
        }
    }

    @Override
    protected boolean stop() {
        if (DEBUG) Log.d(TAG, "stop()");
        try {
            unregisterReceiver(mMapReceiver);
            mAppObserver.shutdown();
        } catch (Exception e) {
            Log.w(TAG,"Unable to unregister map receiver",e);
        }

        setState(BluetoothMap.STATE_DISCONNECTED, BluetoothMap.RESULT_CANCELED);
        sendShutdownMessage();
        return true;
    }

    public boolean cleanup()  {
        if (DEBUG) Log.d(TAG, "cleanup()");
        setState(BluetoothMap.STATE_DISCONNECTED, BluetoothMap.RESULT_CANCELED);
        // TODO: Change to use message? - do we need to wait for completion?
        closeService();
        return true;
    }

    /**
     * Called from each MAS instance when a connection is received.
     * @param remoteDevice The device connecting
     * @param masInst a reference to the calling MAS instance.
     * @return
     */
    public boolean onConnect(BluetoothDevice remoteDevice, BluetoothMapMasInstance masInst) {

        boolean sendIntent=false;
        // As this can be called from each MasInstance, we need to lock access to member variables
        synchronized(this) {
            if(mRemoteDevice == null) {
                mRemoteDevice = remoteDevice;
                sRemoteDeviceName = mRemoteDevice.getName();
                // In case getRemoteName failed and return null
                if (TextUtils.isEmpty(sRemoteDeviceName)) {
                    sRemoteDeviceName = getString(R.string.defaultname);
                }

                if(mTrust == false) {
                    sendIntent = true;
                    mIsWaitingAuthorization = true;
                    setUserTimeoutAlarm();
                }
            } else if (!mRemoteDevice.equals(remoteDevice)) {
                Log.w(TAG, "Unexpected connection from a second Remote Device received. name: " +
                            ((remoteDevice==null)?"unknown":remoteDevice.getName()));
                return false; /* The connecting device is different from what is already
                                 connected, reject the connection. */
            } // Else second connection to same device, just continue
        }


        if(sendIntent == true) {
            /* This will trigger */
            Intent intent = new
                Intent(BluetoothDevice.ACTION_CONNECTION_ACCESS_REQUEST);
            intent.setClassName(ACCESS_AUTHORITY_PACKAGE, ACCESS_AUTHORITY_CLASS);
            intent.putExtra(BluetoothDevice.EXTRA_ACCESS_REQUEST_TYPE,
                            BluetoothDevice.REQUEST_TYPE_MESSAGE_ACCESS);
            intent.putExtra(BluetoothDevice.EXTRA_DEVICE, mRemoteDevice);
            sendBroadcast(intent, BLUETOOTH_ADMIN_PERM);

            if (DEBUG) Log.d(TAG, "waiting for authorization for connection from: "
                    + sRemoteDeviceName);
            //Queue USER_TIMEOUT to disconnect MAP OBEX session. If user doesn't
            //accept or reject authorization request




        } else {
            /* Signal to the service that we have a incoming connection. */
            sendConnectMessage(masInst.getMasId());
        }
        return true;
    };


    private void setUserTimeoutAlarm(){
        if(DEBUG)Log.d(TAG,"SetUserTimeOutAlarm()");
        if(mAlarmManager == null){
            mAlarmManager =(AlarmManager) this.getSystemService (Context.ALARM_SERVICE);
        }
        mRemoveTimeoutMsg = true;
        Intent timeoutIntent =
                new Intent(USER_CONFIRM_TIMEOUT_ACTION);
        PendingIntent pIntent = PendingIntent.getBroadcast(this, 0, timeoutIntent, 0);
        mAlarmManager.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis()+USER_CONFIRM_TIMEOUT_VALUE,pIntent);
    }

    private void cancelUserTimeoutAlarm(){
        if(DEBUG)Log.d(TAG,"cancelUserTimeOutAlarm()");
        Intent intent = new Intent(this, BluetoothMapService.class);
        PendingIntent sender = PendingIntent.getBroadcast(this, 0, intent, 0);
        AlarmManager alarmManager = (AlarmManager) this.getSystemService(Context.ALARM_SERVICE);
        alarmManager.cancel(sender);
        mRemoveTimeoutMsg = false;
    }

    private void sendConnectMessage(int masId) {
        if(mSessionStatusHandler != null) {
            Message msg = mSessionStatusHandler.obtainMessage(MSG_MAS_CONNECT, masId, 0);
            msg.sendToTarget();
        } // Can only be null during shutdown
    }
    private void sendConnectTimeoutMessage() {
        if (DEBUG) Log.d(TAG, "sendConnectTimeoutMessage()");
        if(mSessionStatusHandler != null) {
            Message msg = mSessionStatusHandler.obtainMessage(USER_TIMEOUT);
            msg.sendToTarget();
        } // Can only be null during shutdown
    }
    private void sendConnectCancelMessage() {
        if(mSessionStatusHandler != null) {
            Message msg = mSessionStatusHandler.obtainMessage(MSG_MAS_CONNECT_CANCEL);
            msg.sendToTarget();
        } // Can only be null during shutdown
    }

    private void sendShutdownMessage() {
        /* Any pending messages are no longer valid.
        To speed up things, simply delete them. */
        if (mRemoveTimeoutMsg) {
            Intent timeoutIntent =
                    new Intent(USER_CONFIRM_TIMEOUT_ACTION);
            sendBroadcast(timeoutIntent, BLUETOOTH_PERM);
            mIsWaitingAuthorization = false;
            cancelUserTimeoutAlarm();
        }
        mSessionStatusHandler.removeCallbacksAndMessages(null);
        // Request release of all resources
        mSessionStatusHandler.obtainMessage(SHUTDOWN).sendToTarget();
    }

    private MapBroadcastReceiver mMapReceiver = new MapBroadcastReceiver();

    private class MapBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (DEBUG) Log.d(TAG, "onReceive");
            String action = intent.getAction();
            if (action.equals(BluetoothAdapter.ACTION_STATE_CHANGED)) {
                int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE,
                                               BluetoothAdapter.ERROR);
                if (state == BluetoothAdapter.STATE_TURNING_OFF) {
                    if (DEBUG) Log.d(TAG, "STATE_TURNING_OFF");
                    sendShutdownMessage();
                } else if (state == BluetoothAdapter.STATE_ON) {
                    if (DEBUG) Log.d(TAG, "STATE_ON");
                    // start RFCOMM listener
                    mSessionStatusHandler.sendMessage(mSessionStatusHandler
                                  .obtainMessage(START_LISTENER));
                }
            }else if (action.equals(USER_CONFIRM_TIMEOUT_ACTION)){
                if (DEBUG) Log.d(TAG, "USER_CONFIRM_TIMEOUT ACTION Received.");
                // send us self a message about the timeout.
                sendConnectTimeoutMessage();
            } else if (action.equals(BluetoothDevice.ACTION_CONNECTION_ACCESS_REPLY)) {
                int requestType = intent.getIntExtra(BluetoothDevice.EXTRA_ACCESS_REQUEST_TYPE,
                                               BluetoothDevice.REQUEST_TYPE_PHONEBOOK_ACCESS);
                if (DEBUG) Log.d(TAG, "Received ACTION_CONNECTION_ACCESS_REPLY:" +
                           requestType + "isWaitingAuthorization:" + mIsWaitingAuthorization);
                if ((!mIsWaitingAuthorization) ||
                    (requestType != BluetoothDevice.REQUEST_TYPE_MESSAGE_ACCESS)) {
                    // this reply is not for us
                    return;
                }

                mIsWaitingAuthorization = false;
                if (mRemoveTimeoutMsg) {
                    mSessionStatusHandler.removeMessages(USER_TIMEOUT);
                    cancelUserTimeoutAlarm();
                    setState(BluetoothMap.STATE_DISCONNECTED);
                }

                if (intent.getIntExtra(BluetoothDevice.EXTRA_CONNECTION_ACCESS_RESULT,
                                       BluetoothDevice.CONNECTION_ACCESS_NO) ==
                    BluetoothDevice.CONNECTION_ACCESS_YES) {
                    // Bluetooth connection accepted by user
                    if (intent.getBooleanExtra(BluetoothDevice.EXTRA_ALWAYS_ALLOWED, false)) {
                        // Not implemented in BluetoothDevice
                        //boolean result = mRemoteDevice.setTrust(true);
                        //if (DEBUG) Log.d(TAG, "setTrust() result=" + result);
                    }
                    mTrust = true;
                    sendConnectMessage(-1); // -1 indicates all MAS instances
                } else {
                    // Auth. declined by user, serverSession should not be running, but
                    // call stop anyway to restart listener.
                    mTrust = false;
                    sendConnectCancelMessage();
                }
            } else if (action.equals(ACTION_SHOW_MAPS_EMAIL_SETTINGS)) {
                Log.v(TAG, "Received ACTION_SHOW_MAPS_EMAIL_SETTINGS.");

                Intent in = new Intent(context, BluetoothMapEmailSettings.class);
                in.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                context.startActivity(in);
            } else if (action.equals(BluetoothMapContentObserver.ACTION_MESSAGE_SENT)) {
                BluetoothMapMasInstance masInst = null;
                int result = getResultCode();
                boolean handled = false;
                if(mMasInstances != null && (masInst = mMasInstances.get(MAS_ID_SMS_MMS)) != null) {
                    intent.putExtra(BluetoothMapContentObserver.EXTRA_MESSAGE_SENT_RESULT, result);
                    if(masInst.handleSmsSendIntent(context, intent)) {
                        // The intent was handled by the mas instance it self
                        handled = true;
                    }
                }
                if(handled == false)
                {
                    /* We do not have a connection to a device, hence we need to move
                       the SMS to the correct folder. */
                    BluetoothMapContentObserver.actionMessageSentDisconnected(context, intent, result);
                }
            } else if (action.equals(BluetoothDevice.ACTION_ACL_DISCONNECTED) &&
                    mIsWaitingAuthorization) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);

                if (mRemoteDevice == null || device == null) {
                    Log.e(TAG, "Unexpected error!");
                    return;
                }

                if (DEBUG) Log.d(TAG,"ACL disconnected for "+ device);

                if (mRemoteDevice.equals(device) && mRemoveTimeoutMsg) {
                    // Send any pending timeout now, as ACL got disconnected.
                    mSessionStatusHandler.removeMessages(USER_TIMEOUT);

                    Intent timeoutIntent =
                            new Intent(BluetoothDevice.ACTION_CONNECTION_ACCESS_CANCEL);
                    timeoutIntent.putExtra(BluetoothDevice.EXTRA_DEVICE, mRemoteDevice);
                    timeoutIntent.putExtra(BluetoothDevice.EXTRA_ACCESS_REQUEST_TYPE,
                                           BluetoothDevice.REQUEST_TYPE_MESSAGE_ACCESS);
                    sendBroadcast(timeoutIntent, BLUETOOTH_PERM);
                    mIsWaitingAuthorization = false;
                    mRemoveTimeoutMsg = false;

                }
            }
        }
    };

    //Binder object: Must be static class or memory leak may occur
    /**
     * This class implements the IBluetoothMap interface - or actually it validates the
     * preconditions for calling the actual functionality in the MapService, and calls it.
     */
    private static class BluetoothMapBinder extends IBluetoothMap.Stub
        implements IProfileServiceBinder {
        private BluetoothMapService mService;

        private BluetoothMapService getService() {
            if (!Utils.checkCaller()) {
                Log.w(TAG,"MAP call not allowed for non-active user");
                return null;
            }

            if (mService != null && mService.isAvailable()) {
                mService.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
                return mService;
            }
            return null;
        }

        BluetoothMapBinder(BluetoothMapService service) {
            if (VERBOSE) Log.v(TAG, "BluetoothMapBinder()");
            mService = service;
        }

        public boolean cleanup()  {
            mService = null;
            return true;
        }

        public int getState() {
            if (VERBOSE) Log.v(TAG, "getState()");
            BluetoothMapService service = getService();
            if (service == null) return BluetoothMap.STATE_DISCONNECTED;
            return getService().getState();
        }

        public BluetoothDevice getClient() {
            if (VERBOSE) Log.v(TAG, "getClient()");
            BluetoothMapService service = getService();
            if (service == null) return null;
            Log.v(TAG, "getClient() - returning " + service.getRemoteDevice());
            return service.getRemoteDevice();
        }

        public boolean isConnected(BluetoothDevice device) {
            if (VERBOSE) Log.v(TAG, "isConnected()");
            BluetoothMapService service = getService();
            if (service == null) return false;
            return service.getState() == BluetoothMap.STATE_CONNECTED && service.getRemoteDevice().equals(device);
        }

        public boolean connect(BluetoothDevice device) {
            if (VERBOSE) Log.v(TAG, "connect()");
            BluetoothMapService service = getService();
            if (service == null) return false;
            return false;
        }

        public boolean disconnect(BluetoothDevice device) {
            if (VERBOSE) Log.v(TAG, "disconnect()");
            BluetoothMapService service = getService();
            if (service == null) return false;
            return service.disconnect(device);
        }

        public List<BluetoothDevice> getConnectedDevices() {
            if (VERBOSE) Log.v(TAG, "getConnectedDevices()");
            BluetoothMapService service = getService();
            if (service == null) return new ArrayList<BluetoothDevice>(0);
            return service.getConnectedDevices();
        }

        public List<BluetoothDevice> getDevicesMatchingConnectionStates(int[] states) {
            if (VERBOSE) Log.v(TAG, "getDevicesMatchingConnectionStates()");
            BluetoothMapService service = getService();
            if (service == null) return new ArrayList<BluetoothDevice>(0);
            return service.getDevicesMatchingConnectionStates(states);
        }

        public int getConnectionState(BluetoothDevice device) {
            if (VERBOSE) Log.v(TAG, "getConnectionState()");
            BluetoothMapService service = getService();
            if (service == null) return BluetoothProfile.STATE_DISCONNECTED;
            return service.getConnectionState(device);
        }

        public boolean setPriority(BluetoothDevice device, int priority) {
            BluetoothMapService service = getService();
            if (service == null) return false;
            return service.setPriority(device, priority);
        }

        public int getPriority(BluetoothDevice device) {
            BluetoothMapService service = getService();
            if (service == null) return BluetoothProfile.PRIORITY_UNDEFINED;
            return service.getPriority(device);
        }
    }
}
