/*
 * Copyright (C) 2013 Android Open Kang Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.phone;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.AlarmManager;
import android.content.ComponentName;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.content.ContentResolver;
import android.database.ContentObserver;
import android.database.Cursor;
import android.os.IBinder;
import android.os.UserHandle;
import android.os.Handler;
import android.provider.Telephony.Sms.Intents;
import android.provider.Settings;
import android.telephony.PhoneStateListener;
import android.telephony.SmsMessage;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.provider.Settings;
import android.provider.ContactsContract.PhoneLookup;
import android.net.Uri;
import com.android.internal.telephony.SmsApplication;

import com.android.internal.util.slim.QuietHoursHelper;

import java.util.Calendar;

public class QuietHoursHandler {

    private final static String TAG = "QuietHoursHandler";
    private final static boolean DEBUG = false;

    private static QuietHoursHandler sInstance;

    private TelephonyManager mTelephony;
    private Context mContext;
    private boolean mIncomingCall;
    private boolean mKeepCounting;
    private String mIncomingNumber;
    private String mNumberSent;
    private int mMinuteSent;
    private int mBypassCallCount;
    private int mMinutes;
    private int mDay;
    private CallListener mPhoneStateListener;
    private SmsReceiver mSmsReceiver;
    private SettingsObserver mSettingsObserver;
    private Handler mHandler = new Handler();

    private static final int DEFAULT_DISABLED = 0;
    private static final int ALL_NUMBERS = 1;
    private static final int CONTACTS_ONLY = 2;
    private static final int STARRED_ONLY = 3;
    private static final int BYPASS_ONLY = 4;
    private static final int TIME_LIMIT = 30; // 30 minute bypass limit
    private static final int FULL_DAY = 1440; // 1440 minutes in a day

    public static QuietHoursHandler getInstance(Context context) {
        if (sInstance == null){
            sInstance = new QuietHoursHandler(context);
         }
         return sInstance;
    }

    private QuietHoursHandler(Context context) {
        mContext = context;
        mSettingsObserver = new SettingsObserver(mHandler);
        mSettingsObserver.observe();
    }

    private class CallListener extends PhoneStateListener {

        @Override
        public void onCallStateChanged(int state, String incomingNumber) {
            if (state == TelephonyManager.CALL_STATE_RINGING) {
                mIncomingCall = true;
                mIncomingNumber = incomingNumber;
                final int bypassPreference = Settings.System.getIntForUser(mContext.getContentResolver(),
                        Settings.System.QUIET_HOURS_CALL_BYPASS, 0, UserHandle.USER_CURRENT);
                final boolean isContact = isContact(mIncomingNumber);
                boolean isStarred = false;
                final int userAutoSms = Settings.System.getIntForUser(mContext.getContentResolver(),
                        Settings.System.QUIET_HOURS_AUTO_CALL, 0, UserHandle.USER_CURRENT); 
                final int callNumber = Settings.System.getIntForUser(mContext.getContentResolver(),
                        Settings.System.QUIET_HOURS_CALL_BYPASS_COUNT, 0, UserHandle.USER_CURRENT); 
                final int muteType = Settings.System.getIntForUser(mContext.getContentResolver(),
                        Settings.System.QUIET_HOURS_RINGER, Settings.System.QUIET_HOURS_RINGER_ALLOW_ALL,
                        UserHandle.USER_CURRENT);

                if ((bypassPreference != DEFAULT_DISABLED
                        || userAutoSms != DEFAULT_DISABLED
                        || QuietHoursHelper.hasCallBypass(mContext))
                        && QuietHoursHelper.inQuietHours(mContext, null)
                        // if bypass contacts ringer is enabled dont
                        // run also bypass alarm
                        && muteType != Settings.System.QUIET_HOURS_RINGER_WHITELIST_ONLY) {

                    if (isContact) {
                        isStarred = isStarred(mIncomingNumber);
                    }

                    if (!mKeepCounting) {
                        mKeepCounting = true;
                        mBypassCallCount = 0;
                        mDay = returnDayOfMonth();
                        mMinutes = returnTimeInMinutes();
                    }

                    boolean timeConstraintMet = returnTimeConstraintMet(mMinutes, mDay);
                    if (timeConstraintMet) {
                        switch (bypassPreference) {
                            case DEFAULT_DISABLED:
                                break;
                            case ALL_NUMBERS:
                                mBypassCallCount++;
                                break;
                            case CONTACTS_ONLY:
                                if (isContact) {
                                    mBypassCallCount++;
                                }
                                break;
                            case STARRED_ONLY:
                                if (isStarred) {
                                    mBypassCallCount++;
                                }
                                break;
                        }

                        if (mBypassCallCount == 0) {
                            mKeepCounting = false;
                        }
                    } else {
                        switch (bypassPreference) {
                            case DEFAULT_DISABLED:
                                break;
                            case ALL_NUMBERS:
                                mBypassCallCount = 1;
                                break;
                            case CONTACTS_ONLY:
                                if (isContact) {
                                    mBypassCallCount = 1;
                                } else {
                                    // Reset call count and time at next call
                                    mKeepCounting = false;
                                }
                                break;
                            case STARRED_ONLY:
                                if (isStarred) {
                                    mBypassCallCount = 1;
                                } else {
                                    // Reset call count and time at next call
                                    mKeepCounting = false;
                                }
                                break;
                        }
                        mDay = returnDayOfMonth();
                        mMinutes = returnTimeInMinutes();
                    }
                    if (((mBypassCallCount == callNumber)
                            && timeConstraintMet)
                            || QuietHoursHelper.isCallBypass(mContext, mIncomingNumber)) {
                        // Don't auto-respond if alarm fired
                        mIncomingCall = false;
                        mKeepCounting = false;
                        startAlarm(mIncomingNumber);
                    }
                }
                if (state == TelephonyManager.CALL_STATE_OFFHOOK) {
                    // Don't message or alarm if call was answered
                    mIncomingCall = false;
                    // Call answered, reset Incoming number
                    // Stop AlarmSound
                    mKeepCounting = false;
                    Intent serviceIntent = new Intent(mContext, QuietHoursAlarmService.class);
                    mContext.stopService(serviceIntent);
                }
                if (state == TelephonyManager.CALL_STATE_IDLE && mIncomingCall) {
                    // Call Received and now inactive
                    mIncomingCall = false;

                    if (userAutoSms != DEFAULT_DISABLED) {
                        checkTimeAndNumber(mIncomingNumber, userAutoSms, isContact);
                    }
                }
            }
        }
    }

    private class SmsReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            SmsMessage[] msgs = Intents.getMessagesFromIntent(intent);
            SmsMessage msg = msgs[0];
            String incomingNumber = msg.getOriginatingAddress();
            boolean nawDawg = false;
            final int userAutoSms = Settings.System.getIntForUser(mContext.getContentResolver(),
                    Settings.System.QUIET_HOURS_AUTO_SMS, 0, UserHandle.USER_CURRENT);
            final int bypassCodePref = Settings.System.getIntForUser(mContext.getContentResolver(),
                    Settings.System.QUIET_HOURS_SMS_BYPASS, 0, UserHandle.USER_CURRENT);

            final boolean isContact = isContact(incomingNumber);
            boolean isStarred = false;

            if (isContact) {
                isStarred = isStarred(incomingNumber);
            }

            if ((bypassCodePref != DEFAULT_DISABLED
                   || userAutoSms != DEFAULT_DISABLED
                   || QuietHoursHelper.hasMessageBypass(mContext))
                    && QuietHoursHelper.inQuietHours(mContext, null)) {
                final String bypassCode = getSmsBypassCode();
                final String messageBody = msg.getMessageBody();
                if (QuietHoursHelper.isMessageBypass(mContext, incomingNumber)){
                    nawDawg = true;
                    startAlarm(incomingNumber);
                } else if (bypassCode.length() == 0 || messageBody.contains(bypassCode)) {
                    switch (bypassCodePref) {
                       case DEFAULT_DISABLED:
                           break;
                       case ALL_NUMBERS:
                           // Sound Alarm && Don't auto-respond
                           nawDawg = true;
                           startAlarm(incomingNumber);
                           break;
                       case CONTACTS_ONLY:
                           if (isContact) {
                               // Sound Alarm && Don't auto-respond
                               nawDawg = true;
                               startAlarm(incomingNumber);
                           }
                           break;
                       case STARRED_ONLY:
                           if (isStarred) {
                               // Sound Alarm && Don't auto-respond
                               nawDawg = true;
                               startAlarm(incomingNumber);
                           }
                           break;
                    }
                }
                if (userAutoSms != DEFAULT_DISABLED && nawDawg == false) {
                    checkTimeAndNumber(incomingNumber, userAutoSms, isContact);
                }
            }
        }
    }

    private class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.QUIET_HOURS_WHITELIST),
                    false, this);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.QUIET_HOURS_CALL_BYPASS),
                    false, this);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.QUIET_HOURS_SMS_BYPASS),
                    false, this);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.QUIET_HOURS_AUTO_CALL),
                    false, this);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.QUIET_HOURS_AUTO_SMS),
                    false, this);
        }

        void unobserve() {
            ContentResolver resolver = mContext.getContentResolver();
            resolver.unregisterContentObserver(this);
        }

        @Override
        public void onChange(boolean selfChange) {
            super.onChange(selfChange);
            update();
        }

        public void update() {
            stopQuietHours();
            startQuietHours();
        }
    }

    private boolean shouldStart() {
        final int callBypass = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.QUIET_HOURS_CALL_BYPASS, 0, UserHandle.USER_CURRENT);
        final int userAutoCall = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.QUIET_HOURS_AUTO_CALL, 0, UserHandle.USER_CURRENT); 
        final int userAutoSms = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.QUIET_HOURS_AUTO_SMS, 0, UserHandle.USER_CURRENT);
        final int smsBypass = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.QUIET_HOURS_SMS_BYPASS, 0, UserHandle.USER_CURRENT);

        return callBypass != DEFAULT_DISABLED
                || userAutoCall != DEFAULT_DISABLED
                || smsBypass != DEFAULT_DISABLED
                || userAutoSms != DEFAULT_DISABLED
                || QuietHoursHelper.hasMessageBypass(mContext)
                || QuietHoursHelper.hasCallBypass(mContext);
    }

    public void startQuietHours() {
        if (shouldStart()){
            if (DEBUG){
                Log.d(TAG, "startQuietHours");
            }
            mPhoneStateListener = new CallListener();
            mTelephony = (TelephonyManager) mContext.getSystemService(Context.TELEPHONY_SERVICE);
            mTelephony.listen(mPhoneStateListener, PhoneStateListener.LISTEN_CALL_STATE);

            IntentFilter filter = new IntentFilter();
            filter.addAction(Intents.SMS_RECEIVED_ACTION);

            mSmsReceiver = new SmsReceiver();
            mContext.registerReceiver(mSmsReceiver, filter);
        }
    }

    public void stopQuietHours() {
        if (DEBUG){
            Log.d(TAG, "stopQuietHours");
        }
        if (mPhoneStateListener != null){
            mTelephony.listen(mPhoneStateListener, PhoneStateListener.LISTEN_NONE);
            mPhoneStateListener = null;
        }
        if (mSmsReceiver != null){
            mContext.unregisterReceiver(mSmsReceiver);
            mSmsReceiver = null;
        }
    }

    /*
     * Dont send if alarm fired
     * If in same minute, don't send. This prevents message looping if sent to self
     * or another quiet-hours enabled device with this feature on.
     */
    private void checkTimeAndNumber(String incomingNumber,
            int userSetting, boolean isContact) {
        final int minutesNow = returnTimeInMinutes();
        if (minutesNow != mMinuteSent) {
            mNumberSent = incomingNumber;
            mMinuteSent = returnTimeInMinutes();
            checkSmsQualifiers(incomingNumber, userSetting, isContact);
        } else {
            // Let's try to send if number doesn't match prior
            if (!incomingNumber.equals(mNumberSent)) {
                mNumberSent = incomingNumber;
                mMinuteSent = returnTimeInMinutes();
                checkSmsQualifiers(incomingNumber, userSetting, isContact);
            }
        }
    }

    private void startAlarm(String phoneNumber) {
        String contactName = returnContactName(phoneNumber);
        Intent alarmDialog = new Intent();
        alarmDialog.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        alarmDialog.setClass(mContext, QuietHoursBypassAlarm.class);
        alarmDialog.putExtra("number", contactName);
        mContext.startActivity(alarmDialog);
    }

    /* True: Contact
     * False: Not a contact
     */
    private boolean isContact(String phoneNumber) {
        boolean isContact = false;
        Uri lookupUri = Uri.withAppendedPath(
                PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phoneNumber));
        String[] numberProject = {
                PhoneLookup._ID,
                PhoneLookup.NUMBER,
                PhoneLookup.DISPLAY_NAME };
        Cursor c = mContext.getContentResolver().query(
                lookupUri, numberProject, null, null, null);
        try {
            if (c.moveToFirst()) {
                isContact = true;
            }
        } finally {
            if (c != null) {
               c.close();
            }
        }
        return isContact;
    }

    /* True: Starred contact
     * False: Not starred
     */
    private boolean isStarred(String phoneNumber) {
        boolean isStarred = false;
        Uri lookupUri = Uri.withAppendedPath(
                PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phoneNumber));
        String[] numberProject = { PhoneLookup.STARRED };
        Cursor c = mContext.getContentResolver().query(
                lookupUri, numberProject, null, null, null);
        try {
            if (c.moveToFirst()) {
                if (c.getInt(c.getColumnIndex(PhoneLookup.STARRED)) == 1) {
                    isStarred = true;
                }
            }
        } finally {
            if (c != null) {
               c.close();
            }
        }
        return isStarred;
    }

    // Return the current time
    private int returnTimeInMinutes() {
        Calendar calendar = Calendar.getInstance();
        return calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE);
    }

    // Return current day of month
    private int returnDayOfMonth() {
        Calendar calendar = Calendar.getInstance();
        return calendar.get(Calendar.DAY_OF_MONTH);
    }

    // Return if last call versus current call less than 30 minute apart
    private boolean returnTimeConstraintMet(int firstCallTime, int dayOfFirstCall) {
        final int currentMinutes = returnTimeInMinutes();
        final int dayOfMonth = returnDayOfMonth();
        // New Day, start at zero
        if (dayOfMonth != dayOfFirstCall) {
            // Less or Equal to 30 minutes until midnight
            if (firstCallTime >= (FULL_DAY - TIME_LIMIT)) {
                if ((currentMinutes >= 0) && (currentMinutes <= TIME_LIMIT)) {
                    int remainderDayOne = FULL_DAY - firstCallTime;
                    if ((remainderDayOne + currentMinutes) <= TIME_LIMIT) {
                        return true;
                    } else {
                        return false;
                    }
                } else {
                    return false;
                }
            } else {
                // new day and prior call happened with more than
                // 30 minutes remaining in day
                return false;
            }
        } else {
            // Same day - simple subtraction: or you need to get out more
            // and it's been a month since your last call, reboot, or reschedule
            if ((currentMinutes - firstCallTime) <= TIME_LIMIT) {
                return true;
            } else {
                return false;
            }
        }
    }

    private String getSmsBypassCode() {
        final String defaultCode = mContext.getResources().getString(R.string.quiet_hours_sms_code_null);
        String code = Settings.System.getStringForUser(mContext.getContentResolver(),
                Settings.System.QUIET_HOURS_SMS_BYPASS_CODE, UserHandle.USER_CURRENT);
        if (code == null){
            code = defaultCode;
        }
        return code;
    }

    // Returns the contact name or number
    private String returnContactName(String phoneNumber) {
        String contactName = null;
        Uri lookupUri = Uri.withAppendedPath(
                PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phoneNumber));
        String[] numberProject = { PhoneLookup.DISPLAY_NAME };
        Cursor c = mContext.getContentResolver().query(
            lookupUri, numberProject, null, null, null);

        try {
            if (c.moveToFirst()) {
                contactName = c.getString(c.getColumnIndex(PhoneLookup.DISPLAY_NAME));
            } else {
                // Not in contacts, return number again
                contactName = phoneNumber;
            }
        } finally {
            if (c != null) {
               c.close();
            }
        }

        return contactName;
    }

    // Pull current settings and send message if applicable
    private void checkSmsQualifiers(String incomingNumber,
            int userAutoSms, boolean isContact) {
        String message = Settings.System.getStringForUser(mContext.getContentResolver(),
                Settings.System.QUIET_HOURS_AUTO_SMS_TEXT, UserHandle.USER_CURRENT);
        String defaultSms = mContext.getResources().getString(
                R.string.quiet_hours_auto_sms_null);
        if (message == null){
            message = defaultSms;
        }
        switch (userAutoSms) {
            case ALL_NUMBERS:
                sendAutoReply(message, incomingNumber);
                break;
            case CONTACTS_ONLY:
                if (isContact) {
                    sendAutoReply(message, incomingNumber);
                }
                break;
            case STARRED_ONLY:
                if (isContact && isStarred(incomingNumber)) {
                    sendAutoReply(message, incomingNumber);
                }
                break;
            case BYPASS_ONLY:
                if (QuietHoursHelper.isWhitelistContact(mContext, incomingNumber)) {
                    sendAutoReply(message, incomingNumber);
                }
                break;
        }
    }

    // Send the message
    private void sendAutoReply(String message, String phoneNumber) {
        final ComponentName component = SmsApplication.getDefaultRespondViaMessageApplication(
                PhoneGlobals.getInstance(), true /*updateIfNeeded*/);
        if (component != null) {
            final Uri uri = Uri.fromParts(Constants.SCHEME_SMSTO, phoneNumber, null);
            final Intent intent = new Intent(TelephonyManager.ACTION_RESPOND_VIA_MESSAGE, uri);
            intent.putExtra(Intent.EXTRA_TEXT, message);
            intent.setComponent(component);
            PhoneGlobals.getInstance().startService(intent);
        } else {
            Log.e(TAG, "sendAutoReply: component = null");
        }
    }
}
