/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.wm.shell.pip.phone;

import static com.android.internal.config.sysui.SystemUiDeviceConfigFlags.PIP_STASHING;
import static com.android.internal.config.sysui.SystemUiDeviceConfigFlags.PIP_STASH_MINIMUM_VELOCITY_THRESHOLD;
import static com.android.wm.shell.pip.PipAnimationController.TRANSITION_DIRECTION_TO_PIP;
import static com.android.wm.shell.pip.PipBoundsState.STASH_TYPE_LEFT;
import static com.android.wm.shell.pip.PipBoundsState.STASH_TYPE_NONE;
import static com.android.wm.shell.pip.PipBoundsState.STASH_TYPE_RIGHT;
import static com.android.wm.shell.pip.phone.PhonePipMenuController.MENU_STATE_CLOSE;
import static com.android.wm.shell.pip.phone.PhonePipMenuController.MENU_STATE_FULL;
import static com.android.wm.shell.pip.phone.PhonePipMenuController.MENU_STATE_NONE;

import android.annotation.NonNull;
import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.Rect;
import android.provider.DeviceConfig;
import android.util.Log;
import android.util.Size;
import android.view.InputEvent;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import com.android.internal.annotations.VisibleForTesting;
import com.android.wm.shell.R;
import com.android.wm.shell.common.FloatingContentCoordinator;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.pip.PipAnimationController;
import com.android.wm.shell.pip.PipBoundsAlgorithm;
import com.android.wm.shell.pip.PipBoundsState;
import com.android.wm.shell.pip.PipTaskOrganizer;
import com.android.wm.shell.pip.PipTransitionController;
import com.android.wm.shell.pip.PipUiEventLogger;

import java.io.PrintWriter;

/**
 * Manages all the touch handling for PIP on the Phone, including moving, dismissing and expanding
 * the PIP.
 */
public class PipTouchHandler {
    @VisibleForTesting static final float MINIMUM_SIZE_PERCENT = 0.4f;

    private static final String TAG = "PipTouchHandler";
    private static final float DEFAULT_STASH_VELOCITY_THRESHOLD = 18000.f;

    // Allow PIP to resize to a slightly bigger state upon touch
    private boolean mEnableResize;
    private final Context mContext;
    private final PipBoundsAlgorithm mPipBoundsAlgorithm;
    private final @NonNull PipBoundsState mPipBoundsState;
    private final PipUiEventLogger mPipUiEventLogger;
    private final PipDismissTargetHandler mPipDismissTargetHandler;
    private final ShellExecutor mMainExecutor;

    private PipResizeGestureHandler mPipResizeGestureHandler;

    private final PhonePipMenuController mMenuController;
    private final AccessibilityManager mAccessibilityManager;
    private boolean mShowPipMenuOnAnimationEnd = false;

    /**
     * Whether PIP stash is enabled or not. When enabled, if the user flings toward the edge of the
     * screen, it will be shown in "stashed" mode, where PIP will only show partially.
     */
    private boolean mEnableStash = true;

    private float mStashVelocityThreshold;

    // The reference inset bounds, used to determine the dismiss fraction
    private final Rect mInsetBounds = new Rect();
    private int mExpandedShortestEdgeSize;

    // Used to workaround an issue where the WM rotation happens before we are notified, allowing
    // us to send stale bounds
    private int mDeferResizeToNormalBoundsUntilRotation = -1;
    private int mDisplayRotation;

    private final PipAccessibilityInteractionConnection mConnection;

    // Behaviour states
    private int mMenuState = MENU_STATE_NONE;
    private boolean mIsImeShowing;
    private int mImeHeight;
    private int mImeOffset;
    private boolean mIsShelfShowing;
    private int mShelfHeight;
    private int mMovementBoundsExtraOffsets;
    private int mBottomOffsetBufferPx;
    private float mSavedSnapFraction = -1f;
    private boolean mSendingHoverAccessibilityEvents;
    private boolean mMovementWithinDismiss;

    // Touch state
    private final PipTouchState mTouchState;
    private final FloatingContentCoordinator mFloatingContentCoordinator;
    private PipMotionHelper mMotionHelper;
    private PipTouchGesture mGesture;

    // Temp vars
    private final Rect mTmpBounds = new Rect();

    /**
     * A listener for the PIP menu activity.
     */
    private class PipMenuListener implements PhonePipMenuController.Listener {
        @Override
        public void onPipMenuStateChanged(int menuState, boolean resize, Runnable callback) {
            setMenuState(menuState, resize, callback);
        }

        @Override
        public void onPipExpand() {
            mMotionHelper.expandLeavePip();
        }

        @Override
        public void onPipDismiss() {
            mPipUiEventLogger.log(PipUiEventLogger.PipUiEventEnum.PICTURE_IN_PICTURE_TAP_TO_REMOVE);
            mTouchState.removeDoubleTapTimeoutCallback();
            mMotionHelper.dismissPip();
        }

        @Override
        public void onPipShowMenu() {
            mMenuController.showMenu(MENU_STATE_FULL, mPipBoundsState.getBounds(),
                    true /* allowMenuTimeout */, willResizeMenu(), shouldShowResizeHandle());
        }
    }

