/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.services.telephony;

import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.telecom.Connection;
import android.telecom.ConnectionRequest;
import android.telecom.ConnectionService;
import android.telecom.DisconnectCause;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telephony.PhoneNumberUtils;
import android.telephony.ServiceState;
import android.telephony.TelephonyManager;
import android.text.TextUtils;

import com.android.internal.telephony.Call;
import com.android.internal.telephony.CallStateException;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.PhoneFactory;
import com.android.internal.telephony.PhoneProxy;
import com.android.internal.telephony.SubscriptionController;
import com.android.internal.telephony.cdma.CDMAPhone;
import com.android.phone.MMIDialogActivity;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Service for making GSM and CDMA connections.
 */
public class TelephonyConnectionService extends ConnectionService {
    private final GsmConferenceController mGsmConferenceController =
            new GsmConferenceController(this);
    private final CdmaConferenceController mCdmaConferenceController =
            new CdmaConferenceController(this);
    private ComponentName mExpectedComponentName = null;
    private EmergencyCallHelper mEmergencyCallHelper;
    private EmergencyTonePlayer mEmergencyTonePlayer;

    @Override
    public void onCreate() {
        super.onCreate();
        mExpectedComponentName = new ComponentName(this, this.getClass());
        mEmergencyTonePlayer = new EmergencyTonePlayer(this);
    }

    @Override
    public Connection onCreateOutgoingConnection(
            PhoneAccountHandle connectionManagerPhoneAccount,
            final ConnectionRequest request) {
        Log.i(this, "onCreateOutgoingConnection, request: " + request);

        Uri handle = request.getAddress();
        if (handle == null) {
            Log.d(this, "onCreateOutgoingConnection, handle is null");
            return Connection.createFailedConnection(
                    DisconnectCauseUtil.toTelecomDisconnectCause(
                            android.telephony.DisconnectCause.NO_PHONE_NUMBER_SUPPLIED,
                            "No phone number supplied"));
        }

        String scheme = handle.getScheme();
        final String number;
        if (PhoneAccount.SCHEME_VOICEMAIL.equals(scheme)) {
            // TODO: We don't check for SecurityException here (requires
            // CALL_PRIVILEGED permission).
            final Phone phone = getPhoneForAccount(request.getAccountHandle(), false);
            if (phone == null) {
                Log.d(this, "onCreateOutgoingConnection, phone is null");
                return Connection.createFailedConnection(
                        DisconnectCauseUtil.toTelecomDisconnectCause(
                                android.telephony.DisconnectCause.OUTGOING_FAILURE,
                                "Phone is null"));
            }
            number = phone.getVoiceMailNumber();
            if (TextUtils.isEmpty(number)) {
                Log.d(this, "onCreateOutgoingConnection, no voicemail number set.");
                return Connection.createFailedConnection(
                        DisconnectCauseUtil.toTelecomDisconnectCause(
                                android.telephony.DisconnectCause.VOICEMAIL_NUMBER_MISSING,
                                "Voicemail scheme provided but no voicemail number set."));
            }

            // Convert voicemail: to tel:
            handle = Uri.fromParts(PhoneAccount.SCHEME_TEL, number, null);
        } else {
            if (!PhoneAccount.SCHEME_TEL.equals(scheme)) {
                Log.d(this, "onCreateOutgoingConnection, Handle %s is not type tel", scheme);
                return Connection.createFailedConnection(
                        DisconnectCauseUtil.toTelecomDisconnectCause(
                                android.telephony.DisconnectCause.INVALID_NUMBER,
                                "Handle scheme is not type tel"));
            }

            number = handle.getSchemeSpecificPart();
            if (TextUtils.isEmpty(number)) {
                Log.d(this, "onCreateOutgoingConnection, unable to parse number");
                return Connection.createFailedConnection(
                        DisconnectCauseUtil.toTelecomDisconnectCause(
                                android.telephony.DisconnectCause.INVALID_NUMBER,
                                "Unable to parse number"));
            }
        }

        boolean isEmergencyNumber = PhoneNumberUtils.isPotentialEmergencyNumber(number);

        // Get the right phone object from the account data passed in.
        final Phone phone = getPhoneForAccount(request.getAccountHandle(), isEmergencyNumber);
        if (phone == null) {
            Log.d(this, "onCreateOutgoingConnection, phone is null");
            return Connection.createFailedConnection(
                    DisconnectCauseUtil.toTelecomDisconnectCause(
                            android.telephony.DisconnectCause.OUTGOING_FAILURE, "Phone is null"));
        }

        int state = phone.getServiceState().getState();
        boolean useEmergencyCallHelper = false;

        if (isEmergencyNumber) {
            if (state == ServiceState.STATE_POWER_OFF) {
                useEmergencyCallHelper = true;
            }
        } else {
            switch (state) {
                case ServiceState.STATE_IN_SERVICE:
                case ServiceState.STATE_EMERGENCY_ONLY:
                    break;
                case ServiceState.STATE_OUT_OF_SERVICE:
                    return Connection.createFailedConnection(
                            DisconnectCauseUtil.toTelecomDisconnectCause(
                                    android.telephony.DisconnectCause.OUT_OF_SERVICE,
                                    "ServiceState.STATE_OUT_OF_SERVICE"));
                case ServiceState.STATE_POWER_OFF:
                    return Connection.createFailedConnection(
                            DisconnectCauseUtil.toTelecomDisconnectCause(
                                    android.telephony.DisconnectCause.POWER_OFF,
                                    "ServiceState.STATE_POWER_OFF"));
                default:
                    Log.d(this, "onCreateOutgoingConnection, unknown service state: %d", state);
                    return Connection.createFailedConnection(
                            DisconnectCauseUtil.toTelecomDisconnectCause(
                                    android.telephony.DisconnectCause.OUTGOING_FAILURE,
                                    "Unknown service state " + state));
            }
        }

        final TelephonyConnection connection =
                createConnectionFor(phone, null, true /* isOutgoing */);
        if (connection == null) {
            return Connection.createFailedConnection(
                    DisconnectCauseUtil.toTelecomDisconnectCause(
                            android.telephony.DisconnectCause.OUTGOING_FAILURE,
                            "Invalid phone type"));
        }
        connection.setAddress(handle, PhoneConstants.PRESENTATION_ALLOWED);
        connection.setInitializing();
        connection.setVideoState(request.getVideoState());

        if (useEmergencyCallHelper) {
            if (mEmergencyCallHelper == null) {
                mEmergencyCallHelper = new EmergencyCallHelper(this);
            }
            mEmergencyCallHelper.startTurnOnRadioSequence(phone,
                    new EmergencyCallHelper.Callback() {
                        @Override
                        public void onComplete(boolean isRadioReady) {
                            if (connection.getState() == Connection.STATE_DISCONNECTED) {
                                // If the connection has already been disconnected, do nothing.
                            } else if (isRadioReady) {
                                connection.setInitialized();
                                placeOutgoingConnection(connection, phone, request);
                            } else {
                                Log.d(this, "onCreateOutgoingConnection, failed to turn on radio");
                                connection.setDisconnected(
                                        DisconnectCauseUtil.toTelecomDisconnectCause(
                                                android.telephony.DisconnectCause.POWER_OFF,
                                                "Failed to turn on radio."));
                                connection.destroy();
                            }
                        }
                    });

        } else {
            placeOutgoingConnection(connection, phone, request);
        }

        return connection;
    }

