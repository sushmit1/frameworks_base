/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.qs;

import static android.app.StatusBarManager.DISABLE2_QUICK_SETTINGS;

import static com.android.systemui.util.InjectionInflationController.VIEW_CONTEXT;

import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.database.ContentObserver;
import android.graphics.PorterDuff.Mode;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.util.AttributeSet;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.View.OnLongClickListener;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.settingslib.Utils;
import com.android.settingslib.drawable.UserIconDrawable;
import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.R.dimen;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.qs.TouchAnimator.Builder;
import com.android.systemui.statusbar.phone.MultiUserSwitch;
import com.android.systemui.statusbar.phone.SettingsButton;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;
import com.android.systemui.statusbar.policy.UserInfoController;
import com.android.systemui.statusbar.policy.UserInfoController.OnUserInfoChangedListener;

import javax.inject.Inject;
import javax.inject.Named;

public class QSFooterImpl extends FrameLayout implements QSFooter,
        OnClickListener, OnLongClickListener, OnUserInfoChangedListener {

    private static final String TAG = "QSFooterImpl";

    private final ActivityStarter mActivityStarter;
    private final UserInfoController mUserInfoController;
    private final DeviceProvisionedController mDeviceProvisionedController;
    private SettingsButton mSettingsButton;
    protected View mSettingsContainer;
    private PageIndicator mPageIndicator;
    private TextView mBuildText;
    private boolean mShouldShowBuildText;
    private View mRunningServicesButton;

    private boolean mQsDisabled;
    private QSPanel mQsPanel;
    private QuickQSPanel mQuickQsPanel;

    private boolean mExpanded;

    private boolean mListening;

    protected MultiUserSwitch mMultiUserSwitch;
    private ImageView mMultiUserAvatar;

    protected TouchAnimator mFooterAnimator;
    private float mExpansionAmount;

    protected View mEdit;
    protected View mEditContainer;
    private TouchAnimator mSettingsCogAnimator;

    private View mActionsContainer;

    private OnClickListener mExpandClickListener;

    protected Vibrator mVibrator;

    private final ContentObserver mSettingsObserver = new ContentObserver(
            new Handler(mContext.getMainLooper())) {
        @Override
        public void onChange(boolean selfChange, Uri uri) {
            super.onChange(selfChange, uri);
            setBuildText();
        }
    };

    @Inject
    public QSFooterImpl(@Named(VIEW_CONTEXT) Context context, AttributeSet attrs,
            ActivityStarter activityStarter, UserInfoController userInfoController,
            DeviceProvisionedController deviceProvisionedController) {
        super(context, attrs);
        mActivityStarter = activityStarter;
        mUserInfoController = userInfoController;
        mDeviceProvisionedController = deviceProvisionedController;
    }

    @VisibleForTesting
    public QSFooterImpl(Context context, AttributeSet attrs) {
        this(context, attrs,
                Dependency.get(ActivityStarter.class),
                Dependency.get(UserInfoController.class),
                Dependency.get(DeviceProvisionedController.class));
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mEdit = findViewById(android.R.id.edit);
        mEdit.setOnClickListener(view ->
                mActivityStarter.postQSRunnableDismissingKeyguard(() ->
                        mQsPanel.showEdit(view)));

        mPageIndicator = findViewById(R.id.footer_page_indicator);

        mSettingsButton = findViewById(R.id.settings_button);
        mSettingsContainer = findViewById(R.id.settings_button_container);
        mSettingsButton.setOnClickListener(this);
        mSettingsButton.setOnLongClickListener(this);

        mRunningServicesButton = findViewById(R.id.running_services_button);
        mRunningServicesButton.setOnClickListener(this);

        mMultiUserSwitch = findViewById(R.id.multi_user_switch);
        mMultiUserAvatar = mMultiUserSwitch.findViewById(R.id.multi_user_avatar);

        mActionsContainer = findViewById(R.id.qs_footer_actions_container);
        mEditContainer = findViewById(R.id.qs_footer_actions_edit_container);
        mVibrator = (Vibrator) mContext.getSystemService(Context.VIBRATOR_SERVICE);
        mBuildText = findViewById(R.id.build);

        // RenderThread is doing more harm than good when touching the header (to expand quick
        // settings), so disable it for this view
        ((RippleDrawable) mSettingsButton.getBackground()).setForceSoftware(true);
        ((RippleDrawable) mRunningServicesButton.getBackground()).setForceSoftware(true);


        updateResources();

        addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight,
                oldBottom) -> updateAnimator(right - left));
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        updateEverything();
        setBuildText();
    }

    private void setBuildText() {
        if (mBuildText == null) return;
        boolean isShow = Settings.System.getIntForUser(mContext.getContentResolver(),
                        Settings.System.TENX_FOOTER_TEXT_SHOW, 0,
                        UserHandle.USER_CURRENT) == 1;
        int tenXFooterTextFonts = Settings.System.getIntForUser(mContext.getContentResolver(),
                        Settings.System.TENX_FOOTER_TEXT_FONT, 28,
                        UserHandle.USER_CURRENT);
        int tenXFooterTextColor = Settings.System.getIntForUser(mContext.getContentResolver(),
                        Settings.System.TENX_FOOTER_TEXT_COLOR, 0,
                        UserHandle.USER_CURRENT);
        int tenXFooterTextColorCustom = Settings.System.getIntForUser(mContext.getContentResolver(),
                        Settings.System.TENX_FOOTER_TEXT_COLOR_CUSTOM, 0xffffffff,
                        UserHandle.USER_CURRENT);
        String text = Settings.System.getStringForUser(mContext.getContentResolver(),
                        Settings.System.TENX_FOOTER_TEXT_STRING,
                        UserHandle.USER_CURRENT);
        if (isShow) {
            if (text == null || text == "") {
                mBuildText.setText("#Ten-X");
                mBuildText.setSelected(true);
                mShouldShowBuildText = true;
            } else {
                mBuildText.setText(text);
                mBuildText.setSelected(true);
                mShouldShowBuildText = true;
            }
        } else {
            mShouldShowBuildText = false;
            mBuildText.setSelected(false);
        }

        if (tenXFooterTextColor == 1) {
            int mAccentColor = mContext.getColor(com.android.internal.R.color.gradient_start);
            mBuildText.setTextColor(mAccentColor);
        } else if (tenXFooterTextColor == 2) {
            mBuildText.setTextColor(tenXFooterTextColorCustom);
        }

        switch (tenXFooterTextFonts) {
            case 0:
                mBuildText.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
                break;
            case 1:
                mBuildText.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
                break;
            case 2:
                mBuildText.setTypeface(Typeface.create("sans-serif", Typeface.ITALIC));
                break;
            case 3:
                mBuildText.setTypeface(Typeface.create("sans-serif", Typeface.BOLD_ITALIC));
                break;
            case 4:
                mBuildText.setTypeface(Typeface.create("sans-serif-light", Typeface.ITALIC));
                break;
            case 5:
                mBuildText.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
                break;
            case 6:
                mBuildText.setTypeface(Typeface.create("sans-serif-thin", Typeface.ITALIC));
                break;
            case 7:
                mBuildText.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
                break;
            case 8:
                mBuildText.setTypeface(Typeface.create("sans-serif-condensed", Typeface.NORMAL));
                break;
            case 9:
                 mBuildText.setTypeface(Typeface.create("sans-serif-condensed", Typeface.ITALIC));
                break;
            case 10:
                mBuildText.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
                break;
            case 11:
                 mBuildText.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD_ITALIC));
                break;
            case 12:
                 mBuildText.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                 break;
            case 13:
                 mBuildText.setTypeface(Typeface.create("sans-serif-medium", Typeface.ITALIC));
                 break;
            case 14:
                 mBuildText.setTypeface(Typeface.create("sans-serif-condensed-light", Typeface.NORMAL));
                 break;
            case 15:
                 mBuildText.setTypeface(Typeface.create("sans-serif-condensed-light", Typeface.ITALIC));
                 break;
            case 16:
                 mBuildText.setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
                 break;
            case 17:
                 mBuildText.setTypeface(Typeface.create("sans-serif-black", Typeface.ITALIC));
                 break;
            case 18:
                 mBuildText.setTypeface(Typeface.create("cursive", Typeface.NORMAL));
                 break;
            case 19:
                 mBuildText.setTypeface(Typeface.create("cursive", Typeface.BOLD));
                break;
            case 20:
                 mBuildText.setTypeface(Typeface.create("casual", Typeface.NORMAL));
                 break;
            case 21:
                 mBuildText.setTypeface(Typeface.create("serif", Typeface.NORMAL));
                 break;
            case 22:
                 mBuildText.setTypeface(Typeface.create("serif", Typeface.ITALIC));
                 break;
            case 23:
                 mBuildText.setTypeface(Typeface.create("serif", Typeface.BOLD));
                 break;
            case 24:
                 mBuildText.setTypeface(Typeface.create("serif", Typeface.BOLD_ITALIC));
                 break;
            case 25:
                 mBuildText.setTypeface(Typeface.create("gobold-light-sys", Typeface.NORMAL));
                 break;
            case 26:
                 mBuildText.setTypeface(Typeface.create("roadrage-sys", Typeface.NORMAL));
                 break;
            case 27:
                 mBuildText.setTypeface(Typeface.create("snowstorm-sys", Typeface.NORMAL));
                 break;
            case 28:
                 mBuildText.setTypeface(Typeface.create("googlesans-sys", Typeface.NORMAL));
                 break;
            case 29:
                 mBuildText.setTypeface(Typeface.create("neoneon-sys", Typeface.NORMAL));
                 break;
            case 30:
                 mBuildText.setTypeface(Typeface.create("themeable-sys", Typeface.NORMAL));
                 break;
            case 31:
                 mBuildText.setTypeface(Typeface.create("samsung-sys", Typeface.NORMAL));
                 break;
            case 32:
                 mBuildText.setTypeface(Typeface.create("mexcellent-sys", Typeface.NORMAL));
                 break;
            case 33:
                 mBuildText.setTypeface(Typeface.create("burnstown-sys", Typeface.NORMAL));
                 break;
            case 34:
                 mBuildText.setTypeface(Typeface.create("dumbledor-sys", Typeface.NORMAL));
                break;
            case 35:
                 mBuildText.setTypeface(Typeface.create("phantombold-sys", Typeface.NORMAL));
                 break;
            case 36:
                 mBuildText.setTypeface(Typeface.create("sourcesanspro-sys", Typeface.NORMAL));
                 break;
            case 37:
                 mBuildText.setTypeface(Typeface.create("circularstd-sys", Typeface.NORMAL));
                 break;
            case 38:
                 mBuildText.setTypeface(Typeface.create("oneplusslate-sys", Typeface.NORMAL));
                 break;
            case 39:
                 mBuildText.setTypeface(Typeface.create("aclonica-sys", Typeface.NORMAL));
                 break;
            case 40:
                 mBuildText.setTypeface(Typeface.create("amarante-sys", Typeface.NORMAL));
                 break;
            case 41:
                 mBuildText.setTypeface(Typeface.create("bariol-sys", Typeface.NORMAL));
                 break;
            case 42:
                 mBuildText.setTypeface(Typeface.create("cagliostro-sys", Typeface.NORMAL));
                 break;
            case 43:
                 mBuildText.setTypeface(Typeface.create("coolstory-sys", Typeface.NORMAL));
                 break;
            case 44:
                  mBuildText.setTypeface(Typeface.create("lgsmartgothic-sys", Typeface.NORMAL));
                  break;
            case 45:
                  mBuildText.setTypeface(Typeface.create("rosemary-sys", Typeface.NORMAL));
                  break;
            case 46:
                  mBuildText.setTypeface(Typeface.create("sonysketch-sys", Typeface.NORMAL));
                  break;
            case 47:
                  mBuildText.setTypeface(Typeface.create("surfer-sys", Typeface.NORMAL));
                  break;
            default:
                  mBuildText.setTypeface(Typeface.create("themeable-sys", Typeface.NORMAL));
                  break;
            }
    }

    private void updateAnimator(int width) {
        int numTiles = mQuickQsPanel != null ? mQuickQsPanel.getNumQuickTiles()
                : QuickQSPanel.getDefaultMaxTiles();
        int size = mContext.getResources().getDimensionPixelSize(R.dimen.qs_quick_tile_size)
                - mContext.getResources().getDimensionPixelSize(dimen.qs_quick_tile_padding);
        int remaining = (width - numTiles * size) / (numTiles - 1);
        int defSpace = mContext.getResources().getDimensionPixelOffset(R.dimen.default_gear_space);

        mSettingsCogAnimator = new Builder()
                .addFloat(mSettingsContainer, "translationX",
                        isLayoutRtl() ? (remaining - defSpace) : -(remaining - defSpace), 0)
                .addFloat(mSettingsButton, "rotation", -120, 0)
                .build();

        setExpansion(mExpansionAmount);
    }

    public void vibrateheader() {
        if (mVibrator != null) {
            if (mVibrator.hasVibrator()) {
                mVibrator.vibrate(VibrationEffect.get(VibrationEffect.EFFECT_HEAVY_CLICK));
            }
        }
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateResources();
    }

    @Override
    public void onRtlPropertiesChanged(int layoutDirection) {
        super.onRtlPropertiesChanged(layoutDirection);
        updateResources();
    }

    private void updateResources() {
        updateFooterAnimator();
    }

    private void updateFooterAnimator() {
        mFooterAnimator = createFooterAnimator();
    }

    @Nullable
    private TouchAnimator createFooterAnimator() {
        return new TouchAnimator.Builder()
                .addFloat(mActionsContainer, "alpha", 0, 1) // contains mRunningServicesButton
                .addFloat(mMultiUserAvatar, "alpha", 0, 1)
                .addFloat(mEditContainer, "alpha", 0, 1)
                .addFloat(mPageIndicator, "alpha", 0, 1)
                .setStartDelay(0.9f)
                .build();
    }

    @Override
    public void setKeyguardShowing(boolean keyguardShowing) {
        setExpansion(mExpansionAmount);
    }

    @Override
    public void setExpandClickListener(OnClickListener onClickListener) {
        mExpandClickListener = onClickListener;
    }

    @Override
    public void setExpanded(boolean expanded) {
        if (mExpanded == expanded) return;
        mExpanded = expanded;
        updateEverything();
    }

    @Override
    public void setExpansion(float headerExpansionFraction) {
        mExpansionAmount = headerExpansionFraction;
        if (mSettingsCogAnimator != null) mSettingsCogAnimator.setPosition(headerExpansionFraction);

        if (mFooterAnimator != null) {
            mFooterAnimator.setPosition(headerExpansionFraction);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mContext.getContentResolver().registerContentObserver(
                Settings.System.getUriFor(Settings.System.TENX_FOOTER_TEXT_SHOW), false,
                mSettingsObserver, UserHandle.USER_ALL);
        mContext.getContentResolver().registerContentObserver(
                Settings.System.getUriFor(Settings.System.TENX_FOOTER_TEXT_STRING), false,
                mSettingsObserver, UserHandle.USER_ALL);
        mContext.getContentResolver().registerContentObserver(
                Settings.System.getUriFor(Settings.System.TENX_FOOTER_TEXT_FONT), false,
                mSettingsObserver, UserHandle.USER_ALL);
        mContext.getContentResolver().registerContentObserver(
                Settings.System.getUriFor(Settings.System.TENX_FOOTER_TEXT_COLOR), false,
                mSettingsObserver, UserHandle.USER_ALL);
        mContext.getContentResolver().registerContentObserver(
                Settings.System.getUriFor(Settings.System.TENX_FOOTER_TEXT_COLOR_CUSTOM), false,
                mSettingsObserver, UserHandle.USER_ALL);
    }

    @Override
    @VisibleForTesting
    public void onDetachedFromWindow() {
        setListening(false);
        mContext.getContentResolver().unregisterContentObserver(mSettingsObserver);
        super.onDetachedFromWindow();
    }

    @Override
    public void setListening(boolean listening) {
        if (listening == mListening) {
            return;
        }
        mListening = listening;
        updateListeners();
    }

    @Override
    public boolean performAccessibilityAction(int action, Bundle arguments) {
        if (action == AccessibilityNodeInfo.ACTION_EXPAND) {
            if (mExpandClickListener != null) {
                mExpandClickListener.onClick(null);
                return true;
            }
        }
        return super.performAccessibilityAction(action, arguments);
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_EXPAND);
    }

    @Override
    public void disable(int state1, int state2, boolean animate) {
        final boolean disabled = (state2 & DISABLE2_QUICK_SETTINGS) != 0;
        if (disabled == mQsDisabled) return;
        mQsDisabled = disabled;
        updateEverything();
    }

    public void updateEverything() {
        post(() -> {
            updateVisibilities();
            updateClickabilities();
            setClickable(false);
        });
    }

    private void updateClickabilities() {
        mMultiUserSwitch.setClickable(mMultiUserSwitch.getVisibility() == View.VISIBLE);
        mEdit.setClickable(mEdit.getVisibility() == View.VISIBLE);
        mSettingsButton.setClickable(mSettingsButton.getVisibility() == View.VISIBLE);
    }

    private void updateVisibilities() {
        mSettingsContainer.setVisibility(!isSettingsEnabled() || mQsDisabled ? View.GONE : View.VISIBLE);
        final boolean isDemo = UserManager.isDeviceInDemoMode(mContext);
        mMultiUserSwitch.setVisibility(isUserEnabled() ? (showUserSwitcher() ? View.VISIBLE : View.INVISIBLE) : View.GONE);
        mEdit.setVisibility(isEditEnabled() ? (isDemo || !mExpanded ? View.INVISIBLE : View.VISIBLE) : View.GONE);
        mEditContainer.setVisibility(isDemo || !mExpanded ? View.INVISIBLE : View.VISIBLE);
        mSettingsButton.setVisibility(isSettingsEnabled() ? (isDemo || !mExpanded ? View.INVISIBLE : View.VISIBLE) : View.GONE);
        mRunningServicesButton.setVisibility(isServicesEnabled() ? (!isDemo && mExpanded ? View.VISIBLE : View.INVISIBLE) : View.GONE);
        mBuildText.setVisibility(mExpanded && mShouldShowBuildText ? View.VISIBLE : View.GONE);
    }

    private boolean showUserSwitcher() {
        return mExpanded && mMultiUserSwitch.isMultiUserEnabled();
    }

    private void updateListeners() {
        if (mListening) {
            mUserInfoController.addCallback(this);
        } else {
            mUserInfoController.removeCallback(this);
        }
    }

    @Override
    public void setQSPanel(final QSPanel qsPanel, final QuickQSPanel quickQSPanel) {
        mQsPanel = qsPanel;
        mQuickQsPanel = quickQSPanel;
        if (mQsPanel != null) {
            mMultiUserSwitch.setQsPanel(qsPanel);
            mQsPanel.setFooterPageIndicator(mPageIndicator);
        }
    }

    public boolean isSettingsEnabled() {
        return Settings.System.getInt(mContext.getContentResolver(),
            Settings.System.QS_FOOTER_SHOW_SETTINGS, 1) == 1;
    }

    public boolean isServicesEnabled() {
        return Settings.System.getInt(mContext.getContentResolver(),
            Settings.System.QS_FOOTER_SHOW_SERVICES, 0) == 1;
    }

    public boolean isEditEnabled() {
        return Settings.System.getInt(mContext.getContentResolver(),
            Settings.System.QS_FOOTER_SHOW_EDIT, 1) == 1;
    }

    public boolean isUserEnabled() {
        return Settings.System.getInt(mContext.getContentResolver(),
            Settings.System.QS_FOOTER_SHOW_USER, 1) == 1;
    }

    @Override
    public void setQQSPanel(@Nullable QuickQSPanel panel) {
        mQuickQsPanel = panel;
    }

    @Override
    public void onClick(View v) {
        // Don't do anything until view are unhidden
        if (!mExpanded) {
            return;
        }

        if (v == mSettingsButton) {
            if (!mDeviceProvisionedController.isCurrentUserSetup()) {
                // If user isn't setup just unlock the device and dump them back at SUW.
                mActivityStarter.postQSRunnableDismissingKeyguard(() -> {
                });
                return;
            }
            MetricsLogger.action(mContext,
                    mExpanded ? MetricsProto.MetricsEvent.ACTION_QS_EXPANDED_SETTINGS_LAUNCH
                            : MetricsProto.MetricsEvent.ACTION_QS_COLLAPSED_SETTINGS_LAUNCH);
            startSettingsActivity();
        } else if (v == mRunningServicesButton) {
            MetricsLogger.action(mContext,
                    mExpanded ? MetricsProto.MetricsEvent.ACTION_QS_EXPANDED_SETTINGS_LAUNCH
                            : MetricsProto.MetricsEvent.ACTION_QS_COLLAPSED_SETTINGS_LAUNCH);
            startRunningServicesActivity();
        }
    }

    private void startRunningServicesActivity() {
        Intent intent = new Intent();
        intent.setClassName("com.android.settings",
                "com.android.settings.Settings$DevRunningServicesActivity");
        mActivityStarter.startActivity(intent, true /* dismissShade */);
    }

    @Override
    public boolean onLongClick(View v) {
        if (v == mSettingsButton) {
            startTenXActivity();
            vibrateheader();
        }
        return false;
    }

    private void startTenXActivity() {
        Intent nIntent = new Intent(Intent.ACTION_MAIN);
        nIntent.setClassName("com.android.settings",
            "com.android.settings.Settings$TenXSettingsActivity");
        mActivityStarter.startActivity(nIntent, true /* dismissShade */);
    }

    private void startSettingsActivity() {
        mActivityStarter.startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS),
                true /* dismissShade */);
    }

    @Override
    public void onUserInfoChanged(String name, Drawable picture, String userAccount) {
        if (picture != null &&
                UserManager.get(mContext).isGuestUser(KeyguardUpdateMonitor.getCurrentUser()) &&
                !(picture instanceof UserIconDrawable)) {
            picture = picture.getConstantState().newDrawable(mContext.getResources()).mutate();
            picture.setColorFilter(
                    Utils.getColorAttrDefaultColor(mContext, android.R.attr.colorForeground),
                    Mode.SRC_IN);
        }
        mMultiUserAvatar.setImageDrawable(picture);
    }
}