    @SuppressLint("InflateParams")
    public PipTouchHandler(Context context,
            PhonePipMenuController menuController,
            PipBoundsAlgorithm pipBoundsAlgorithm,
            @NonNull PipBoundsState pipBoundsState,
            PipTaskOrganizer pipTaskOrganizer,
            PipTransitionController pipTransitionController,
            FloatingContentCoordinator floatingContentCoordinator,
            PipUiEventLogger pipUiEventLogger,
            ShellExecutor mainExecutor) {
        // Initialize the Pip input consumer
        mContext = context;
        mMainExecutor = mainExecutor;
        mAccessibilityManager = context.getSystemService(AccessibilityManager.class);
        mPipBoundsAlgorithm = pipBoundsAlgorithm;
        mPipBoundsState = pipBoundsState;
        mMenuController = menuController;
        mPipUiEventLogger = pipUiEventLogger;
        mFloatingContentCoordinator = floatingContentCoordinator;
        mMenuController.addListener(new PipMenuListener());
        mGesture = new DefaultPipTouchGesture();
        mMotionHelper = new PipMotionHelper(mContext, pipBoundsState, pipTaskOrganizer,
                mMenuController, mPipBoundsAlgorithm.getSnapAlgorithm(), pipTransitionController,
                floatingContentCoordinator);
        mPipResizeGestureHandler =
                new PipResizeGestureHandler(context, pipBoundsAlgorithm, pipBoundsState,
                        mMotionHelper, pipTaskOrganizer, this::getMovementBounds,
                        this::updateMovementBounds, pipUiEventLogger, menuController,
                        mainExecutor);
        mPipDismissTargetHandler = new PipDismissTargetHandler(context, pipUiEventLogger,
                mMotionHelper, mainExecutor);
        mTouchState = new PipTouchState(ViewConfiguration.get(context),
                () -> {
                    if (mPipBoundsState.isStashed()) {
                        animateToUnStashedState();
                        mPipUiEventLogger.log(
                                PipUiEventLogger.PipUiEventEnum.PICTURE_IN_PICTURE_STASH_UNSTASHED);
                        mPipBoundsState.setStashed(STASH_TYPE_NONE);
                    } else {
                        mMenuController.showMenuWithPossibleDelay(MENU_STATE_FULL,
                                mPipBoundsState.getBounds(), true /* allowMenuTimeout */,
                                willResizeMenu(),
                                shouldShowResizeHandle());
                    }
                },
                menuController::hideMenu,
                mainExecutor);
        mConnection = new PipAccessibilityInteractionConnection(mContext, pipBoundsState,
                mMotionHelper, pipTaskOrganizer, mPipBoundsAlgorithm.getSnapAlgorithm(),
                this::onAccessibilityShowMenu, this::updateMovementBounds, mainExecutor);
    }

    public void init() {
        Resources res = mContext.getResources();
        mEnableResize = res.getBoolean(R.bool.config_pipEnableResizeForMenu);
        reloadResources();

        mMotionHelper.init();
        mPipResizeGestureHandler.init();
        mPipDismissTargetHandler.init();

        mEnableStash = DeviceConfig.getBoolean(
                DeviceConfig.NAMESPACE_SYSTEMUI,
                PIP_STASHING,
                /* defaultValue = */ true);
        DeviceConfig.addOnPropertiesChangedListener(DeviceConfig.NAMESPACE_SYSTEMUI,
                mMainExecutor,
                properties -> {
                    if (properties.getKeyset().contains(PIP_STASHING)) {
                        mEnableStash = properties.getBoolean(
                                PIP_STASHING, /* defaultValue = */ true);
                    }
                });
        mStashVelocityThreshold = DeviceConfig.getFloat(
                DeviceConfig.NAMESPACE_SYSTEMUI,
                PIP_STASH_MINIMUM_VELOCITY_THRESHOLD,
                DEFAULT_STASH_VELOCITY_THRESHOLD);
        DeviceConfig.addOnPropertiesChangedListener(DeviceConfig.NAMESPACE_SYSTEMUI,
                mMainExecutor,
                properties -> {
                    if (properties.getKeyset().contains(PIP_STASH_MINIMUM_VELOCITY_THRESHOLD)) {
                        mStashVelocityThreshold = properties.getFloat(
                                PIP_STASH_MINIMUM_VELOCITY_THRESHOLD,
                                DEFAULT_STASH_VELOCITY_THRESHOLD);
                    }
                });
    }

    private void reloadResources() {
        final Resources res = mContext.getResources();
        mBottomOffsetBufferPx = res.getDimensionPixelSize(R.dimen.pip_bottom_offset_buffer);
        mExpandedShortestEdgeSize = res.getDimensionPixelSize(
                R.dimen.pip_expanded_shortest_edge_size);
        mImeOffset = res.getDimensionPixelSize(R.dimen.pip_ime_offset);
        mPipDismissTargetHandler.updateMagneticTargetSize();
    }

    private boolean shouldShowResizeHandle() {
        return false;
    }

    public void setTouchGesture(PipTouchGesture gesture) {
        mGesture = gesture;
    }

    public void setTouchEnabled(boolean enabled) {
        mTouchState.setAllowTouches(enabled);
    }

    public void showPictureInPictureMenu() {
        // Only show the menu if the user isn't currently interacting with the PiP
        if (!mTouchState.isUserInteracting()) {
            mMenuController.showMenu(MENU_STATE_FULL, mPipBoundsState.getBounds(),
                    false /* allowMenuTimeout */, willResizeMenu(),
                    shouldShowResizeHandle());
        }
    }

    public void onActivityPinned() {
        mPipDismissTargetHandler.createOrUpdateDismissTarget();

        mShowPipMenuOnAnimationEnd = true;
        mPipResizeGestureHandler.onActivityPinned();
        mFloatingContentCoordinator.onContentAdded(mMotionHelper);
    }

