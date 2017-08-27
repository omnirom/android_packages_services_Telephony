/*
 * Copyright (C) 2008 The Android Open Source Project
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

import android.app.ActionBar;
import android.app.Activity;
import android.app.ActivityOptions;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.os.UserHandle;
import android.os.UserManager;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceScreen;
import android.preference.SwitchPreference;
import android.provider.Settings;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.telephony.CarrierConfigManager;
import android.telephony.ims.feature.ImsFeature;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;
import android.view.MenuItem;
import android.widget.Toast;

import com.android.ims.ImsConfig;
import com.android.ims.ImsException;
import com.android.ims.ImsManager;
import com.android.internal.telephony.CallForwardInfo;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneConstants;
import com.android.phone.common.util.SettingsUtil;
import com.android.phone.settings.AccountSelectionPreference;
import com.android.phone.settings.PhoneAccountSettingsFragment;
import com.android.phone.settings.VoicemailSettingsActivity;
import com.android.phone.settings.fdn.FdnSetting;
import com.android.services.telephony.sip.SipUtil;

import java.lang.String;
import java.util.ArrayList;
import java.util.List;

import org.codeaurora.ims.QtiCallConstants;

/**
 * Top level "Call settings" UI; see res/xml/call_feature_setting.xml
 *
 * This preference screen is the root of the "Call settings" hierarchy available from the Phone
 * app; the settings here let you control various features related to phone calls (including
 * voicemail settings, the "Respond via SMS" feature, and others.)  It's used only on
 * voice-capable phone devices.
 *
 * Note that this activity is part of the package com.android.phone, even
 * though you reach it from the "Phone" app (i.e. DialtactsActivity) which
 * is from the package com.android.contacts.
 *
 * For the "Mobile network settings" screen under the main Settings app,
 * See {@link MobileNetworkSettings}.
 *
 * @see com.android.phone.MobileNetworkSettings
 */
