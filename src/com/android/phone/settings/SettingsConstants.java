/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.phone.settings;

import android.app.Activity;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * Constants related to settings which are shared by two or more classes.
 */
public class SettingsConstants {
    // Dtmf tone type setting value for CDMA phone
    public static final int DTMF_TONE_TYPE_NORMAL = 0;
    public static final int DTMF_TONE_TYPE_LONG   = 1;

    // Hearing Aid Compatability settings values.
    public static final String HAC_KEY = "HACSetting";
    public static final int HAC_DISABLED = 0;
    public static final int HAC_ENABLED = 1;
    public static final String HAC_VAL_ON = "ON";
    public static final String HAC_VAL_OFF = "OFF";

    /**
     * Given an activity, configure the activity to adjust for edge to edge restrictions.
     * @param activity the activity.
     */
    public static void setupEdgeToEdge(Activity activity) {
        ViewCompat.setOnApplyWindowInsetsListener(activity.findViewById(android.R.id.content),
                (v, windowInsets) -> {
                    Insets insets = windowInsets.getInsets(
                            WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.ime());

                    // Apply the insets paddings to the view.
                    v.setPadding(insets.left, insets.top, insets.right, insets.bottom);

                    // Return CONSUMED if you don't want the window insets to keep being
                    // passed down to descendant views.
                    return WindowInsetsCompat.CONSUMED;
                });
    }
}