    public void onActivityUnpinned(ComponentName topPipActivity) {
        if (topPipActivity == null) {
            // Clean up state after the last PiP activity is removed
            mPipDismissTargetHandler.cleanUpDismissTarget();

            mFloatingContentCoordinator.onContentRemoved(mMotionHelper);
        }
        mPipResizeGestureHandler.onActivityUnpinned();
    }

    public void onPinnedStackAnimationEnded(
            @PipAnimationController.TransitionDirection int direction) {
        // Always synchronize the motion helper bounds once PiP animations finish
        mMotionHelper.synchronizePinnedStackBounds();
        updateMovementBounds();
        if (direction == TRANSITION_DIRECTION_TO_PIP) {
            // Set the initial bounds as the user resize bounds.
            mPipResizeGestureHandler.setUserResizeBounds(mPipBoundsState.getBounds());
        }

        if (mShowPipMenuOnAnimationEnd) {
            mMenuController.showMenu(MENU_STATE_CLOSE, mPipBoundsState.getBounds(),
                    true /* allowMenuTimeout */, false /* willResizeMenu */,
                    shouldShowResizeHandle());
            mShowPipMenuOnAnimationEnd = false;
        }
    }

    public void onConfigurationChanged() {
        mPipResizeGestureHandler.onConfigurationChanged();
        mMotionHelper.synchronizePinnedStackBounds();
        reloadResources();

        // Recreate the dismiss target for the new orientation.
        mPipDismissTargetHandler.createOrUpdateDismissTarget();
    }

    public void onImeVisibilityChanged(boolean imeVisible, int imeHeight) {
        mIsImeShowing = imeVisible;
        mImeHeight = imeHeight;
    }

    public void onShelfVisibilityChanged(boolean shelfVisible, int shelfHeight) {
        mIsShelfShowing = shelfVisible;
        mShelfHeight = shelfHeight;
    }

    /**
     * Called when SysUI state changed.
     *
     * @param isSysUiStateValid Is SysUI valid or not.
     */
    public void onSystemUiStateChanged(boolean isSysUiStateValid) {
        mPipResizeGestureHandler.onSystemUiStateChanged(isSysUiStateValid);
    }

    public void adjustBoundsForRotation(Rect outBounds, Rect curBounds, Rect insetBounds) {
        final Rect toMovementBounds = new Rect();
        mPipBoundsAlgorithm.getMovementBounds(outBounds, insetBounds, toMovementBounds, 0);
        final int prevBottom = mPipBoundsState.getMovementBounds().bottom
                - mMovementBoundsExtraOffsets;
        if ((prevBottom - mBottomOffsetBufferPx) <= curBounds.top) {
            outBounds.offsetTo(outBounds.left, toMovementBounds.bottom);
        }
    }

    /**
     * Responds to IPinnedStackListener on resetting aspect ratio for the pinned window.
     */
    public void onAspectRatioChanged() {
        mPipResizeGestureHandler.invalidateUserResizeBounds();
    }