    @Override
    public Connection onCreateIncomingConnection(
            PhoneAccountHandle connectionManagerPhoneAccount,
            ConnectionRequest request) {
        Log.i(this, "onCreateIncomingConnection, request: " + request);

        Phone phone = getPhoneForAccount(request.getAccountHandle(), false);
        if (phone == null) {
            return Connection.createFailedConnection(
                    DisconnectCauseUtil.toTelecomDisconnectCause(
                            android.telephony.DisconnectCause.ERROR_UNSPECIFIED));
        }

        Call call = phone.getRingingCall();
        if (!call.getState().isRinging()) {
            Log.i(this, "onCreateIncomingConnection, no ringing call");
            return Connection.createFailedConnection(
                    DisconnectCauseUtil.toTelecomDisconnectCause(
                            android.telephony.DisconnectCause.INCOMING_MISSED,
                            "Found no ringing call"));
        }

        com.android.internal.telephony.Connection originalConnection =
                call.getState() == Call.State.WAITING ?
                    call.getLatestConnection() : call.getEarliestConnection();
        if (isOriginalConnectionKnown(originalConnection)) {
            Log.i(this, "onCreateIncomingConnection, original connection already registered");
            return Connection.createCanceledConnection();
        }

        Connection connection =
                createConnectionFor(phone, originalConnection, false /* isOutgoing */);
        if (connection == null) {
            connection = Connection.createCanceledConnection();
            return Connection.createCanceledConnection();
        } else {
            return connection;
        }
    }

    @Override
    public Connection onCreateUnknownConnection(PhoneAccountHandle connectionManagerPhoneAccount,
            ConnectionRequest request) {
        Log.i(this, "onCreateUnknownConnection, request: " + request);

        Phone phone = getPhoneForAccount(request.getAccountHandle(), false);
        if (phone == null) {
            return Connection.createFailedConnection(
                    DisconnectCauseUtil.toTelecomDisconnectCause(
                            android.telephony.DisconnectCause.ERROR_UNSPECIFIED));
        }

        final List<com.android.internal.telephony.Connection> allConnections = new ArrayList<>();
        final Call ringingCall = phone.getRingingCall();
        if (ringingCall.hasConnections()) {
            allConnections.addAll(ringingCall.getConnections());
        }
        final Call foregroundCall = phone.getForegroundCall();
        if (foregroundCall.hasConnections()) {
            allConnections.addAll(foregroundCall.getConnections());
        }
        final Call backgroundCall = phone.getBackgroundCall();
        if (backgroundCall.hasConnections()) {
            allConnections.addAll(phone.getBackgroundCall().getConnections());
        }

        com.android.internal.telephony.Connection unknownConnection = null;
        for (com.android.internal.telephony.Connection telephonyConnection : allConnections) {
            if (!isOriginalConnectionKnown(telephonyConnection)) {
                unknownConnection = telephonyConnection;
                break;
            }
        }

        if (unknownConnection == null) {
            Log.i(this, "onCreateUnknownConnection, did not find previously unknown connection.");
            return Connection.createCanceledConnection();
        }

        TelephonyConnection connection =
                createConnectionFor(phone, unknownConnection,
                        !unknownConnection.isIncoming() /* isOutgoing */);

        if (connection == null) {
            return Connection.createCanceledConnection();
        } else {
            connection.updateState();
            return connection;
        }
    }

