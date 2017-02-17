/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.server.wm;

import static android.app.ActivityManager.StackId.PINNED_STACK_ID;
import static android.util.TypedValue.COMPLEX_UNIT_DIP;

import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;

import android.app.RemoteAction;
import android.content.pm.ParceledListSlice;
import android.content.res.Resources;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.Rect;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.Size;
import android.util.Slog;
import android.util.TypedValue;
import android.view.DisplayInfo;
import android.view.Gravity;
import android.view.IPinnedStackController;
import android.view.IPinnedStackListener;

import com.android.internal.policy.PipSnapAlgorithm;
import com.android.server.UiThread;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Holds the common state of the pinned stack between the system and SystemUI. If SystemUI ever
 * needs to be restarted, it will be notified with the last known state.
 *
 * Changes to the pinned stack also flow through this controller, and generally, the system only
 * changes the pinned stack bounds through this controller in two ways:
 *
 * 1) When first entering PiP: the controller returns the valid bounds given, taking aspect ratio
 *    and IME state into account.
 * 2) When rotating the device: the controller calculates the new bounds in the new orientation,
 *    taking the minimized and IME state into account. In this case, we currently ignore the
 *    SystemUI adjustments (ie. expanded for menu, interaction, etc).
 *
 * Other changes in the system, including adjustment of IME, configuration change, and more are
 * handled by SystemUI (similar to the docked stack divider).
 */
class PinnedStackController {

    private static final String TAG = TAG_WITH_CLASS_NAME ? "PinnedStackController" : TAG_WM;

    private final WindowManagerService mService;
    private final DisplayContent mDisplayContent;
    private final Handler mHandler = UiThread.getHandler();

    private IPinnedStackListener mPinnedStackListener;
    private final PinnedStackListenerDeathHandler mPinnedStackListenerDeathHandler =
            new PinnedStackListenerDeathHandler();

    private final PinnedStackControllerCallback mCallbacks = new PinnedStackControllerCallback();
    private final PipSnapAlgorithm mSnapAlgorithm;

    // States that affect how the PIP can be manipulated
    private boolean mIsMinimized;
    private boolean mIsImeShowing;
    private int mImeHeight;

    // The set of actions and aspect-ratio for the that are currently allowed on the PiP activity
    private ArrayList<RemoteAction> mActions = new ArrayList<>();
    private float mAspectRatio = -1f;

    // Used to calculate stack bounds across rotations
    private final DisplayInfo mDisplayInfo = new DisplayInfo();
    private final Rect mStableInsets = new Rect();

    // The size and position information that describes where the pinned stack will go by default.
    private int mDefaultStackGravity;
    private Size mDefaultStackSize;
    private Point mScreenEdgeInsets;

    // The aspect ratio bounds of the PIP.
    private float mMinAspectRatio;
    private float mMaxAspectRatio;

    // Temp vars for calculation
    private final DisplayMetrics mTmpMetrics = new DisplayMetrics();
    private final Rect mTmpInsets = new Rect();
    private final Rect mTmpRect = new Rect();
    private final Point mTmpDisplaySize = new Point();

    /**
     * The callback object passed to listeners for them to notify the controller of state changes.
     */
    private class PinnedStackControllerCallback extends IPinnedStackController.Stub {

        @Override
        public void setIsMinimized(final boolean isMinimized) {
            mHandler.post(() -> {
                mIsMinimized = isMinimized;
                mSnapAlgorithm.setMinimized(isMinimized);
            });
        }
    }

    /**
     * Handler for the case where the listener dies.
     */
    private class PinnedStackListenerDeathHandler implements IBinder.DeathRecipient {

        @Override
        public void binderDied() {
            // Clean up the state if the listener dies
            mPinnedStackListener = null;
        }
    }

    PinnedStackController(WindowManagerService service, DisplayContent displayContent) {
        mService = service;
        mDisplayContent = displayContent;
        mSnapAlgorithm = new PipSnapAlgorithm(service.mContext);
        mDisplayInfo.copyFrom(mDisplayContent.getDisplayInfo());
        reloadResources();
    }