    public void onMovementBoundsChanged(Rect insetBounds, Rect normalBounds, Rect curBounds,
            boolean fromImeAdjustment, boolean fromShelfAdjustment, int displayRotation) {
        // Set the user resized bounds equal to the new normal bounds in case they were
        // invalidated (e.g. by an aspect ratio change).
        if (mPipResizeGestureHandler.getUserResizeBounds().isEmpty()) {
            mPipResizeGestureHandler.setUserResizeBounds(normalBounds);
        }

        final int bottomOffset = mIsImeShowing ? mImeHeight : 0;
        final boolean fromDisplayRotationChanged = (mDisplayRotation != displayRotation);
        if (fromDisplayRotationChanged) {
            mTouchState.reset();
        }

        // Re-calculate the expanded bounds
        Rect normalMovementBounds = new Rect();
        mPipBoundsAlgorithm.getMovementBounds(normalBounds, insetBounds,
                normalMovementBounds, bottomOffset);

        if (mPipBoundsState.getMovementBounds().isEmpty()) {
            // mMovementBounds is not initialized yet and a clean movement bounds without
            // bottom offset shall be used later in this function.
            mPipBoundsAlgorithm.getMovementBounds(curBounds, insetBounds,
                    mPipBoundsState.getMovementBounds(), 0 /* bottomOffset */);
        }

        // Calculate the expanded size
        float aspectRatio = (float) normalBounds.width() / normalBounds.height();
        Point displaySize = new Point();
        mContext.getDisplay().getRealSize(displaySize);
        Size expandedSize = mPipBoundsAlgorithm.getSizeForAspectRatio(
                aspectRatio, mExpandedShortestEdgeSize, displaySize.x, displaySize.y);
        mPipBoundsState.setExpandedBounds(
                new Rect(0, 0, expandedSize.getWidth(), expandedSize.getHeight()));
        Rect expandedMovementBounds = new Rect();
        mPipBoundsAlgorithm.getMovementBounds(
                mPipBoundsState.getExpandedBounds(), insetBounds, expandedMovementBounds,
                bottomOffset);

        if (mPipResizeGestureHandler.isUsingPinchToZoom()) {
            updatePinchResizeSizeConstraints(insetBounds, normalBounds, aspectRatio);
        } else {
            mPipResizeGestureHandler.updateMinSize(normalBounds.width(), normalBounds.height());
            mPipResizeGestureHandler.updateMaxSize(mPipBoundsState.getExpandedBounds().width(),
                    mPipBoundsState.getExpandedBounds().height());
        }

        // The extra offset does not really affect the movement bounds, but are applied based on the
        // current state (ime showing, or shelf offset) when we need to actually shift
        int extraOffset = Math.max(
                mIsImeShowing ? mImeOffset : 0,
                !mIsImeShowing && mIsShelfShowing ? mShelfHeight : 0);

        // If this is from an IME or shelf adjustment, then we should move the PiP so that it is not
        // occluded by the IME or shelf.
        if (fromImeAdjustment || fromShelfAdjustment) {
            if (mTouchState.isUserInteracting()) {
                // Defer the update of the current movement bounds until after the user finishes
                // touching the screen
            } else {
                final boolean isExpanded = mMenuState == MENU_STATE_FULL && willResizeMenu();
                final Rect toMovementBounds = new Rect();
                mPipBoundsAlgorithm.getMovementBounds(curBounds, insetBounds,
                        toMovementBounds, mIsImeShowing ? mImeHeight : 0);
                final int prevBottom = mPipBoundsState.getMovementBounds().bottom
                        - mMovementBoundsExtraOffsets;
                // This is to handle landscape fullscreen IMEs, don't apply the extra offset in this
                // case
                final int toBottom = toMovementBounds.bottom < toMovementBounds.top
                        ? toMovementBounds.bottom
                        : toMovementBounds.bottom - extraOffset;

                if (isExpanded) {
                    curBounds.set(mPipBoundsState.getExpandedBounds());
                    mPipBoundsAlgorithm.getSnapAlgorithm().applySnapFraction(curBounds,
                            toMovementBounds, mSavedSnapFraction);
                }

                if (prevBottom < toBottom) {
                    // The movement bounds are expanding
                    if (curBounds.top > prevBottom - mBottomOffsetBufferPx) {
                        mMotionHelper.animateToOffset(curBounds, toBottom - curBounds.top);
                    }
                } else if (prevBottom > toBottom) {
                    // The movement bounds are shrinking
                    if (curBounds.top > toBottom - mBottomOffsetBufferPx) {
                        mMotionHelper.animateToOffset(curBounds, toBottom - curBounds.top);
                    }
                }
            }
        }

        // Update the movement bounds after doing the calculations based on the old movement bounds
        // above
        mPipBoundsState.setNormalMovementBounds(normalMovementBounds);
        mPipBoundsState.setExpandedMovementBounds(expandedMovementBounds);
        mDisplayRotation = displayRotation;
        mInsetBounds.set(insetBounds);
        updateMovementBounds();
        mMovementBoundsExtraOffsets = extraOffset;
        mConnection.onMovementBoundsChanged(normalBounds, mPipBoundsState.getExpandedBounds(),
                mPipBoundsState.getNormalMovementBounds(),
                mPipBoundsState.getExpandedMovementBounds());

        // If we have a deferred resize, apply it now
        if (mDeferResizeToNormalBoundsUntilRotation == displayRotation) {
            mMotionHelper.animateToUnexpandedState(normalBounds, mSavedSnapFraction,
                    mPipBoundsState.getNormalMovementBounds(), mPipBoundsState.getMovementBounds(),
                    true /* immediate */);
            mSavedSnapFraction = -1f;
            mDeferResizeToNormalBoundsUntilRotation = -1;
        }
    }

    private void updatePinchResizeSizeConstraints(Rect insetBounds, Rect normalBounds,
            float aspectRatio) {
        final int shorterLength = Math.min(mPipBoundsState.getDisplayBounds().width(),
                mPipBoundsState.getDisplayBounds().height());
        final int totalPadding = insetBounds.left * 2;
        final int minWidth, minHeight, maxWidth, maxHeight;
        if (aspectRatio > 1f) {
            minWidth = (int) Math.min(normalBounds.width(), shorterLength * MINIMUM_SIZE_PERCENT);
            minHeight = (int) (minWidth / aspectRatio);
            maxWidth = (int) Math.max(normalBounds.width(), shorterLength - totalPadding);
            maxHeight = (int) (maxWidth / aspectRatio);
        } else {
            minHeight = (int) Math.min(normalBounds.height(), shorterLength * MINIMUM_SIZE_PERCENT);
            minWidth = (int) (minHeight * aspectRatio);
            maxHeight = (int) Math.max(normalBounds.height(), shorterLength - totalPadding);
            maxWidth = (int) (maxHeight * aspectRatio);
        }

        mPipResizeGestureHandler.updateMinSize(minWidth, minHeight);
        mPipResizeGestureHandler.updateMaxSize(maxWidth, maxHeight);
        mPipBoundsState.setMaxSize(maxWidth, maxHeight);
        mPipBoundsState.setMinSize(minWidth, minHeight);
    }

    /**
     * TODO Add appropriate description
     */
    public void onRegistrationChanged(boolean isRegistered) {
        if (isRegistered) {
            mConnection.register(mAccessibilityManager);
        } else {
            mAccessibilityManager.setPictureInPictureActionReplacingConnection(null);
        }
        if (!isRegistered && mTouchState.isUserInteracting()) {
            // If the input consumer is unregistered while the user is interacting, then we may not
            // get the final TOUCH_UP event, so clean up the dismiss target as well
            mPipDismissTargetHandler.cleanUpDismissTarget();
        }
    }

    private void onAccessibilityShowMenu() {
        mMenuController.showMenu(MENU_STATE_FULL, mPipBoundsState.getBounds(),
                true /* allowMenuTimeout */, willResizeMenu(),
                shouldShowResizeHandle());
    }

