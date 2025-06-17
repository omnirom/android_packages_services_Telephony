/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.phone.testapps.satellitetestapp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.CancellationSignal;
import android.telephony.satellite.EnableRequestAttributes;
import android.telephony.satellite.SatelliteManager;
import android.telephony.satellite.stub.SatelliteResult;
import android.util.Log;

import java.util.Objects;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public class SatelliteTestAppReceiver extends BroadcastReceiver {
    private static final String TAG = "SatelliteTestAppRcvr";

    private static final long TEST_REQUEST_TIMEOUT = TimeUnit.SECONDS.toMillis(3);
    private static final String TEST_SATELLITE_TOKEN = "SATELLITE_TOKEN";
    private static final String ACTION = "com.android.phone.testapps.satellitetestapp.RECEIVER";
    private static final String ACTION_PROVISION = "provision";
    private static final String ACTION_DEPROVISION = "deprovision";
    private static final String ACTION_ENABLE = "enable";
    private static final String ACTION_DISABLE = "disable";
    private static final String PARAM_ACTION_KEY = "action_key";
    private static final String PARAM_DEMO_MODE = "demo_mode";

    private static SatelliteManager mSatelliteManager;


    @Override
    public void onReceive(Context context, Intent intent) {
        Log.d(TAG, "onReceive: intent: " + intent.toString());

        String action = intent.getAction();
        if (!Objects.equals(action, ACTION)) {
            Log.d(TAG, "Unsupported action: " + action + ", exiting.");
            return;
        }

        String param = intent.getStringExtra(PARAM_ACTION_KEY);
        if (param == null) {
            Log.d(TAG, "No param provided, exiting");
            return;
        }

        if (mSatelliteManager == null) {
            mSatelliteManager = context.getSystemService(SatelliteManager.class);
        }

        if (mSatelliteManager == null) {
            Log.d(TAG, "Satellite Manager is not available, exiting.");
            return;
        }

        switch (param) {
            case ACTION_PROVISION -> provisionSatellite();
            case ACTION_DEPROVISION -> deprovisionSatellite();
            case ACTION_ENABLE -> {
                boolean demoMode = intent.getBooleanExtra(PARAM_DEMO_MODE, true);
                enableSatellite(demoMode);
            }
            case ACTION_DISABLE -> disableSatellite();
            default -> Log.d(TAG, "Unsupported param:" + param);
        }
    }

    private void provisionSatellite() {
        CancellationSignal cancellationSignal = new CancellationSignal();
        LinkedBlockingQueue<Integer> error = new LinkedBlockingQueue<>(1);
        String mText = "This is test provision data.";
        byte[] testProvisionData = mText.getBytes();
        mSatelliteManager.provisionService(TEST_SATELLITE_TOKEN, testProvisionData,
                cancellationSignal, Runnable::run, error::offer);
        try {
            Integer value = error.poll(TEST_REQUEST_TIMEOUT, TimeUnit.MILLISECONDS);
            if (value == null) {
                Log.d(TAG, "Timed out to provision the satellite");
            } else if (value != SatelliteResult.SATELLITE_RESULT_SUCCESS) {
                Log.d(TAG, "Failed to provision the satellite, error ="
                        + SatelliteErrorUtils.mapError(value));
            } else {
                Log.d(TAG, "Successfully provisioned the satellite");
            }
        } catch (InterruptedException e) {
            Log.d(TAG, "Provision SatelliteService exception caught =" + e);
        }
    }

    private void deprovisionSatellite() {
        LinkedBlockingQueue<Integer> error = new LinkedBlockingQueue<>(1);
        mSatelliteManager.deprovisionService(TEST_SATELLITE_TOKEN, Runnable::run,
                error::offer);
        try {
            Integer value = error.poll(TEST_REQUEST_TIMEOUT, TimeUnit.MILLISECONDS);
            if (value == null) {
                Log.d(TAG, "Timed out to deprovision the satellite");
            } else if (value != SatelliteResult.SATELLITE_RESULT_SUCCESS) {
                Log.d(TAG, "Failed to deprovision the satellite, error ="
                        + SatelliteErrorUtils.mapError(value));
            } else {
                Log.d(TAG, "Successfully deprovisioned the satellite");
            }
        } catch (InterruptedException e) {
            Log.d(TAG, "Deprovision SatelliteService exception caught =" + e);
        }
    }

    private void enableSatellite(boolean isDemoMode) {
        LinkedBlockingQueue<Integer> error = new LinkedBlockingQueue<>(1);
        mSatelliteManager.requestEnabled(
                new EnableRequestAttributes.Builder(true)
                        .setDemoMode(isDemoMode)
                        .setEmergencyMode(true)
                        .build(), Runnable::run, error::offer);
        Log.d(TAG, "enableSatelliteApp: isDemoMode=" + isDemoMode);
        try {
            Integer value = error.poll(TEST_REQUEST_TIMEOUT, TimeUnit.MILLISECONDS);
            if (value == null) {
                Log.d(TAG, "Timed out to enable the satellite");
            } else if (value != SatelliteResult.SATELLITE_RESULT_SUCCESS) {
                Log.d(TAG, "Failed to enable the satellite, error ="
                        + SatelliteErrorUtils.mapError(value));
            } else {
                Log.d(TAG, "Successfully enabled the satellite");
            }
        } catch (InterruptedException e) {
            Log.d(TAG, "Enable SatelliteService exception caught =" + e);
        }
    }

    private void disableSatellite() {
        LinkedBlockingQueue<Integer> error = new LinkedBlockingQueue<>(1);
        mSatelliteManager.requestEnabled(new EnableRequestAttributes.Builder(false).build(),
                Runnable::run, error::offer);
        try {
            Integer value = error.poll(TEST_REQUEST_TIMEOUT, TimeUnit.MILLISECONDS);
            if (value == null) {
                Log.d(TAG, "Timed out to enable the satellite");
            } else if (value != SatelliteResult.SATELLITE_RESULT_SUCCESS) {
                Log.d(TAG, "Failed to enable the satellite, error ="
                        + SatelliteErrorUtils.mapError(value));
            } else {
                Log.d(TAG, "Successfully disabled the satellite");
            }
        } catch (InterruptedException e) {
            Log.d(TAG, "Disable SatelliteService exception caught =" + e);
        }
    }
}
