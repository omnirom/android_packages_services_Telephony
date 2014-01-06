/*
 * Copyright (C) 2013 ParanoidAndroid Project
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

package com.android.phone;

import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneConstants;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.util.Log;

 public class BatterySaverManager extends BroadcastReceiver {

    private static final String STORED_NETWORK_TYPE_KEY = "stored_network_type";
    private static final String STORED_BATTERY_SAVE_STATUS = "battery_save_status";

    static final int preferredNetworkMode = Phone.PREFERRED_NT_MODE;
    static final int batteryMode = Phone.NT_MODE_GSM_ONLY;

    private AudioManager audioManager;
    private SharedPreferences mStoredNetwork;
    private boolean updateDefault = true;
    private ModeHandler modeHandler;
    private Phone mPhone;

    @Override
    public void onReceive(Context context, Intent intent) {
        modeHandler = new ModeHandler();
        mPhone = PhoneGlobals.getPhone();
        mStoredNetwork = mPhone.getContext()
                       .getSharedPreferences("StoredNetworkType", 0);

        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_USER_PRESENT);

        BroadcastReceiver screenReceiver = new ScreenReceiver();

        mPhone.getContext().registerReceiver(screenReceiver, filter);

        audioManager = (AudioManager) mPhone.getContext()
                                    .getSystemService(Context.AUDIO_SERVICE);
    }

    private class ScreenReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            int enabled = mStoredNetwork.getInt(STORED_BATTERY_SAVE_STATUS, 0);
            if(intent.getAction().equals(Intent.ACTION_SCREEN_OFF) && (enabled == 1) && updateDefault) {
                // If music is playing do not change network type
                if (audioManager.isMusicActive()) return;

                setNetworkTo2G();

            } else if(intent.getAction().equals(Intent.ACTION_USER_PRESENT) && enabled == 1) {
                resetNetwork();
            }
        }
    }

    private void setNetworkTo2G() {
        modeHandler.postDelayed(new Runnable() {
            public void run() {
                int currentNetworkMode = android.provider.Settings.Global.getInt(mPhone.getContext()
                                        .getContentResolver(), android.provider.Settings.Global
                                        .PREFERRED_NETWORK_MODE, preferredNetworkMode);

                // store current preferred network mode
                mStoredNetwork.edit().putInt(STORED_NETWORK_TYPE_KEY, currentNetworkMode).apply();
                // update network to 2G
                updateNetworkType(batteryMode);
                updateDefault = false;
            }
        }, 30 * 1000); // 30 second delay before switching to 2G
    }

    private void resetNetwork() {
        if (updateDefault) {
            // cancel changing network mode if device is unlocked before 30 second delay
            modeHandler.removeCallbacksAndMessages(null);
            return;
        }
        updateDefault = true;
        // reset network type to original state
        int storedNetworkMode = mStoredNetwork
                                .getInt(STORED_NETWORK_TYPE_KEY, preferredNetworkMode);
        updateNetworkType(storedNetworkMode);
    }

    private void updateNetworkType(int networkType) {
        //set the Settings.System
        android.provider.Settings.Global.putInt(mPhone.getContext().getContentResolver(),
                    android.provider.Settings.Global.PREFERRED_NETWORK_MODE, networkType);
        //Set the Modem
        mPhone.setPreferredNetworkType(networkType,
                    modeHandler.obtainMessage(ModeHandler.MESSAGE_SET_PREFERRED_NETWORK_TYPE));
    }

    private class ModeHandler extends Handler {

        static final int MESSAGE_SET_PREFERRED_NETWORK_TYPE = 1;

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MESSAGE_SET_PREFERRED_NETWORK_TYPE:
                    setNetwork(msg);
                    break;
            }
        }

        private void setNetwork(Message msg) {
            AsyncResult ar = (AsyncResult) msg.obj;

            if (ar.exception == null) {
                android.provider.Settings.Global.putInt(mPhone.getContext().getContentResolver(),
                        android.provider.Settings.Global.PREFERRED_NETWORK_MODE,
                        preferredNetworkMode);
            }
        }
    }
}