    /**
     * TODO Add appropriate description
     */
    public boolean handleTouchEvent(InputEvent inputEvent) {
        // Skip any non motion events
        if (!(inputEvent instanceof MotionEvent)) {
            return true;
        }

        MotionEvent ev = (MotionEvent) inputEvent;
        if (!mPipBoundsState.isStashed() && mPipResizeGestureHandler.willStartResizeGesture(ev)) {
            // Initialize the touch state for the gesture, but immediately reset to invalidate the
            // gesture
            mTouchState.onTouchEvent(ev);
            mTouchState.reset();
            return true;
        }

        if (mPipResizeGestureHandler.hasOngoingGesture()) {
            mPipDismissTargetHandler.hideDismissTargetMaybe();
            return true;
        }

        if ((ev.getAction() == MotionEvent.ACTION_DOWN || mTouchState.isUserInteracting())
                && mPipDismissTargetHandler.maybeConsumeMotionEvent(ev)) {
            // If the first touch event occurs within the magnetic field, pass the ACTION_DOWN event
            // to the touch state. Touch state needs a DOWN event in order to later process MOVE
            // events it'll receive if the object is dragged out of the magnetic field.
            if (ev.getAction() == MotionEvent.ACTION_DOWN) {
                mTouchState.onTouchEvent(ev);
            }

            // Continue tracking velocity when the object is in the magnetic field, since we want to
            // respect touch input velocity if the object is dragged out and then flung.
            mTouchState.addMovementToVelocityTracker(ev);

            return true;
        }

        // Update the touch state
        mTouchState.onTouchEvent(ev);

        boolean shouldDeliverToMenu = mMenuState != MENU_STATE_NONE;

        switch (ev.getAction()) {
            case MotionEvent.ACTION_DOWN: {
                mGesture.onDown(mTouchState);
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                if (mGesture.onMove(mTouchState)) {
                    break;
                }

                shouldDeliverToMenu = !mTouchState.isDragging();
                break;
            }
            case MotionEvent.ACTION_UP: {
                // Update the movement bounds again if the state has changed since the user started
                // dragging (ie. when the IME shows)
                updateMovementBounds();

                if (mGesture.onUp(mTouchState)) {
                    break;
                }

                // Fall through to clean up
            }
            case MotionEvent.ACTION_CANCEL: {
                shouldDeliverToMenu = !mTouchState.startedDragging() && !mTouchState.isDragging();
                mTouchState.reset();
                break;
            }
            case MotionEvent.ACTION_HOVER_ENTER:
                // If Touch Exploration is enabled, some a11y services (e.g. Talkback) is probably
                // on and changing MotionEvents into HoverEvents.
                // Let's not enable menu show/hide for a11y services.
                if (!mAccessibilityManager.isTouchExplorationEnabled()) {
                    mTouchState.removeHoverExitTimeoutCallback();
                    mMenuController.showMenu(MENU_STATE_FULL, mPipBoundsState.getBounds(),
                            false /* allowMenuTimeout */, false /* willResizeMenu */,
                            shouldShowResizeHandle());
                }
            case MotionEvent.ACTION_HOVER_MOVE: {
                if (!shouldDeliverToMenu && !mSendingHoverAccessibilityEvents) {
                    sendAccessibilityHoverEvent(AccessibilityEvent.TYPE_VIEW_HOVER_ENTER);
                    mSendingHoverAccessibilityEvents = true;
                }
                break;
            }
            case MotionEvent.ACTION_HOVER_EXIT: {
                // If Touch Exploration is enabled, some a11y services (e.g. Talkback) is probably
                // on and changing MotionEvents into HoverEvents.
                // Let's not enable menu show/hide for a11y services.
                if (!mAccessibilityManager.isTouchExplorationEnabled()) {
                    mTouchState.scheduleHoverExitTimeoutCallback();
                }
                if (!shouldDeliverToMenu && mSendingHoverAccessibilityEvents) {
                    sendAccessibilityHoverEvent(AccessibilityEvent.TYPE_VIEW_HOVER_EXIT);
                    mSendingHoverAccessibilityEvents = false;
                }
                break;
            }
        }

        shouldDeliverToMenu |= !mPipBoundsState.isStashed();

        // Deliver the event to PipMenuActivity to handle button click if the menu has shown.
        if (shouldDeliverToMenu) {
            final MotionEvent cloneEvent = MotionEvent.obtain(ev);
            // Send the cancel event and cancel menu timeout if it starts to drag.
            if (mTouchState.startedDragging()) {
                cloneEvent.setAction(MotionEvent.ACTION_CANCEL);
                mMenuController.pokeMenu();
            }

            mMenuController.handlePointerEvent(cloneEvent);
        }

        return true;
    }

    private void sendAccessibilityHoverEvent(int type) {
        if (!mAccessibilityManager.isEnabled()) {
            return;
        }

        AccessibilityEvent event = AccessibilityEvent.obtain(type);
        event.setImportantForAccessibility(true);
        event.setSourceNodeId(AccessibilityNodeInfo.ROOT_NODE_ID);
        event.setWindowId(
                AccessibilityWindowInfo.PICTURE_IN_PICTURE_ACTION_REPLACER_WINDOW_ID);
        mAccessibilityManager.sendAccessibilityEvent(event);
    }