    @Override
    public void onConference(Connection connection1, Connection connection2) {
        if (connection1 instanceof TelephonyConnection &&
                connection2 instanceof TelephonyConnection) {
            ((TelephonyConnection) connection1).performConference(
                (TelephonyConnection) connection2);
        }

    }

    private void placeOutgoingConnection(
            TelephonyConnection connection, Phone phone, ConnectionRequest request) {
        String number = connection.getAddress().getSchemeSpecificPart();

        com.android.internal.telephony.Connection originalConnection;
        try {
            originalConnection = phone.dial(number, request.getVideoState());
        } catch (CallStateException e) {
            Log.e(this, e, "placeOutgoingConnection, phone.dial exception: " + e);
            connection.setDisconnected(DisconnectCauseUtil.toTelecomDisconnectCause(
                    android.telephony.DisconnectCause.OUTGOING_FAILURE,
                    e.getMessage()));
            return;
        }

        if (originalConnection == null) {
            int telephonyDisconnectCause = android.telephony.DisconnectCause.OUTGOING_FAILURE;
            // On GSM phones, null connection means that we dialed an MMI code
            if (phone.getPhoneType() == PhoneConstants.PHONE_TYPE_GSM) {
                Log.d(this, "dialed MMI code");
                telephonyDisconnectCause = android.telephony.DisconnectCause.DIALED_MMI;
                final Intent intent = new Intent(this, MMIDialogActivity.class);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK |
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
                startActivity(intent);
            }
            Log.d(this, "placeOutgoingConnection, phone.dial returned null");
            connection.setDisconnected(DisconnectCauseUtil.toTelecomDisconnectCause(
                    telephonyDisconnectCause, "Connection is null"));
        } else {
            connection.setOriginalConnection(originalConnection);
        }
    }

    private TelephonyConnection createConnectionFor(
            Phone phone,
            com.android.internal.telephony.Connection originalConnection,
            boolean isOutgoing) {
        int phoneType = phone.getPhoneType();
        if (phoneType == TelephonyManager.PHONE_TYPE_GSM) {
            GsmConnection connection = new GsmConnection(originalConnection);
            mGsmConferenceController.add(connection);
            return connection;
        } else if (phoneType == TelephonyManager.PHONE_TYPE_CDMA) {
            boolean allowMute = allowMute(phone);
            CdmaConnection connection = new CdmaConnection(
                    originalConnection, mEmergencyTonePlayer, allowMute, isOutgoing);
            mCdmaConferenceController.add(connection);
            return connection;
        } else {
            return null;
        }
    }

    private boolean isOriginalConnectionKnown(
            com.android.internal.telephony.Connection originalConnection) {
        for (Connection connection : getAllConnections()) {
            TelephonyConnection telephonyConnection = (TelephonyConnection) connection;
            if (connection instanceof TelephonyConnection) {
                if (telephonyConnection.getOriginalConnection() == originalConnection) {
                    return true;
                }
            }
        }
        return false;
    }

    private Phone getPhoneForAccount(PhoneAccountHandle accountHandle, boolean isEmergency) {
        if (isEmergency) {
            return PhoneFactory.getDefaultPhone();
        }

        if (Objects.equals(mExpectedComponentName, accountHandle.getComponentName())) {
            if (accountHandle.getId() != null) {
                try {
                    int phoneId = SubscriptionController.getInstance().getPhoneId(
                            Long.parseLong(accountHandle.getId()));
                    return PhoneFactory.getPhone(phoneId);
                } catch (NumberFormatException e) {
                    Log.w(this, "Could not get subId from account: " + accountHandle.getId());
                }
            }
        }
        return null;
    }

    /**
     * Determines if the connection should allow mute.
     *
     * @param phone The current phone.
     * @return {@code True} if the connection should allow mute.
     */
    private boolean allowMute(Phone phone) {
        // For CDMA phones, check if we are in Emergency Callback Mode (ECM).  Mute is disallowed
        // in ECM mode.
        if (phone.getPhoneType() == TelephonyManager.PHONE_TYPE_CDMA) {
            PhoneProxy phoneProxy = (PhoneProxy)phone;
            CDMAPhone cdmaPhone = (CDMAPhone)phoneProxy.getActivePhone();
            if (cdmaPhone != null) {
                if (cdmaPhone.isInEcm()) {
                    return false;
                }
            }
        }

        return true;
    }
}