    void onConfigurationChanged() {
        reloadResources();
        notifyMovementBoundsChanged(false /* fromImeAdjustment */);
    }

    /**
     * Reloads all the resources for the current configuration.
     */
    void reloadResources() {
        final Resources res = mService.mContext.getResources();
        final Size defaultSizeDp = Size.parseSize(res.getString(
                com.android.internal.R.string.config_defaultPictureInPictureSize));
        final Size screenEdgeInsetsDp = Size.parseSize(res.getString(
                com.android.internal.R.string.config_defaultPictureInPictureScreenEdgeInsets));
        mDefaultStackGravity = res.getInteger(
                com.android.internal.R.integer.config_defaultPictureInPictureGravity);
        mDisplayContent.getDisplay().getRealMetrics(mTmpMetrics);
        mDefaultStackSize = new Size(dpToPx(defaultSizeDp.getWidth(), mTmpMetrics),
                dpToPx(defaultSizeDp.getHeight(), mTmpMetrics));
        mScreenEdgeInsets = new Point(dpToPx(screenEdgeInsetsDp.getWidth(), mTmpMetrics),
                dpToPx(screenEdgeInsetsDp.getHeight(), mTmpMetrics));
        mMinAspectRatio = res.getFloat(
                com.android.internal.R.dimen.config_pictureInPictureMinAspectRatio);
        mMaxAspectRatio = res.getFloat(
                com.android.internal.R.dimen.config_pictureInPictureMaxAspectRatio);
    }

    /**
     * Registers a pinned stack listener.
     */
    void registerPinnedStackListener(IPinnedStackListener listener) {
        try {
            listener.asBinder().linkToDeath(mPinnedStackListenerDeathHandler, 0);
            listener.onListenerRegistered(mCallbacks);
            mPinnedStackListener = listener;
            notifyImeVisibilityChanged(mIsImeShowing, mImeHeight);
            // The movement bounds notification needs to be sent before the minimized state, since
            // SystemUI may use the bounds to retore the minimized position
            notifyMovementBoundsChanged(false /* fromImeAdjustment */);
            notifyActionsChanged(mActions);
            notifyMinimizeChanged(mIsMinimized);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to register pinned stack listener", e);
        }
    }

    /**
     * @return whether the given {@param aspectRatio} is valid.
     */
    public boolean isValidPictureInPictureAspectRatio(float aspectRatio) {
        return mMinAspectRatio <= aspectRatio && aspectRatio <= mMaxAspectRatio;
    }

    /**
     * Returns the current bounds (or the default bounds if there are no current bounds) with the
     * specified aspect ratio.
     */
    Rect transformBoundsToAspectRatio(Rect stackBounds, float aspectRatio) {
        // Save the snap fraction, calculate the aspect ratio based on the current bounds
        final float snapFraction = mSnapAlgorithm.getSnapFraction(stackBounds,
                getMovementBounds(stackBounds));
        final float radius = PointF.length(stackBounds.width(), stackBounds.height());
        final int height = (int) Math.round(Math.sqrt((radius * radius) /
                (aspectRatio * aspectRatio + 1)));
        final int width = Math.round(height * aspectRatio);
        final int left = (int) (stackBounds.centerX() - width / 2f);
        final int top = (int) (stackBounds.centerY() - height / 2f);
        stackBounds.set(left, top, left + width, top + height);
        mSnapAlgorithm.applySnapFraction(stackBounds, getMovementBounds(stackBounds), snapFraction);
        if (mIsMinimized) {
            applyMinimizedOffset(stackBounds, getMovementBounds(stackBounds));
        }
        return stackBounds;
    }

    /**
     * @return the default bounds to show the PIP when there is no active PIP.
     */
    Rect getDefaultBounds() {
        final Rect insetBounds = new Rect();
        getInsetBounds(insetBounds);

        final Rect defaultBounds = new Rect();
        Gravity.apply(mDefaultStackGravity, mDefaultStackSize.getWidth(),
                mDefaultStackSize.getHeight(), insetBounds, 0, 0, defaultBounds);
        return defaultBounds;
    }