    /**
     * Sets the menu visibility.
     */
    private void setMenuState(int menuState, boolean resize, Runnable callback) {
        if (mMenuState == menuState && !resize) {
            return;
        }

        if (menuState == MENU_STATE_FULL && mMenuState != MENU_STATE_FULL) {
            // Save the current snap fraction and if we do not drag or move the PiP, then
            // we store back to this snap fraction.  Otherwise, we'll reset the snap
            // fraction and snap to the closest edge.
            if (resize) {
                animateToExpandedState(callback);
            }
        } else if (menuState == MENU_STATE_NONE && mMenuState == MENU_STATE_FULL) {
            // Try and restore the PiP to the closest edge, using the saved snap fraction
            // if possible
            if (resize) {
                if (mDeferResizeToNormalBoundsUntilRotation == -1) {
                    // This is a very special case: when the menu is expanded and visible,
                    // navigating to another activity can trigger auto-enter PiP, and if the
                    // revealed activity has a forced rotation set, then the controller will get
                    // updated with the new rotation of the display. However, at the same time,
                    // SystemUI will try to hide the menu by creating an animation to the normal
                    // bounds which are now stale.  In such a case we defer the animation to the
                    // normal bounds until after the next onMovementBoundsChanged() call to get the
                    // bounds in the new orientation
                    int displayRotation = mContext.getDisplay().getRotation();
                    if (mDisplayRotation != displayRotation) {
                        mDeferResizeToNormalBoundsUntilRotation = displayRotation;
                    }
                }

                if (mDeferResizeToNormalBoundsUntilRotation == -1) {
                    animateToUnexpandedState(getUserResizeBounds());
                }
            } else {
                mSavedSnapFraction = -1f;
            }
        }
        mMenuState = menuState;
        updateMovementBounds();
        // If pip menu has dismissed, we should register the A11y ActionReplacingConnection for pip
        // as well, or it can't handle a11y focus and pip menu can't perform any action.
        onRegistrationChanged(menuState == MENU_STATE_NONE);
        if (menuState == MENU_STATE_NONE) {
            mPipUiEventLogger.log(PipUiEventLogger.PipUiEventEnum.PICTURE_IN_PICTURE_HIDE_MENU);
        } else if (menuState == MENU_STATE_FULL) {
            mPipUiEventLogger.log(PipUiEventLogger.PipUiEventEnum.PICTURE_IN_PICTURE_SHOW_MENU);
        }
    }

    private void animateToMaximizedState(Runnable callback) {
        Rect maxMovementBounds = new Rect();
        Rect maxBounds = new Rect(0, 0, mPipBoundsState.getMaxSize().x,
                mPipBoundsState.getMaxSize().y);
        mPipBoundsAlgorithm.getMovementBounds(maxBounds, mInsetBounds, maxMovementBounds,
                mIsImeShowing ? mImeHeight : 0);
        mSavedSnapFraction = mMotionHelper.animateToExpandedState(maxBounds,
                mPipBoundsState.getMovementBounds(), maxMovementBounds,
                callback);
    }

    private void animateToMinimizedState() {
        animateToUnexpandedState(new Rect(0, 0, mPipBoundsState.getMinSize().x,
                mPipBoundsState.getMinSize().y));
    }

    private void animateToExpandedState(Runnable callback) {
        mPipResizeGestureHandler.setUserResizeBounds(mPipBoundsState.getBounds());
        final Rect currentBounds = mPipBoundsState.getBounds();
        final Rect expandedBounds = mPipBoundsState.getExpandedBounds();
        Rect finalExpandedBounds = new Rect(expandedBounds.width() > expandedBounds.width()
                        && expandedBounds.height() > expandedBounds.height()
                        ? currentBounds : expandedBounds);
        Rect restoredMovementBounds = new Rect();
        mPipBoundsAlgorithm.getMovementBounds(finalExpandedBounds,
                mInsetBounds, restoredMovementBounds, mIsImeShowing ? mImeHeight : 0);
        mSavedSnapFraction = mMotionHelper.animateToExpandedState(finalExpandedBounds,
                mPipBoundsState.getMovementBounds(), restoredMovementBounds, callback);
    }

    private void animateToUnexpandedState(Rect restoreBounds) {
        Rect restoredMovementBounds = new Rect();
        mPipBoundsAlgorithm.getMovementBounds(restoreBounds,
                mInsetBounds, restoredMovementBounds, mIsImeShowing ? mImeHeight : 0);
        mMotionHelper.animateToUnexpandedState(restoreBounds, mSavedSnapFraction,
                restoredMovementBounds, mPipBoundsState.getMovementBounds(), false /* immediate */);
        mSavedSnapFraction = -1f;
    }

    private void animateToUnStashedState() {
        final Rect pipBounds = mPipBoundsState.getBounds();
        final boolean onLeftEdge = pipBounds.left < mPipBoundsState.getDisplayBounds().left;
        final Rect unStashedBounds = new Rect(0, pipBounds.top, 0, pipBounds.bottom);
        unStashedBounds.left = onLeftEdge ? mInsetBounds.left
                : mInsetBounds.right - pipBounds.width();
        unStashedBounds.right = onLeftEdge ? mInsetBounds.left + pipBounds.width()
                : mInsetBounds.right;
        mMotionHelper.animateToUnStashedBounds(unStashedBounds);
    }

    /**
     * @return the motion helper.
     */
    public PipMotionHelper getMotionHelper() {
        return mMotionHelper;
    }

    @VisibleForTesting
    public PipResizeGestureHandler getPipResizeGestureHandler() {
        return mPipResizeGestureHandler;
    }

    @VisibleForTesting
    public void setPipResizeGestureHandler(PipResizeGestureHandler pipResizeGestureHandler) {
        mPipResizeGestureHandler = pipResizeGestureHandler;
    }

    @VisibleForTesting
    public void setPipMotionHelper(PipMotionHelper pipMotionHelper) {
        mMotionHelper = pipMotionHelper;
    }

    Rect getUserResizeBounds() {
        return mPipResizeGestureHandler.getUserResizeBounds();
    }