public class CallFeaturesSetting extends PreferenceActivity
        implements Preference.OnPreferenceChangeListener {
    private static final String LOG_TAG = "CallFeaturesSetting";
    private static final boolean DBG = (PhoneGlobals.DBG_LEVEL >= 2);

    // String keys for preference lookup
    // TODO: Naming these "BUTTON_*" is confusing since they're not actually buttons(!)
    // TODO: Consider moving these strings to strings.xml, so that they are not duplicated here and
    // in the layout files. These strings need to be treated carefully; if the setting is
    // persistent, they are used as the key to store shared preferences and the name should not be
    // changed unless the settings are also migrated.
    private static final String VOICEMAIL_SETTING_SCREEN_PREF_KEY = "button_voicemail_category_key";
    private static final String BUTTON_FDN_KEY   = "button_fdn_key";
    private static final String BUTTON_RETRY_KEY       = "button_auto_retry_key";
    private static final String BUTTON_GSM_UMTS_OPTIONS = "button_gsm_more_expand_key";
    private static final String BUTTON_CDMA_OPTIONS = "button_cdma_more_expand_key";

    private static final String PHONE_ACCOUNT_SETTINGS_KEY =
            "phone_account_settings_preference_screen";

    private static final String ENABLE_VIDEO_CALLING_KEY = "button_enable_video_calling";
    private static final String BUTTON_IMS_SETTINGS_KEY   = "ims_settings_key";

    private Phone mPhone;
    private ImsManager mImsMgr;
    private SubscriptionInfoHelper mSubscriptionInfoHelper;
    private TelecomManager mTelecomManager;

    private SwitchPreference mButtonAutoRetry;
    private PreferenceScreen mVoicemailSettingsScreen;
    private SwitchPreference mEnableVideoCalling;
    private PreferenceScreen mImsSettingsScreen;

    /*
     * Click Listeners, handle click based on objects attached to UI.
     */

    // Click listener for all toggle events
    @Override
    public boolean onPreferenceTreeClick(PreferenceScreen preferenceScreen, Preference preference) {
        if (preference == mButtonAutoRetry) {
            android.provider.Settings.Global.putInt(mPhone.getContext().getContentResolver(),
                    android.provider.Settings.Global.CALL_AUTO_RETRY,
                    mButtonAutoRetry.isChecked() ? 1 : 0);
            return true;
        }
        return false;
    }

    /**
     * Implemented to support onPreferenceChangeListener to look for preference
     * changes.
     *
     * @param preference is the preference to be changed
     * @param objValue should be the value of the selection, NOT its localized
     * display value.
     */
    @Override
    public boolean onPreferenceChange(Preference preference, Object objValue) {
        if (DBG) log("onPreferenceChange: \"" + preference + "\" changed to \"" + objValue + "\"");

        if (preference == mEnableVideoCalling) {
            if (mImsMgr.isEnhanced4gLteModeSettingEnabledByUserForSlot()) {
                mImsMgr.setVtSettingForSlot((boolean) objValue);
            } else {
                AlertDialog.Builder builder = new AlertDialog.Builder(this);
                DialogInterface.OnClickListener networkSettingsClickListener =
                        new Dialog.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                if (PhoneUtils.isNetworkSettingsApkAvailable(mPhone.getContext())) {
                                    startActivity(new Intent(
                                            "codeaurora.intent.action.MOBILE_NETWORK_SETTINGS"));
                                } else {
                                    startActivity(new Intent(mPhone.getContext(),
                                            com.android.phone.MobileNetworkSettings.class));
                                }
                            }
                        };
                builder.setMessage(getResources().getString(
                                R.string.enable_video_calling_dialog_msg))
                        .setNeutralButton(getResources().getString(
                                R.string.enable_video_calling_dialog_settings),
                                networkSettingsClickListener)
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
                return false;
            }
        }

        // Always let the preference setting proceed.
        return true;
    }

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        if (DBG) log("onCreate: Intent is " + getIntent());

        // Make sure we are running as an admin user.
        if (!UserManager.get(this).isAdminUser()) {
            Toast.makeText(this, R.string.call_settings_admin_user_only,
                    Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        mSubscriptionInfoHelper = new SubscriptionInfoHelper(this, getIntent());
        mSubscriptionInfoHelper.setActionBarTitle(
                getActionBar(), getResources(), R.string.call_settings_with_label);
        mPhone = mSubscriptionInfoHelper.getPhone();
        mTelecomManager = TelecomManager.from(this);
        updateImsManager(mPhone);
    }

    private void updateImsManager(Phone phone) {
        log("updateImsManager :: phone.getContext()=" + phone.getContext()
                + " phone.getPhoneId()=" + phone.getPhoneId());
        mImsMgr = ImsManager.getInstance(phone.getContext(), phone.getPhoneId());
        if (mImsMgr == null) {
          log("updateImsManager :: Could not get ImsManager instance!");
        } else {
          log("updateImsManager :: mImsMgr=" + mImsMgr);
        }
    }

    private final PhoneStateListener mPhoneStateListener = new PhoneStateListener() {
        @Override
        public void onCallStateChanged(int state, String incomingNumber) {
            if (DBG) log("PhoneStateListener onCallStateChanged: state is " + state);
            if (mEnableVideoCalling != null) {
                mEnableVideoCalling.setEnabled(state == TelephonyManager.CALL_STATE_IDLE);
            }
        }
    };

    @Override
    protected void onPause() {
        super.onPause();
        TelephonyManager telephonyManager =
                (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
        telephonyManager.listen(mPhoneStateListener, PhoneStateListener.LISTEN_NONE);
    }

    @Override
    protected void onResume() {
        super.onResume();

        PreferenceScreen preferenceScreen = getPreferenceScreen();
        if (preferenceScreen != null) {
            preferenceScreen.removeAll();
        }

        addPreferencesFromResource(R.xml.call_feature_setting);

        TelephonyManager telephonyManager =
                (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
        telephonyManager.listen(mPhoneStateListener, PhoneStateListener.LISTEN_CALL_STATE);

        Preference phoneAccountSettingsPreference = findPreference(PHONE_ACCOUNT_SETTINGS_KEY);
        if (telephonyManager.isMultiSimEnabled() || !SipUtil.isVoipSupported(mPhone.getContext())) {
            getPreferenceScreen().removePreference(phoneAccountSettingsPreference);
        }

        PreferenceScreen prefSet = getPreferenceScreen();
        mVoicemailSettingsScreen =
                (PreferenceScreen) findPreference(VOICEMAIL_SETTING_SCREEN_PREF_KEY);
        mVoicemailSettingsScreen.setIntent(mSubscriptionInfoHelper.getIntent(
                VoicemailSettingsActivity.class));

        maybeHideVoicemailSettings();

        mButtonAutoRetry = (SwitchPreference) findPreference(BUTTON_RETRY_KEY);

        mEnableVideoCalling = (SwitchPreference) findPreference(ENABLE_VIDEO_CALLING_KEY);

        PersistableBundle carrierConfig =
                PhoneGlobals.getInstance().getCarrierConfigForSubId(mPhone.getSubId());

        if (carrierConfig.getBoolean(CarrierConfigManager.KEY_AUTO_RETRY_ENABLED_BOOL)) {
            mButtonAutoRetry.setOnPreferenceChangeListener(this);
            int autoretry = Settings.Global.getInt(
                    getContentResolver(), Settings.Global.CALL_AUTO_RETRY, 0);
            mButtonAutoRetry.setChecked(autoretry != 0);
        } else {
            prefSet.removePreference(mButtonAutoRetry);
            mButtonAutoRetry = null;
        }

        Preference cdmaOptions = prefSet.findPreference(BUTTON_CDMA_OPTIONS);
        Preference gsmOptions = prefSet.findPreference(BUTTON_GSM_UMTS_OPTIONS);
        Preference fdnButton = prefSet.findPreference(BUTTON_FDN_KEY);
        fdnButton.setIntent(mSubscriptionInfoHelper.getIntent(FdnSetting.class));
        if (carrierConfig.getBoolean(CarrierConfigManager.KEY_WORLD_PHONE_BOOL)) {
            cdmaOptions.setIntent(mSubscriptionInfoHelper.getIntent(CdmaCallOptions.class));
            gsmOptions.setIntent(mSubscriptionInfoHelper.getIntent(GsmUmtsCallOptions.class));
        } else {
            prefSet.removePreference(cdmaOptions);
            prefSet.removePreference(gsmOptions);

            int phoneType = mPhone.getPhoneType();
            if (carrierConfig.getBoolean(CarrierConfigManager.KEY_HIDE_CARRIER_NETWORK_SETTINGS_BOOL)) {
                prefSet.removePreference(fdnButton);
            } else {
                if (phoneType == PhoneConstants.PHONE_TYPE_CDMA) {
                    prefSet.removePreference(fdnButton);
                    addPreferencesFromResource(R.xml.cdma_call_privacy);

                    if (carrierConfig.getBoolean(
                            CarrierConfigManager.KEY_VOICE_PRIVACY_DISABLE_UI_BOOL)) {
                        CdmaVoicePrivacySwitchPreference prefPri =
                                (CdmaVoicePrivacySwitchPreference)prefSet.findPreference(
                                "button_voice_privacy_key");
                        if (prefPri != null) {
                             prefSet.removePreference(prefPri);
                        }
                    }

                    if (carrierConfig.getBoolean(
                                CarrierConfigManager.KEY_CDMA_CW_CF_ENABLED_BOOL)
                                && CdmaCallOptions.isCdmaCallWaitingActivityPresent(
                                mPhone.getContext())) {
                        Log.d(LOG_TAG, "Enabled CW CF");
                        PreferenceScreen prefCW = (PreferenceScreen)
                                prefSet.findPreference("button_cw_key");
                        if (prefCW != null) {
                            prefCW.setOnPreferenceClickListener(
                                    new Preference.OnPreferenceClickListener() {
                                        @Override
                                        public boolean onPreferenceClick(Preference preference) {
                                            Intent intent = new Intent(CdmaCallOptions.
                                                    CALL_WAITING_INTENT);
                                            intent.putExtra(PhoneConstants.
                                                    SUBSCRIPTION_KEY,mPhone.getSubId());
                                            startActivity(intent);
                                            return true;
                                        }
                                    });
                        }
                        PreferenceScreen prefCF = (PreferenceScreen)
                                prefSet.findPreference("button_cf_expand_key");
                        if (prefCF != null) {
                            prefCF.setOnPreferenceClickListener(
                                    new Preference.OnPreferenceClickListener() {
                                        @Override
                                        public boolean onPreferenceClick(Preference preference) {
                                            Intent intent = (mPhone.getImsPhone() != null
                                                && mPhone.isUtEnabled()) ?
                                                mSubscriptionInfoHelper
                                                    .getIntent(CallForwardType.class)
                                                : new Intent(CdmaCallOptions.CALL_FORWARD_INTENT);
                                            intent.putExtra(PhoneConstants.SUBSCRIPTION_KEY, mPhone.getSubId());
                                            startActivity(intent);
                                            return true;
                                        }
                                    });
                        }
                    } else {
                        Log.d(LOG_TAG, "Disabled CW CF");
                        PreferenceScreen prefCW = (PreferenceScreen)
                                prefSet.findPreference("button_cw_key");
                        if (prefCW != null) {
                            prefSet.removePreference(prefCW);
                        }
                        PreferenceScreen prefCF = (PreferenceScreen)
                                prefSet.findPreference("button_cf_expand_key");
                        if (prefCF != null) {
                            prefSet.removePreference(prefCF);
                        }
                    }
                } else if (phoneType == PhoneConstants.PHONE_TYPE_GSM) {

                    if (carrierConfig.getBoolean(
                            CarrierConfigManager.KEY_ADDITIONAL_CALL_SETTING_BOOL)) {
                        addPreferencesFromResource(R.xml.gsm_umts_call_options);
                        GsmUmtsCallOptions.init(prefSet, mSubscriptionInfoHelper);
                    }
                } else {
                    throw new IllegalStateException("Unexpected phone type: " + phoneType);
                }
            }
        }

        mImsSettingsScreen = (PreferenceScreen) findPreference(BUTTON_IMS_SETTINGS_KEY);
        if (isImsSettingsApkAvailable(mPhone.getContext())) {
            ImsManager imsManager = ImsManager.getInstance(mPhone.getContext(),
                    mPhone.getPhoneId());
            try {
                if ((imsManager.isVolteEnabledByPlatformForSlot() ||
                        imsManager.isVtEnabledByPlatformForSlot() ||
                        imsManager.isWfcEnabledByPlatformForSlot()) &&
                        imsManager.getImsServiceStatus() == ImsFeature.STATE_READY) {
                    mImsSettingsScreen.setOnPreferenceClickListener(
                            new Preference.OnPreferenceClickListener() {
                         @Override
                         public boolean onPreferenceClick(Preference preference) {
                             Intent intent = new Intent("org.codeaurora.IMS_SETTINGS");
                             intent.putExtra(QtiCallConstants.EXTRA_PHONE_ID, mPhone.getPhoneId());
                             startActivity(intent);
                             return true;
                         }
                    });
                } else {
                    prefSet.removePreference(mImsSettingsScreen);
                }
            } catch (ImsException ex) {
                log("Exception when trying to get ImsServiceStatus: " + ex);
                prefSet.removePreference(mImsSettingsScreen);
            }
        } else {
            prefSet.removePreference(mImsSettingsScreen);
        }

        if (mImsMgr.isVtEnabledByPlatformForSlot() &&
                mImsMgr.isVtProvisionedOnDeviceForSlot() &&
                (carrierConfig.getBoolean(
                        CarrierConfigManager.KEY_IGNORE_DATA_ENABLED_CHANGED_FOR_VIDEO_CALLS)
                        || mPhone.mDcTracker.isDataEnabled())) {
            final boolean isVideoCallingEnabledForSlot =
                    mImsMgr.isVtEnabledByPlatformForSlot()
                    && mImsMgr.isEnhanced4gLteModeSettingEnabledByUserForSlot()
                    && mImsMgr.isVtEnabledByUserForSlot();
            mEnableVideoCalling.setChecked(isVideoCallingEnabledForSlot);
            mEnableVideoCalling.setOnPreferenceChangeListener(this);
        } else {
            prefSet.removePreference(mEnableVideoCalling);
        }

        if (mImsMgr.isVolteEnabledByPlatformForSlot() &&
                !carrierConfig.getBoolean(
                        CarrierConfigManager.KEY_CARRIER_VOLTE_TTY_SUPPORTED_BOOL)) {
            TelephonyManager tm = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
            /* tm.listen(mPhoneStateListener, PhoneStateListener.LISTEN_CALL_STATE); */
        }

        Preference wifiCallingSettings = findPreference(
                getResources().getString(R.string.wifi_calling_settings_key));

        wifiCallingSettings.setOnPreferenceClickListener(
                 new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                Intent intent = new Intent("android.settings.WIFI_CALLING_SETTINGS");
                intent.putExtra(QtiCallConstants.EXTRA_PHONE_ID, mPhone.getPhoneId());
                startActivity(intent);
                return true;
            }
        });

        final PhoneAccountHandle simCallManager = mTelecomManager.getSimCallManager();
        if (simCallManager != null) {
            Intent intent = PhoneAccountSettingsFragment.buildPhoneAccountConfigureIntent(
                    this, simCallManager);
            if (intent != null) {
                PackageManager pm = mPhone.getContext().getPackageManager();
                List<ResolveInfo> resolutions = pm.queryIntentActivities(intent, 0);
                if (!resolutions.isEmpty()) {
                    wifiCallingSettings.setTitle(resolutions.get(0).loadLabel(pm));
                    wifiCallingSettings.setSummary(null);
                    wifiCallingSettings.setIntent(intent);
                } else {
                    prefSet.removePreference(wifiCallingSettings);
                }
            } else {
                prefSet.removePreference(wifiCallingSettings);
            }
        } else if (!mImsMgr.isWfcEnabledByPlatformForSlot() ||
                !mImsMgr.isWfcProvisionedOnDeviceForSlot() ) {
            prefSet.removePreference(wifiCallingSettings);
        } else {
            int resId = com.android.internal.R.string.wifi_calling_off_summary;
            if (mImsMgr.isWfcEnabledByUserForSlot()) {
                boolean isRoaming = telephonyManager.isNetworkRoaming(mPhone.getSubId());
                int wfcMode = mImsMgr.getWfcModeForSlot(isRoaming);
                switch (wfcMode) {
                    case ImsConfig.WfcModeFeatureValueConstants.WIFI_ONLY:
                        resId = com.android.internal.R.string.wfc_mode_wifi_only_summary;
                        break;
                    case ImsConfig.WfcModeFeatureValueConstants.CELLULAR_PREFERRED:
                        resId = com.android.internal.R.string.wfc_mode_cellular_preferred_summary;
                        break;
                    case ImsConfig.WfcModeFeatureValueConstants.WIFI_PREFERRED:
                        resId = com.android.internal.R.string.wfc_mode_wifi_preferred_summary;
                        break;
                    default:
                        if (DBG) log("Unexpected WFC mode value: " + wfcMode);
                }
            }
            wifiCallingSettings.setSummary(resId);
        }

        try {
            if (mImsMgr.getImsServiceStatus() != ImsFeature.STATE_READY) {
                log("Feature state not ready so remove vt and wfc settings for " +
                        " phone =" + mPhone.getPhoneId());
                prefSet.removePreference(wifiCallingSettings);
                prefSet.removePreference(mEnableVideoCalling);
            }
        } catch (ImsException ex) {
            log("Exception when trying to get ImsServiceStatus: " + ex);
            prefSet.removePreference(wifiCallingSettings);
            prefSet.removePreference(mEnableVideoCalling);
        }
    }

    /**
     * Hides the top level voicemail settings entry point if the default dialer contains a
     * particular manifest metadata key. This is required when the default dialer wants to display
     * its own version of voicemail settings.
     */
    private void maybeHideVoicemailSettings() {
        String defaultDialer = getSystemService(TelecomManager.class).getDefaultDialerPackage();
        if (defaultDialer == null) {
            return;
        }
        try {
            Bundle metadata = getPackageManager()
                    .getApplicationInfo(defaultDialer, PackageManager.GET_META_DATA).metaData;
            if (metadata == null) {
                return;
            }
            if (!metadata
                    .getBoolean(TelephonyManager.METADATA_HIDE_VOICEMAIL_SETTINGS_MENU, false)) {
                if (DBG) {
                    log("maybeHideVoicemailSettings(): not disabled by default dialer");
                }
                return;
            }
            getPreferenceScreen().removePreference(mVoicemailSettingsScreen);
            if (DBG) {
                log("maybeHideVoicemailSettings(): disabled by default dialer");
            }
        } catch (NameNotFoundException e) {
            // do nothing
            if (DBG) {
                log("maybeHideVoicemailSettings(): not controlled by default dialer");
            }
        }
    }

    @Override
    protected void onNewIntent(Intent newIntent) {
        setIntent(newIntent);

        mSubscriptionInfoHelper = new SubscriptionInfoHelper(this, getIntent());
        mSubscriptionInfoHelper.setActionBarTitle(
                getActionBar(), getResources(), R.string.call_settings_with_label);
        mPhone = mSubscriptionInfoHelper.getPhone();
        updateImsManager(mPhone);
    }

    private static void log(String msg) {
        Log.d(LOG_TAG, msg);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        final int itemId = item.getItemId();
        if (itemId == android.R.id.home) {  // See ActionBar#setDisplayHomeAsUpEnabled()
            onBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /**
     * Finish current Activity and go up to the top level Settings ({@link CallFeaturesSetting}).
     * This is useful for implementing "HomeAsUp" capability for second-level Settings.
     */
    public static void goUpToTopLevelSetting(
            Activity activity, SubscriptionInfoHelper subscriptionInfoHelper) {
        Intent intent = subscriptionInfoHelper.getIntent(CallFeaturesSetting.class);
        intent.setAction(Intent.ACTION_MAIN);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        activity.startActivity(intent);
        activity.finish();
    }

    /**
     * check whether ImsSettings apk exist in system, if yes, return true, else
     * return false.
     */
    public static boolean isImsSettingsApkAvailable(Context context) {
        // check whether the target handler exist in system
        Intent intent = new Intent("org.codeaurora.IMS_SETTINGS");
        PackageManager pm = context.getPackageManager();
        List<ResolveInfo> list = pm.queryIntentActivities(intent, 0);
        for (ResolveInfo resolveInfo : list) {
            // check is it installed in system.img, exclude the application
            // installed by user
            if ((resolveInfo.activityInfo.applicationInfo.flags &
                    ApplicationInfo.FLAG_SYSTEM) != 0) {
                return true;
            }
        }
        return false;
    }
}