    /**
     * @return the movement bounds for the given {@param stackBounds} and the current state of the
     *         controller.
     */
    private Rect getMovementBounds(Rect stackBounds) {
        return getMovementBounds(stackBounds, true /* adjustForIme */);
    }

    /**
     * @return the movement bounds for the given {@param stackBounds} and the current state of the
     *         controller.
     */
    private Rect getMovementBounds(Rect stackBounds, boolean adjustForIme) {
        final Rect movementBounds = new Rect();
        getInsetBounds(movementBounds);

        // Apply the movement bounds adjustments based on the current state
        mSnapAlgorithm.getMovementBounds(stackBounds, movementBounds, movementBounds,
                (adjustForIme && mIsImeShowing) ? mImeHeight : 0);
        return movementBounds;
    }

    /**
     * @param preChangeTargetBounds The final bounds of the stack if it is currently animating
     * @return the repositioned PIP bounds given it's pre-change bounds, and the new display
     *         content.
     */
    Rect onDisplayChanged(Rect preChangeStackBounds, Rect preChangeTargetBounds,
            DisplayContent displayContent) {
        final Rect postChangeStackBounds = new Rect(preChangeTargetBounds);
        final DisplayInfo displayInfo = displayContent.getDisplayInfo();
        if (!mDisplayInfo.equals(displayInfo)) {
            // Calculate the snap fraction of the current stack along the old movement bounds, and
            // then update the stack bounds to the same fraction along the rotated movement bounds.
            final Rect preChangeMovementBounds = getMovementBounds(preChangeStackBounds);
            final float snapFraction = mSnapAlgorithm.getSnapFraction(preChangeStackBounds,
                    preChangeMovementBounds);
            mDisplayInfo.copyFrom(displayInfo);

            final Rect postChangeMovementBounds = getMovementBounds(preChangeStackBounds,
                    false /* adjustForIme */);
            mSnapAlgorithm.applySnapFraction(postChangeStackBounds, postChangeMovementBounds,
                    snapFraction);
            if (mIsMinimized) {
                applyMinimizedOffset(postChangeStackBounds, postChangeMovementBounds);
            }
            notifyMovementBoundsChanged(false /* fromImeAdjustment */);
        }
        return postChangeStackBounds;
    }

    /**
     * Sets the Ime state and height.
     */
    void setAdjustedForIme(boolean adjustedForIme, int imeHeight) {
        // Return early if there is no state change
        if (mIsImeShowing == adjustedForIme && mImeHeight == imeHeight) {
            return;
        }

        mIsImeShowing = adjustedForIme;
        mImeHeight = imeHeight;
        notifyImeVisibilityChanged(adjustedForIme, imeHeight);
        notifyMovementBoundsChanged(true /* fromImeAdjustment */);
    }

    /**
     * Sets the current aspect ratio.
     */
    void setAspectRatio(float aspectRatio) {
        if (Float.compare(mAspectRatio, aspectRatio) != 0) {
            mAspectRatio = aspectRatio;
            notifyMovementBoundsChanged(false /* fromImeAdjustment */);
        }
    }

    /**
     * Sets the current set of actions.
     */
    void setActions(List<RemoteAction> actions) {
        mActions.clear();
        if (actions != null) {
            mActions.addAll(actions);
        }
        notifyActionsChanged(mActions);
    }

    /**
     * Notifies listeners that the PIP needs to be adjusted for the IME.
     */
    private void notifyImeVisibilityChanged(boolean imeVisible, int imeHeight) {
        if (mPinnedStackListener != null) {
            try {
                mPinnedStackListener.onImeVisibilityChanged(imeVisible, imeHeight);
            } catch (RemoteException e) {
                Slog.e(TAG_WM, "Error delivering bounds changed event.", e);
            }
        }
    }