    /**
     * Gesture controlling normal movement of the PIP.
     */
    private class DefaultPipTouchGesture extends PipTouchGesture {
        private final Point mStartPosition = new Point();
        private final PointF mDelta = new PointF();
        private boolean mShouldHideMenuAfterFling;
        private float mDownSavedFraction = -1f;

        @Override
        public void onDown(PipTouchState touchState) {
            if (!touchState.isUserInteracting()) {
                return;
            }

            Rect bounds = getPossiblyMotionBounds();
            mDelta.set(0f, 0f);
            mStartPosition.set(bounds.left, bounds.top);
            mMovementWithinDismiss = touchState.getDownTouchPosition().y
                    >= mPipBoundsState.getMovementBounds().bottom;
            mMotionHelper.setSpringingToTouch(false);
            mDownSavedFraction = mPipBoundsAlgorithm.getSnapFraction(mPipBoundsState.getBounds());

            // If the menu is still visible then just poke the menu
            // so that it will timeout after the user stops touching it
            if (mMenuState != MENU_STATE_NONE && !mPipBoundsState.isStashed()) {
                mMenuController.pokeMenu();
            }
        }

        @Override
        public boolean onMove(PipTouchState touchState) {
            if (!touchState.isUserInteracting()) {
                return false;
            }

            if (touchState.startedDragging()) {
                mSavedSnapFraction = -1f;
                mPipDismissTargetHandler.showDismissTargetMaybe();
            }

            if (touchState.isDragging()) {
                // Move the pinned stack freely
                final PointF lastDelta = touchState.getLastTouchDelta();
                float lastX = mStartPosition.x + mDelta.x;
                float lastY = mStartPosition.y + mDelta.y;
                float left = lastX + lastDelta.x;
                float top = lastY + lastDelta.y;

                // Add to the cumulative delta after bounding the position
                mDelta.x += left - lastX;
                mDelta.y += top - lastY;

                mTmpBounds.set(getPossiblyMotionBounds());
                mTmpBounds.offsetTo((int) left, (int) top);
                mMotionHelper.movePip(mTmpBounds, true /* isDragging */);

                final PointF curPos = touchState.getLastTouchPosition();
                if (mMovementWithinDismiss) {
                    // Track if movement remains near the bottom edge to identify swipe to dismiss
                    mMovementWithinDismiss = curPos.y >= mPipBoundsState.getMovementBounds().bottom;
                }
                return true;
            }
            return false;
        }

        @Override
        public boolean onUp(PipTouchState touchState) {
            mPipDismissTargetHandler.hideDismissTargetMaybe();

            if (!touchState.isUserInteracting()) {
                return false;
            }

            final PointF vel = touchState.getVelocity();

            if (touchState.isDragging()) {
                if (mMenuState != MENU_STATE_NONE) {
                    // If the menu is still visible, then just poke the menu so that
                    // it will timeout after the user stops touching it
                    mMenuController.showMenu(mMenuState, mPipBoundsState.getBounds(),
                            true /* allowMenuTimeout */, willResizeMenu(),
                            shouldShowResizeHandle());
                }
                mShouldHideMenuAfterFling = mMenuState == MENU_STATE_NONE;

                // Reset the touch state on up before the fling settles
                mTouchState.reset();
                if (mEnableStash && shouldStash(vel, getPossiblyMotionBounds())) {
                    mMotionHelper.stashToEdge(vel.x, vel.y, this::stashEndAction /* endAction */);
                } else {
                    mPipUiEventLogger.log(
                            PipUiEventLogger.PipUiEventEnum.PICTURE_IN_PICTURE_STASH_UNSTASHED);
                    mPipBoundsState.setStashed(STASH_TYPE_NONE);
                    mMotionHelper.flingToSnapTarget(vel.x, vel.y,
                            this::flingEndAction /* endAction */);
                }
            } else if (mTouchState.isDoubleTap() && !mPipBoundsState.isStashed()) {
                // If using pinch to zoom, double-tap functions as resizing between max/min size
                if (mPipResizeGestureHandler.isUsingPinchToZoom()) {
                    final boolean toExpand = mPipBoundsState.getBounds().width()
                            < mPipBoundsState.getMaxSize().x
                            && mPipBoundsState.getBounds().height()
                            < mPipBoundsState.getMaxSize().y;
                    if (mMenuController.isMenuVisible()) {
                        mMenuController.hideMenu(false /* animate */, false /* resize */);
                    }
                    if (toExpand) {
                        mPipResizeGestureHandler.setUserResizeBounds(mPipBoundsState.getBounds());
                        animateToMaximizedState(null);
                    } else {
                        animateToUnexpandedState(getUserResizeBounds());
                    }
                } else {
                    // Expand to fullscreen if this is a double tap
                    // the PiP should be frozen until the transition ends
                    setTouchEnabled(false);
                    mMotionHelper.expandLeavePip();
                }
            } else if (mMenuState != MENU_STATE_FULL) {
                if (!mTouchState.isWaitingForDoubleTap()) {
                    if (mPipBoundsState.isStashed()) {
                        animateToUnStashedState();
                        mPipUiEventLogger.log(
                                PipUiEventLogger.PipUiEventEnum.PICTURE_IN_PICTURE_STASH_UNSTASHED);
                        mPipBoundsState.setStashed(STASH_TYPE_NONE);
                    } else {
                        // User has stalled long enough for this not to be a drag or a double tap,
                        // just expand the menu
                        mMenuController.showMenu(MENU_STATE_FULL, mPipBoundsState.getBounds(),
                                true /* allowMenuTimeout */, willResizeMenu(),
                                shouldShowResizeHandle());
                    }
                } else {
                    // Next touch event _may_ be the second tap for the double-tap, schedule a
                    // fallback runnable to trigger the menu if no touch event occurs before the
                    // next tap
                    mTouchState.scheduleDoubleTapTimeoutCallback();
                }
            }
            mDownSavedFraction = -1f;
            return true;
        }

        private void stashEndAction() {
            if (mPipBoundsState.getBounds().left < 0
                    && mPipBoundsState.getStashedState() != STASH_TYPE_LEFT) {
                mPipUiEventLogger.log(
                        PipUiEventLogger.PipUiEventEnum.PICTURE_IN_PICTURE_STASH_LEFT);
                mPipBoundsState.setStashed(STASH_TYPE_LEFT);
            } else if (mPipBoundsState.getBounds().left >= 0
                    && mPipBoundsState.getStashedState() != STASH_TYPE_RIGHT) {
                mPipUiEventLogger.log(
                        PipUiEventLogger.PipUiEventEnum.PICTURE_IN_PICTURE_STASH_RIGHT);
                mPipBoundsState.setStashed(STASH_TYPE_RIGHT);
            }
        }

        private void flingEndAction() {
            if (mShouldHideMenuAfterFling) {
                // If the menu is not visible, then we can still be showing the activity for the
                // dismiss overlay, so just finish it after the animation completes
                mMenuController.hideMenu();
            }
        }

        private boolean shouldStash(PointF vel, Rect motionBounds) {
            // If user flings the PIP window above the minimum velocity, stash PIP.
            // Only allow stashing to the edge if PIP wasn't previously stashed on the opposite
            // edge.
            final boolean stashFromFlingToEdge = ((vel.x < -mStashVelocityThreshold
                    && mPipBoundsState.getStashedState() != STASH_TYPE_RIGHT)
                    || (vel.x > mStashVelocityThreshold
                    && mPipBoundsState.getStashedState() != STASH_TYPE_LEFT));

            // If User releases the PIP window while it's out of the display bounds, put
            // PIP into stashed mode.
            final int offset = motionBounds.width() / 2;
            final boolean stashFromDroppingOnEdge =
                    (motionBounds.right > mPipBoundsState.getDisplayBounds().right + offset
                            || motionBounds.left
                            < mPipBoundsState.getDisplayBounds().left - offset);

            return stashFromFlingToEdge || stashFromDroppingOnEdge;
        }
    }

    /**
     * Updates the current movement bounds based on whether the menu is currently visible and
     * resized.
     */
    private void updateMovementBounds() {
        mPipBoundsAlgorithm.getMovementBounds(mPipBoundsState.getBounds(),
                mInsetBounds, mPipBoundsState.getMovementBounds(), mIsImeShowing ? mImeHeight : 0);
        mMotionHelper.onMovementBoundsChanged();

        boolean isMenuExpanded = mMenuState == MENU_STATE_FULL;
        mPipBoundsState.setMinEdgeSize(
                isMenuExpanded && willResizeMenu() ? mExpandedShortestEdgeSize
                        : mPipBoundsAlgorithm.getDefaultMinSize());
    }

    private Rect getMovementBounds(Rect curBounds) {
        Rect movementBounds = new Rect();
        mPipBoundsAlgorithm.getMovementBounds(curBounds, mInsetBounds,
                movementBounds, mIsImeShowing ? mImeHeight : 0);
        return movementBounds;
    }

    /**
     * @return {@code true} if the menu should be resized on tap because app explicitly specifies
     * PiP window size that is too small to hold all the actions.
     */
    private boolean willResizeMenu() {
        if (!mEnableResize) {
            return false;
        }
        final Size estimatedMinMenuSize = mMenuController.getEstimatedMinMenuSize();
        if (estimatedMinMenuSize == null) {
            Log.wtf(TAG, "Failed to get estimated menu size");
            return false;
        }
        final Rect currentBounds = mPipBoundsState.getBounds();
        return currentBounds.width() < estimatedMinMenuSize.getWidth()
                || currentBounds.height() < estimatedMinMenuSize.getHeight();
    }

    /**
     * Returns the PIP bounds if we're not in the middle of a motion operation, or the current,
     * temporary motion bounds otherwise.
     */
    Rect getPossiblyMotionBounds() {
        return mPipBoundsState.getMotionBoundsState().isInMotion()
                ? mPipBoundsState.getMotionBoundsState().getBoundsInMotion()
                : mPipBoundsState.getBounds();
    }

    void setOhmOffset(int offset) {
        mPipResizeGestureHandler.setOhmOffset(offset);
    }

    public void dump(PrintWriter pw, String prefix) {
        final String innerPrefix = prefix + "  ";
        pw.println(prefix + TAG);
        pw.println(innerPrefix + "mMenuState=" + mMenuState);
        pw.println(innerPrefix + "mIsImeShowing=" + mIsImeShowing);
        pw.println(innerPrefix + "mImeHeight=" + mImeHeight);
        pw.println(innerPrefix + "mIsShelfShowing=" + mIsShelfShowing);
        pw.println(innerPrefix + "mShelfHeight=" + mShelfHeight);
        pw.println(innerPrefix + "mSavedSnapFraction=" + mSavedSnapFraction);
        pw.println(innerPrefix + "mMovementBoundsExtraOffsets=" + mMovementBoundsExtraOffsets);
        mPipBoundsAlgorithm.dump(pw, innerPrefix);
        mTouchState.dump(pw, innerPrefix);
        if (mPipResizeGestureHandler != null) {
            mPipResizeGestureHandler.dump(pw, innerPrefix);
        }
    }

}