    /**
     * Notifies listeners that the PIP minimized state has changed.
     */
    private void notifyMinimizeChanged(boolean isMinimized) {
        if (mPinnedStackListener != null) {
            try {
                mPinnedStackListener.onMinimizedStateChanged(isMinimized);
            } catch (RemoteException e) {
                Slog.e(TAG_WM, "Error delivering minimize changed event.", e);
            }
        }
    }

    /**
     * Notifies listeners that the PIP actions have changed.
     */
    private void notifyActionsChanged(List<RemoteAction> actions) {
        if (mPinnedStackListener != null) {
            try {
                mPinnedStackListener.onActionsChanged(new ParceledListSlice(actions));
            } catch (RemoteException e) {
                Slog.e(TAG_WM, "Error delivering actions changed event.", e);
            }
        }
    }

    /**
     * Notifies listeners that the PIP movement bounds have changed.
     */
    private void notifyMovementBoundsChanged(boolean fromImeAdjustement) {
        if (mPinnedStackListener != null) {
            try {
                Rect insetBounds = new Rect();
                getInsetBounds(insetBounds);
                Rect normalBounds = getDefaultBounds();
                if (isValidPictureInPictureAspectRatio(mAspectRatio)) {
                    transformBoundsToAspectRatio(normalBounds, mAspectRatio);
                }
                mPinnedStackListener.onMovementBoundsChanged(insetBounds, normalBounds,
                        fromImeAdjustement);
            } catch (RemoteException e) {
                Slog.e(TAG_WM, "Error delivering actions changed event.", e);
            }
        }
    }

    /**
     * @return the bounds on the screen that the PIP can be visible in.
     */
    private void getInsetBounds(Rect outRect) {
        mService.mPolicy.getStableInsetsLw(mDisplayInfo.rotation, mDisplayInfo.logicalWidth,
                mDisplayInfo.logicalHeight, mTmpInsets);
        outRect.set(mTmpInsets.left + mScreenEdgeInsets.x, mTmpInsets.top + mScreenEdgeInsets.y,
                mDisplayInfo.logicalWidth - mTmpInsets.right - mScreenEdgeInsets.x,
                mDisplayInfo.logicalHeight - mTmpInsets.bottom - mScreenEdgeInsets.y);
    }

    /**
     * Applies the minimized offsets to the given stack bounds.
     */
    private void applyMinimizedOffset(Rect stackBounds, Rect movementBounds) {
        mTmpDisplaySize.set(mDisplayInfo.logicalWidth, mDisplayInfo.logicalHeight);
        mService.getStableInsetsLocked(mDisplayContent.getDisplayId(), mStableInsets);
        mSnapAlgorithm.applyMinimizedOffset(stackBounds, movementBounds, mTmpDisplaySize,
                mStableInsets);
    }

    /**
     * @return the pixels for a given dp value.
     */
    private int dpToPx(float dpValue, DisplayMetrics dm) {
        return (int) TypedValue.applyDimension(COMPLEX_UNIT_DIP, dpValue, dm);
    }

    void dump(String prefix, PrintWriter pw) {
        pw.println(prefix + "PinnedStackController");
        pw.print(prefix + "  defaultBounds="); getDefaultBounds().printShortString(pw);
        pw.println();
        mService.getStackBounds(PINNED_STACK_ID, mTmpRect);
        pw.print(prefix + "  movementBounds="); getMovementBounds(mTmpRect).printShortString(pw);
        pw.println();
        pw.println(prefix + "  mIsImeShowing=" + mIsImeShowing);
        pw.println(prefix + "  mIsMinimized=" + mIsMinimized);
        if (mActions.isEmpty()) {
            pw.println(prefix + "  mActions=[]");
        } else {
            pw.println(prefix + "  mActions=[");
            for (int i = 0; i < mActions.size(); i++) {
                RemoteAction action = mActions.get(i);
                pw.print(prefix + "    Action[" + i + "]: ");
                action.dump("", pw);
            }
            pw.println(prefix + "  ]");
        }
    }
}
