/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.android.quickstep;

import static android.view.Surface.ROTATION_0;
import static android.view.Surface.ROTATION_270;
import static android.view.Surface.ROTATION_90;
import static android.widget.Toast.LENGTH_SHORT;

import static com.android.launcher3.BaseActivity.INVISIBLE_BY_STATE_HANDLER;
import static com.android.launcher3.BaseActivity.STATE_HANDLER_INVISIBILITY_FLAGS;
import static com.android.launcher3.PagedView.INVALID_PAGE;
import static com.android.launcher3.anim.Interpolators.ACCEL_DEACCEL;
import static com.android.launcher3.anim.Interpolators.DEACCEL;
import static com.android.launcher3.anim.Interpolators.OVERSHOOT_1_2;
import static com.android.launcher3.config.FeatureFlags.ENABLE_QUICKSTEP_LIVE_TILE;
import static com.android.launcher3.logging.StatsLogManager.LAUNCHER_STATE_BACKGROUND;
import static com.android.launcher3.logging.StatsLogManager.LauncherEvent.IGNORE;
import static com.android.launcher3.logging.StatsLogManager.LauncherEvent.LAUNCHER_HOME_GESTURE;
import static com.android.launcher3.logging.StatsLogManager.LauncherEvent.LAUNCHER_OVERVIEW_GESTURE;
import static com.android.launcher3.logging.StatsLogManager.LauncherEvent.LAUNCHER_QUICKSWITCH_LEFT;
import static com.android.launcher3.logging.StatsLogManager.LauncherEvent.LAUNCHER_QUICKSWITCH_RIGHT;
import static com.android.launcher3.util.Executors.MAIN_EXECUTOR;
import static com.android.launcher3.util.Executors.UI_HELPER_EXECUTOR;
import static com.android.launcher3.util.SystemUiController.UI_STATE_FULLSCREEN_TASK;
import static com.android.launcher3.util.window.RefreshRateTracker.getSingleFrameMs;
import static com.android.quickstep.GestureState.GestureEndTarget.HOME;
import static com.android.quickstep.GestureState.GestureEndTarget.LAST_TASK;
import static com.android.quickstep.GestureState.GestureEndTarget.NEW_TASK;
import static com.android.quickstep.GestureState.GestureEndTarget.RECENTS;
import static com.android.quickstep.GestureState.STATE_END_TARGET_ANIMATION_FINISHED;
import static com.android.quickstep.GestureState.STATE_END_TARGET_SET;
import static com.android.quickstep.GestureState.STATE_RECENTS_ANIMATION_CANCELED;
import static com.android.quickstep.GestureState.STATE_RECENTS_SCROLLING_FINISHED;
import static com.android.quickstep.MultiStateCallback.DEBUG_STATES;
import static com.android.quickstep.util.VibratorWrapper.OVERVIEW_HAPTIC;
import static com.android.quickstep.views.RecentsView.UPDATE_SYSUI_FLAGS_THRESHOLD;
import static com.android.systemui.shared.system.ActivityManagerWrapper.CLOSE_SYSTEM_WINDOWS_REASON_RECENTS;
import static com.android.systemui.shared.system.RemoteAnimationTargetCompat.ACTIVITY_TYPE_HOME;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ValueAnimator;
import android.annotation.TargetApi;
import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Matrix;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnApplyWindowInsetsListener;
import android.view.ViewTreeObserver.OnDrawListener;
import android.view.ViewTreeObserver.OnScrollChangedListener;
import android.view.WindowInsets;
import android.view.animation.Interpolator;
import android.widget.Toast;
import android.window.PictureInPictureSurfaceTransaction;

import androidx.annotation.Nullable;
import androidx.annotation.UiThread;

import com.android.launcher3.AbstractFloatingView;
import com.android.launcher3.DeviceProfile;
import com.android.launcher3.R;
import com.android.launcher3.Utilities;
import com.android.launcher3.anim.AnimationSuccessListener;
import com.android.launcher3.anim.AnimatorPlaybackController;
import com.android.launcher3.logging.StatsLogManager;
import com.android.launcher3.logging.StatsLogManager.StatsLogger;
import com.android.launcher3.statemanager.BaseState;
import com.android.launcher3.statemanager.StatefulActivity;
import com.android.launcher3.tracing.InputConsumerProto;
import com.android.launcher3.tracing.SwipeHandlerProto;
import com.android.launcher3.util.ActivityLifecycleCallbacksAdapter;
import com.android.launcher3.util.TraceHelper;
import com.android.launcher3.util.WindowBounds;
import com.android.quickstep.BaseActivityInterface.AnimationFactory;
import com.android.quickstep.GestureState.GestureEndTarget;
import com.android.quickstep.RemoteTargetGluer.RemoteTargetHandle;
import com.android.quickstep.util.ActiveGestureLog;
import com.android.quickstep.util.ActivityInitListener;
import com.android.quickstep.util.AnimatorControllerWithResistance;
import com.android.quickstep.util.InputConsumerProxy;
import com.android.quickstep.util.InputProxyHandlerFactory;
import com.android.quickstep.util.MotionPauseDetector;
import com.android.quickstep.util.ProtoTracer;
import com.android.quickstep.util.RecentsOrientedState;
import com.android.quickstep.util.RectFSpringAnim;
import com.android.quickstep.util.StaggeredWorkspaceAnim;
import com.android.quickstep.util.SurfaceTransactionApplier;
import com.android.quickstep.util.SwipePipToHomeAnimator;
import com.android.quickstep.util.TaskViewSimulator;
import com.android.quickstep.util.VibratorWrapper;
import com.android.quickstep.views.RecentsView;
import com.android.quickstep.views.TaskView;
import com.android.systemui.shared.recents.model.Task;
import com.android.systemui.shared.recents.model.ThumbnailData;
import com.android.systemui.shared.system.ActivityManagerWrapper;
import com.android.systemui.shared.system.InputConsumerController;
import com.android.systemui.shared.system.InteractionJankMonitorWrapper;
import com.android.systemui.shared.system.LatencyTrackerCompat;
import com.android.systemui.shared.system.RemoteAnimationTargetCompat;
import com.android.systemui.shared.system.TaskStackChangeListener;
import com.android.systemui.shared.system.TaskStackChangeListeners;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.function.Consumer;

/**
 * Handles the navigation gestures when Launcher is the default home activity.
 */
@TargetApi(Build.VERSION_CODES.R)
public abstract class AbsSwipeUpHandler<T extends StatefulActivity<S>,
        Q extends RecentsView, S extends BaseState<S>>
        extends SwipeUpAnimationLogic implements OnApplyWindowInsetsListener,
        RecentsAnimationCallbacks.RecentsAnimationListener {
    private static final String TAG = "AbsSwipeUpHandler";

    private static final String[] STATE_NAMES = DEBUG_STATES ? new String[18] : null;

    protected final BaseActivityInterface<S, T> mActivityInterface;
    protected final InputConsumerProxy mInputConsumerProxy;
    protected final ActivityInitListener mActivityInitListener;
    // Callbacks to be made once the recents animation starts
    private final ArrayList<Runnable> mRecentsAnimationStartCallbacks = new ArrayList<>();
    private final OnScrollChangedListener mOnRecentsScrollListener = this::onRecentsViewScroll;

    // Null if the recents animation hasn't started yet or has been canceled or finished.
    protected @Nullable RecentsAnimationController mRecentsAnimationController;
    protected @Nullable RecentsAnimationController mDeferredCleanupRecentsAnimationController;
    protected RecentsAnimationTargets mRecentsAnimationTargets;
    protected T mActivity;
    protected Q mRecentsView;
    protected Runnable mGestureEndCallback;
    protected MultiStateCallback mStateCallback;
    protected boolean mCanceled;
    private boolean mRecentsViewScrollLinked = false;
    private final ActivityLifecycleCallbacksAdapter mLifecycleCallbacks =
            new ActivityLifecycleCallbacksAdapter() {
                @Override
                public void onActivityDestroyed(Activity activity) {
                    if (mActivity != activity) {
                        return;
                    }
                    mRecentsView = null;
                    mActivity = null;
                }
            };

    private static int getFlagForIndex(int index, String name) {
        if (DEBUG_STATES) {
            STATE_NAMES[index] = name;
        }
        return 1 << index;
    }

    // Launcher UI related states
    protected static final int STATE_LAUNCHER_PRESENT =
            getFlagForIndex(0, "STATE_LAUNCHER_PRESENT");
    protected static final int STATE_LAUNCHER_STARTED =
            getFlagForIndex(1, "STATE_LAUNCHER_STARTED");
    protected static final int STATE_LAUNCHER_DRAWN =
            getFlagForIndex(2, "STATE_LAUNCHER_DRAWN");
    // Called when the Launcher has connected to the touch interaction service (and the taskbar
    // ui controller is initialized)
    protected static final int STATE_LAUNCHER_BIND_TO_SERVICE =
            getFlagForIndex(3, "STATE_LAUNCHER_BIND_TO_SERVICE");

    // Internal initialization states
    private static final int STATE_APP_CONTROLLER_RECEIVED =
            getFlagForIndex(4, "STATE_APP_CONTROLLER_RECEIVED");

    // Interaction finish states
    private static final int STATE_SCALED_CONTROLLER_HOME =
            getFlagForIndex(5, "STATE_SCALED_CONTROLLER_HOME");
    private static final int STATE_SCALED_CONTROLLER_RECENTS =
            getFlagForIndex(6, "STATE_SCALED_CONTROLLER_RECENTS");

    protected static final int STATE_HANDLER_INVALIDATED =
            getFlagForIndex(7, "STATE_HANDLER_INVALIDATED");
    private static final int STATE_GESTURE_STARTED =
            getFlagForIndex(8, "STATE_GESTURE_STARTED");
    private static final int STATE_GESTURE_CANCELLED =
            getFlagForIndex(9, "STATE_GESTURE_CANCELLED");
    private static final int STATE_GESTURE_COMPLETED =
            getFlagForIndex(10, "STATE_GESTURE_COMPLETED");

    private static final int STATE_CAPTURE_SCREENSHOT =
            getFlagForIndex(11, "STATE_CAPTURE_SCREENSHOT");
    protected static final int STATE_SCREENSHOT_CAPTURED =
            getFlagForIndex(12, "STATE_SCREENSHOT_CAPTURED");
    private static final int STATE_SCREENSHOT_VIEW_SHOWN =
            getFlagForIndex(13, "STATE_SCREENSHOT_VIEW_SHOWN");

    private static final int STATE_RESUME_LAST_TASK =
            getFlagForIndex(14, "STATE_RESUME_LAST_TASK");
    private static final int STATE_START_NEW_TASK =
            getFlagForIndex(15, "STATE_START_NEW_TASK");
    private static final int STATE_CURRENT_TASK_FINISHED =
            getFlagForIndex(16, "STATE_CURRENT_TASK_FINISHED");
    private static final int STATE_FINISH_WITH_NO_END =
            getFlagForIndex(17, "STATE_FINISH_WITH_NO_END");

    private static final int LAUNCHER_UI_STATES =
            STATE_LAUNCHER_PRESENT | STATE_LAUNCHER_DRAWN | STATE_LAUNCHER_STARTED |
                    STATE_LAUNCHER_BIND_TO_SERVICE;

    public static final long MAX_SWIPE_DURATION = 350;
    public static final long HOME_DURATION = StaggeredWorkspaceAnim.DURATION_MS;

    public static final float MIN_PROGRESS_FOR_OVERVIEW = 0.7f;
    private static final float SWIPE_DURATION_MULTIPLIER =
            Math.min(1 / MIN_PROGRESS_FOR_OVERVIEW, 1 / (1 - MIN_PROGRESS_FOR_OVERVIEW));
    private static final String SCREENSHOT_CAPTURED_EVT = "ScreenshotCaptured";

    public static final long RECENTS_ATTACH_DURATION = 300;

    private static final float MAX_QUICK_SWITCH_RECENTS_SCALE_PROGRESS = 0.07f;

    /**
     * Used as the page index for logging when we return to the last task at the end of the gesture.
     */
    private static final int LOG_NO_OP_PAGE_INDEX = -1;

    protected final TaskAnimationManager mTaskAnimationManager;

    // Either RectFSpringAnim (if animating home) or ObjectAnimator (from mCurrentShift) otherwise
    private RunningWindowAnim[] mRunningWindowAnim;
    // Possible second animation running at the same time as mRunningWindowAnim
    private Animator mParallelRunningAnim;
    // Current running divider animation
    private ValueAnimator mDividerAnimator;
    private boolean mIsMotionPaused;
    private boolean mHasMotionEverBeenPaused;

    private boolean mContinuingLastGesture;

    private ThumbnailData mTaskSnapshot;

    // Used to control launcher components throughout the swipe gesture.
    private AnimatorControllerWithResistance mLauncherTransitionController;
    private boolean mHasEndedLauncherTransition;

    private AnimationFactory mAnimationFactory = (t) -> { };

    private boolean mWasLauncherAlreadyVisible;

    private boolean mPassedOverviewThreshold;
    private boolean mGestureStarted;
    private boolean mLogDirectionUpOrLeft = true;
    private PointF mDownPos;
    private boolean mIsLikelyToStartNewTask;

    private final long mTouchTimeMs;
    private long mLauncherFrameDrawnTime;

    private final Runnable mOnDeferredActivityLaunch = this::onDeferredActivityLaunch;

    private SwipePipToHomeAnimator mSwipePipToHomeAnimator;
    protected boolean mIsSwipingPipToHome;
    // TODO(b/195473090) no split PIP for now, remove once we have more clarity
    //  can try to have RectFSpringAnim evaluate multiple rects at once
    private final SwipePipToHomeAnimator[] mSwipePipToHomeAnimators =
            new SwipePipToHomeAnimator[2];

    // Interpolate RecentsView scale from start of quick switch scroll until this scroll threshold
    private final float mQuickSwitchScaleScrollThreshold;

    public AbsSwipeUpHandler(Context context, RecentsAnimationDeviceState deviceState,
            TaskAnimationManager taskAnimationManager, GestureState gestureState,
            long touchTimeMs, boolean continuingLastGesture,
            InputConsumerController inputConsumer) {
        super(context, deviceState, gestureState);
        mActivityInterface = gestureState.getActivityInterface();
        mActivityInitListener = mActivityInterface.createActivityInitListener(this::onActivityInit);
        mInputConsumerProxy =
                new InputConsumerProxy(context,
                        () -> mRecentsView.getPagedViewOrientedState().getRecentsActivityRotation(),
                        inputConsumer, () -> {
                    endRunningWindowAnim(mGestureState.getEndTarget() == HOME /* cancel */);
                    endLauncherTransitionController();
                }, new InputProxyHandlerFactory(mActivityInterface, mGestureState));
        mTaskAnimationManager = taskAnimationManager;
        mTouchTimeMs = touchTimeMs;
        mContinuingLastGesture = continuingLastGesture;
        mQuickSwitchScaleScrollThreshold = context.getResources().getDimension(
                R.dimen.quick_switch_scaling_scroll_threshold);

        initAfterSubclassConstructor();
        initStateCallbacks();
    }

    private void initStateCallbacks() {
        mStateCallback = new MultiStateCallback(STATE_NAMES);

        mStateCallback.runOnceAtState(STATE_LAUNCHER_PRESENT | STATE_GESTURE_STARTED,
                this::onLauncherPresentAndGestureStarted);

        mStateCallback.runOnceAtState(STATE_LAUNCHER_DRAWN | STATE_GESTURE_STARTED,
                this::initializeLauncherAnimationController);

        mStateCallback.runOnceAtState(STATE_LAUNCHER_PRESENT | STATE_LAUNCHER_DRAWN,
                this::launcherFrameDrawn);

        mStateCallback.runOnceAtState(STATE_LAUNCHER_PRESENT | STATE_LAUNCHER_STARTED
                        | STATE_GESTURE_CANCELLED,
                this::resetStateForAnimationCancel);

        mStateCallback.runOnceAtState(STATE_RESUME_LAST_TASK | STATE_APP_CONTROLLER_RECEIVED,
                this::resumeLastTask);
        mStateCallback.runOnceAtState(STATE_START_NEW_TASK | STATE_SCREENSHOT_CAPTURED,
                this::startNewTask);

        mStateCallback.runOnceAtState(STATE_LAUNCHER_PRESENT | STATE_APP_CONTROLLER_RECEIVED
                        | STATE_LAUNCHER_DRAWN | STATE_CAPTURE_SCREENSHOT,
                this::switchToScreenshot);

        mStateCallback.runOnceAtState(STATE_SCREENSHOT_CAPTURED | STATE_GESTURE_COMPLETED
                        | STATE_SCALED_CONTROLLER_RECENTS,
                this::finishCurrentTransitionToRecents);

        mStateCallback.runOnceAtState(STATE_SCREENSHOT_CAPTURED | STATE_GESTURE_COMPLETED
                        | STATE_SCALED_CONTROLLER_HOME,
                this::finishCurrentTransitionToHome);
        mStateCallback.runOnceAtState(STATE_SCALED_CONTROLLER_HOME | STATE_CURRENT_TASK_FINISHED,
                this::reset);

        mStateCallback.runOnceAtState(STATE_LAUNCHER_PRESENT | STATE_APP_CONTROLLER_RECEIVED
                        | STATE_LAUNCHER_DRAWN | STATE_SCALED_CONTROLLER_RECENTS
                        | STATE_CURRENT_TASK_FINISHED | STATE_GESTURE_COMPLETED
                        | STATE_GESTURE_STARTED,
                this::setupLauncherUiAfterSwipeUpToRecentsAnimation);

        mGestureState.runOnceAtState(STATE_END_TARGET_ANIMATION_FINISHED,
                this::continueComputingRecentsScrollIfNecessary);
        mGestureState.runOnceAtState(STATE_END_TARGET_ANIMATION_FINISHED
                        | STATE_RECENTS_SCROLLING_FINISHED,
                this::onSettledOnEndTarget);

        mStateCallback.runOnceAtState(STATE_HANDLER_INVALIDATED, this::invalidateHandler);
        mStateCallback.runOnceAtState(STATE_LAUNCHER_PRESENT | STATE_HANDLER_INVALIDATED,
                this::invalidateHandlerWithLauncher);
        mStateCallback.runOnceAtState(STATE_HANDLER_INVALIDATED | STATE_RESUME_LAST_TASK,
                this::resetStateForAnimationCancel);
        mStateCallback.runOnceAtState(STATE_HANDLER_INVALIDATED | STATE_FINISH_WITH_NO_END,
                this::resetStateForAnimationCancel);

        if (!ENABLE_QUICKSTEP_LIVE_TILE.get()) {
            mStateCallback.addChangeListener(STATE_APP_CONTROLLER_RECEIVED | STATE_LAUNCHER_PRESENT
                            | STATE_SCREENSHOT_VIEW_SHOWN | STATE_CAPTURE_SCREENSHOT,
                    (b) -> mRecentsView.setRunningTaskHidden(!b));
        }
    }

    protected boolean onActivityInit(Boolean alreadyOnHome) {
        if (mStateCallback.hasStates(STATE_HANDLER_INVALIDATED)) {
            return false;
        }

        T createdActivity = mActivityInterface.getCreatedActivity();
        if (createdActivity != null) {
            initTransitionEndpoints(createdActivity.getDeviceProfile());
        }
        final T activity = mActivityInterface.getCreatedActivity();
        if (mActivity == activity) {
            return true;
        }

        if (mActivity != null) {
            if (mStateCallback.hasStates(STATE_GESTURE_COMPLETED)) {
                // If the activity has restarted between setting the page scroll settling callback
                // and actually receiving the callback, just mark the gesture completed
                mGestureState.setState(STATE_RECENTS_SCROLLING_FINISHED);
                return true;
            }

            // The launcher may have been recreated as a result of device rotation.
            int oldState = mStateCallback.getState() & ~LAUNCHER_UI_STATES;
            initStateCallbacks();
            mStateCallback.setState(oldState);
        }
        mWasLauncherAlreadyVisible = alreadyOnHome;
        mActivity = activity;
        // Override the visibility of the activity until the gesture actually starts and we swipe
        // up, or until we transition home and the home animation is composed
        if (alreadyOnHome) {
            mActivity.clearForceInvisibleFlag(STATE_HANDLER_INVISIBILITY_FLAGS);
        } else {
            mActivity.addForceInvisibleFlag(STATE_HANDLER_INVISIBILITY_FLAGS);
        }

        mRecentsView = activity.getOverviewPanel();
        mRecentsView.setOnPageTransitionEndCallback(null);

        mStateCallback.setState(STATE_LAUNCHER_PRESENT);
        if (alreadyOnHome) {
            onLauncherStart();
        } else {
            activity.runOnceOnStart(this::onLauncherStart);
        }

        // Set up a entire animation lifecycle callback to notify the current recents view when
        // the animation is canceled
        mGestureState.runOnceAtState(STATE_RECENTS_ANIMATION_CANCELED, () -> {
                HashMap<Integer, ThumbnailData> snapshots =
                        mGestureState.consumeRecentsAnimationCanceledSnapshot();
                if (snapshots != null) {
                    mRecentsView.onRecentsAnimationComplete();
                    if (mRecentsAnimationController != null) {
                        mRecentsAnimationController.cleanupScreenshot();
                        mRecentsAnimationController = null;
                    }
                    if (mDeferredCleanupRecentsAnimationController != null) {
                        mDeferredCleanupRecentsAnimationController.cleanupScreenshot();
                        mDeferredCleanupRecentsAnimationController = null;
                    }
                }
            });

        setupRecentsViewUi();
        linkRecentsViewScroll();
        activity.runOnBindToTouchInteractionService(this::onLauncherBindToService);

        mActivity.registerActivityLifecycleCallbacks(mLifecycleCallbacks);
        return true;
    }

    /**
     * Return true if the window should be translated horizontally if the recents view scrolls
     */
    protected boolean moveWindowWithRecentsScroll() {
        return mGestureState.getEndTarget() != HOME;
    }

    private void onLauncherStart() {
        final T activity = mActivityInterface.getCreatedActivity();
        if (mActivity != activity) {
            return;
        }
        if (mStateCallback.hasStates(STATE_HANDLER_INVALIDATED)) {
            return;
        }
        // RecentsView never updates the display rotation until swipe-up, force update
        // RecentsOrientedState before passing to TaskViewSimulator.
        mRecentsView.updateRecentsRotation();
        runActionOnRemoteHandles(remoteTargetHandle -> remoteTargetHandle.getTaskViewSimulator()
                .setOrientationState(mRecentsView.getPagedViewOrientedState()));

        // If we've already ended the gesture and are going home, don't prepare recents UI,
        // as that will set the state as BACKGROUND_APP, overriding the animation to NORMAL.
        if (mGestureState.getEndTarget() != HOME) {
            Runnable initAnimFactory = () -> {
                mAnimationFactory = mActivityInterface.prepareRecentsUI(mDeviceState,
                        mWasLauncherAlreadyVisible, this::onAnimatorPlaybackControllerCreated);
                maybeUpdateRecentsAttachedState(false /* animate */);
                if (mGestureState.getEndTarget() != null) {
                    // Update the end target in case the gesture ended before we init.
                    mAnimationFactory.setEndTarget(mGestureState.getEndTarget());
                }
            };
            if (mWasLauncherAlreadyVisible) {
                // Launcher is visible, but might be about to stop. Thus, if we prepare recents
                // now, it might get overridden by moveToRestState() in onStop(). To avoid this,
                // wait until the next gesture (and possibly launcher) starts.
                mStateCallback.runOnceAtState(STATE_GESTURE_STARTED, initAnimFactory);
            } else {
                initAnimFactory.run();
            }
        }
        AbstractFloatingView.closeAllOpenViewsExcept(activity, mWasLauncherAlreadyVisible,
                AbstractFloatingView.TYPE_LISTENER);

        if (mWasLauncherAlreadyVisible) {
            mStateCallback.setState(STATE_LAUNCHER_DRAWN);
        } else {
            Object traceToken = TraceHelper.INSTANCE.beginSection("WTS-init");
            View dragLayer = activity.getDragLayer();
            dragLayer.getViewTreeObserver().addOnDrawListener(new OnDrawListener() {
                boolean mHandled = false;

                @Override
                public void onDraw() {
                    if (mHandled) {
                        return;
                    }
                    mHandled = true;

                    TraceHelper.INSTANCE.endSection(traceToken);
                    dragLayer.post(() ->
                            dragLayer.getViewTreeObserver().removeOnDrawListener(this));
                    if (activity != mActivity) {
                        return;
                    }

                    mStateCallback.setState(STATE_LAUNCHER_DRAWN);
                }
            });
        }

        activity.getRootView().setOnApplyWindowInsetsListener(this);
        mStateCallback.setState(STATE_LAUNCHER_STARTED);
    }

    private void onLauncherBindToService() {
        mStateCallback.setState(STATE_LAUNCHER_BIND_TO_SERVICE);
        flushOnRecentsAnimationAndLauncherBound();
    }

    private void onLauncherPresentAndGestureStarted() {
        // Re-setup the recents UI when gesture starts, as the state could have been changed during
        // that time by a previous window transition.
        setupRecentsViewUi();

        // For the duration of the gesture, in cases where an activity is launched while the
        // activity is not yet resumed, finish the animation to ensure we get resumed
        mGestureState.getActivityInterface().setOnDeferredActivityLaunchCallback(
                mOnDeferredActivityLaunch);

        mGestureState.runOnceAtState(STATE_END_TARGET_SET,
                () -> {
                    mDeviceState.getRotationTouchHelper()
                            .onEndTargetCalculated(mGestureState.getEndTarget(),
                                    mActivityInterface);
                });

        notifyGestureStartedAsync();
    }

    private void onDeferredActivityLaunch() {
        if (ENABLE_QUICKSTEP_LIVE_TILE.get()) {
            mActivityInterface.switchRunningTaskViewToScreenshot(
                    null, () -> {
                        mTaskAnimationManager.finishRunningRecentsAnimation(true /* toHome */);
                    });
        } else {
            mTaskAnimationManager.finishRunningRecentsAnimation(true /* toHome */);
        }
    }

    private void setupRecentsViewUi() {
        if (mContinuingLastGesture) {
            updateSysUiFlags(mCurrentShift.value);
            return;
        }
        notifyGestureAnimationStartToRecents();
    }

    protected void notifyGestureAnimationStartToRecents() {
        Task[] runningTasks;
        if (mIsSwipeForStagedSplit) {
            int[] splitTaskIds = TopTaskTracker.INSTANCE.get(mContext).getRunningSplitTaskIds();
            runningTasks = mGestureState.getRunningTask().getPlaceholderTasks(splitTaskIds);
        } else {
            runningTasks = mGestureState.getRunningTask().getPlaceholderTasks();
        }
        mRecentsView.onGestureAnimationStart(runningTasks, mDeviceState.getRotationTouchHelper());
    }

    private void launcherFrameDrawn() {
        mLauncherFrameDrawnTime = SystemClock.uptimeMillis();
    }

    private void initializeLauncherAnimationController() {
        buildAnimationController();

        Object traceToken = TraceHelper.INSTANCE.beginSection("logToggleRecents",
                TraceHelper.FLAG_IGNORE_BINDERS);
        LatencyTrackerCompat.logToggleRecents(
                mContext, (int) (mLauncherFrameDrawnTime - mTouchTimeMs));
        TraceHelper.INSTANCE.endSection(traceToken);

        // This method is only called when STATE_GESTURE_STARTED is set, so we can enable the
        // high-res thumbnail loader here once we are sure that we will end up in an overview state
        RecentsModel.INSTANCE.get(mContext).getThumbnailCache()
                .getHighResLoadingState().setVisible(true);
    }

    public MotionPauseDetector.OnMotionPauseListener getMotionPauseListener() {
        return new MotionPauseDetector.OnMotionPauseListener() {
            @Override
            public void onMotionPauseDetected() {
                mHasMotionEverBeenPaused = true;
                maybeUpdateRecentsAttachedState(true/* animate */, true/* moveFocusedTask */);
                performHapticFeedback();
            }

            @Override
            public void onMotionPauseChanged(boolean isPaused) {
                mIsMotionPaused = isPaused;
            }
        };
    }

    private void maybeUpdateRecentsAttachedState() {
        maybeUpdateRecentsAttachedState(true /* animate */);
    }

    private void maybeUpdateRecentsAttachedState(boolean animate) {
        maybeUpdateRecentsAttachedState(animate, false /* moveFocusedTask */);
    }

    /**
     * Determines whether to show or hide RecentsView. The window is always
     * synchronized with its corresponding TaskView in RecentsView, so if
     * RecentsView is shown, it will appear to be attached to the window.
     *
     * Note this method has no effect unless the navigation mode is NO_BUTTON.
     * @param animate whether to animate when attaching RecentsView
     * @param moveFocusedTask whether to move focused task to front when attaching
     */
    private void maybeUpdateRecentsAttachedState(boolean animate, boolean moveFocusedTask) {
        if (!mDeviceState.isFullyGesturalNavMode() || mRecentsView == null) {
            return;
        }
        RemoteAnimationTargetCompat runningTaskTarget = mRecentsAnimationTargets != null
                ? mRecentsAnimationTargets.findTask(mGestureState.getRunningTaskId())
                : null;
        final boolean recentsAttachedToAppWindow;
        if (mGestureState.getEndTarget() != null) {
            recentsAttachedToAppWindow = mGestureState.getEndTarget().recentsAttachedToAppWindow;
        } else if (mContinuingLastGesture
                && mRecentsView.getRunningTaskIndex() != mRecentsView.getNextPage()) {
            recentsAttachedToAppWindow = true;
        } else if (runningTaskTarget != null && isNotInRecents(runningTaskTarget)) {
            // The window is going away so make sure recents is always visible in this case.
            recentsAttachedToAppWindow = true;
        } else {
            recentsAttachedToAppWindow = mHasMotionEverBeenPaused || mIsLikelyToStartNewTask;
        }
        if (moveFocusedTask && !mAnimationFactory.hasRecentsEverAttachedToAppWindow()
                && recentsAttachedToAppWindow) {
            // Only move focused task if RecentsView has never been attached before, to avoid
            // TaskView jumping to new position as we move the tasks.
            mRecentsView.moveFocusedTaskToFront();
        }
        mAnimationFactory.setRecentsAttachedToAppWindow(recentsAttachedToAppWindow, animate);

        // Reapply window transform throughout the attach animation, as the animation affects how
        // much the window is bound by overscroll (vs moving freely).
        if (animate) {
            ValueAnimator reapplyWindowTransformAnim = ValueAnimator.ofFloat(0, 1);
            reapplyWindowTransformAnim.addUpdateListener(anim -> {
                if (mRunningWindowAnim == null || mRunningWindowAnim.length == 0) {
                    applyScrollAndTransform();
                }
            });
            reapplyWindowTransformAnim.setDuration(RECENTS_ATTACH_DURATION).start();
            mStateCallback.runOnceAtState(STATE_HANDLER_INVALIDATED,
                    reapplyWindowTransformAnim::cancel);
        } else {
            applyScrollAndTransform();
        }
    }

    public void setIsLikelyToStartNewTask(boolean isLikelyToStartNewTask) {
        setIsLikelyToStartNewTask(isLikelyToStartNewTask, true /* animate */);
    }

    private void setIsLikelyToStartNewTask(boolean isLikelyToStartNewTask, boolean animate) {
        if (mIsLikelyToStartNewTask != isLikelyToStartNewTask) {
            mIsLikelyToStartNewTask = isLikelyToStartNewTask;
            maybeUpdateRecentsAttachedState(animate);
        }
    }

    private void buildAnimationController() {
        if (!canCreateNewOrUpdateExistingLauncherTransitionController()) {
            return;
        }
        initTransitionEndpoints(mActivity.getDeviceProfile());
        mAnimationFactory.createActivityInterface(mTransitionDragLength);
    }

    /**
     * We don't want to change mLauncherTransitionController if mGestureState.getEndTarget() == HOME
     * (it has its own animation) or if we explicitly ended the controller already.
     * @return Whether we can create the launcher controller or update its progress.
     */
    private boolean canCreateNewOrUpdateExistingLauncherTransitionController() {
        return mGestureState.getEndTarget() != HOME && !mHasEndedLauncherTransition;
    }

    @Override
    public WindowInsets onApplyWindowInsets(View view, WindowInsets windowInsets) {
        WindowInsets result = view.onApplyWindowInsets(windowInsets);
        buildAnimationController();
        // Reapply the current shift to ensure it takes new insets into account, e.g. when long
        // pressing to stash taskbar without moving the finger.
        updateFinalShift();
        return result;
    }

    private void onAnimatorPlaybackControllerCreated(AnimatorControllerWithResistance anim) {
        boolean isFirstCreation = mLauncherTransitionController == null;
        mLauncherTransitionController = anim;
        if (isFirstCreation) {
            mStateCallback.runOnceAtState(STATE_GESTURE_STARTED, () -> {
                // Wait until the gesture is started (touch slop was passed) to start in sync with
                // mWindowTransitionController. This ensures we don't hide the taskbar background
                // when long pressing to stash it, for instance.
                mLauncherTransitionController.getNormalController().dispatchOnStart();
                updateLauncherTransitionProgress();
            });
        }
    }

    public Intent getLaunchIntent() {
        return mGestureState.getOverviewIntent();
    }

    /**
     * Called when the value of {@link #mCurrentShift} changes
     */
    @UiThread
    @Override
    public void updateFinalShift() {
        final boolean passed = mCurrentShift.value >= MIN_PROGRESS_FOR_OVERVIEW;
        if (passed != mPassedOverviewThreshold) {
            mPassedOverviewThreshold = passed;
            if (mDeviceState.isTwoButtonNavMode() && !mGestureState.isHandlingAtomicEvent()) {
                performHapticFeedback();
            }
        }

        updateSysUiFlags(mCurrentShift.value);
        applyScrollAndTransform();

        updateLauncherTransitionProgress();
    }

    private void updateLauncherTransitionProgress() {
        if (mLauncherTransitionController == null
                || !canCreateNewOrUpdateExistingLauncherTransitionController()) {
            return;
        }
        mLauncherTransitionController.setProgress(
                Math.max(mCurrentShift.value, getScaleProgressDueToScroll()), mDragLengthFactor);
    }

    /**
     * @param windowProgress 0 == app, 1 == overview
     */
    private void updateSysUiFlags(float windowProgress) {
        if (mRecentsAnimationController != null && mRecentsView != null) {
            TaskView runningTask = mRecentsView.getRunningTaskView();
            TaskView centermostTask = mRecentsView.getTaskViewNearestToCenterOfScreen();
            int centermostTaskFlags = centermostTask == null ? 0
                    : centermostTask.getThumbnail().getSysUiStatusNavFlags();
            boolean swipeUpThresholdPassed = windowProgress > 1 - UPDATE_SYSUI_FLAGS_THRESHOLD;
            boolean quickswitchThresholdPassed = centermostTask != runningTask;

            // We will handle the sysui flags based on the centermost task view.
            mRecentsAnimationController.setUseLauncherSystemBarFlags(swipeUpThresholdPassed
                    ||  (quickswitchThresholdPassed && centermostTaskFlags != 0));
            mRecentsAnimationController.setSplitScreenMinimized(mContext, swipeUpThresholdPassed);
            // Provide a hint to WM the direction that we will be settling in case the animation
            // needs to be canceled
            mRecentsAnimationController.setWillFinishToHome(swipeUpThresholdPassed);

            if (swipeUpThresholdPassed) {
                mActivity.getSystemUiController().updateUiState(UI_STATE_FULLSCREEN_TASK, 0);
            } else {
                mActivity.getSystemUiController().updateUiState(
                        UI_STATE_FULLSCREEN_TASK, centermostTaskFlags);
            }
        }
    }

    @Override
    public void onRecentsAnimationStart(RecentsAnimationController controller,
            RecentsAnimationTargets targets) {
        super.onRecentsAnimationStart(controller, targets);
        ActiveGestureLog.INSTANCE.addLog("startRecentsAnimationCallback", targets.apps.length);
        mRemoteTargetHandles = mTargetGluer.assignTargetsForSplitScreen(mContext, targets);
        mRecentsAnimationController = controller;
        mRecentsAnimationTargets = targets;

        // Only initialize the device profile, if it has not been initialized before, as in some
        // configurations targets.homeContentInsets may not be correct.
        if (mActivity == null) {
            RemoteAnimationTargetCompat primaryTaskTarget = targets.apps[0];
            // orientation state is independent of which remote target handle we use since both
            // should be pointing to the same one. Just choose index 0 for now since that works for
            // both split and non-split
            RecentsOrientedState orientationState = mRemoteTargetHandles[0].getTaskViewSimulator()
                    .getOrientationState();
            DeviceProfile dp = orientationState.getLauncherDeviceProfile();
            if (targets.minimizedHomeBounds != null && primaryTaskTarget != null) {
                Rect overviewStackBounds = mActivityInterface
                        .getOverviewWindowBounds(targets.minimizedHomeBounds, primaryTaskTarget);
                dp = dp.getMultiWindowProfile(mContext,
                        new WindowBounds(overviewStackBounds, targets.homeContentInsets));
            } else {
                // If we are not in multi-window mode, home insets should be same as system insets.
                dp = dp.copy(mContext);
            }
            dp.updateInsets(targets.homeContentInsets);
            dp.updateIsSeascape(mContext);
            initTransitionEndpoints(dp);
            orientationState.setMultiWindowMode(dp.isMultiWindowMode);
        }

        // Notify when the animation starts
        flushOnRecentsAnimationAndLauncherBound();

        // Only add the callback to enable the input consumer after we actually have the controller
        mStateCallback.runOnceAtState(STATE_APP_CONTROLLER_RECEIVED | STATE_GESTURE_STARTED,
                this::startInterceptingTouchesForGesture);
        mStateCallback.setStateOnUiThread(STATE_APP_CONTROLLER_RECEIVED);

        mPassedOverviewThreshold = false;
    }

    @Override
    public void onRecentsAnimationCanceled(HashMap<Integer, ThumbnailData> thumbnailDatas) {
        ActiveGestureLog.INSTANCE.addLog("cancelRecentsAnimation");
        mActivityInitListener.unregister();
        // Cache the recents animation controller so we can defer its cleanup to after having
        // properly cleaned up the screenshot without accidentally using it.
        mDeferredCleanupRecentsAnimationController = mRecentsAnimationController;
        mStateCallback.setStateOnUiThread(STATE_GESTURE_CANCELLED | STATE_HANDLER_INVALIDATED);

        if (mRecentsAnimationTargets != null) {
            setDividerShown(true /* shown */, false /* immediate */);
        }

        // Defer clearing the controller and the targets until after we've updated the state
        mRecentsAnimationController = null;
        mRecentsAnimationTargets = null;
        if (mRecentsView != null) {
            mRecentsView.setRecentsAnimationTargets(null, null);
        }
    }

    @UiThread
    public void onGestureStarted(boolean isLikelyToStartNewTask) {
        mActivityInterface.closeOverlay();
        TaskUtils.closeSystemWindowsAsync(CLOSE_SYSTEM_WINDOWS_REASON_RECENTS);

        if (mRecentsView != null) {
            mRecentsView.getViewTreeObserver().addOnDrawListener(new OnDrawListener() {
                boolean mHandled = false;

                @Override
                public void onDraw() {
                    if (mHandled) {
                        return;
                    }
                    mHandled = true;

                    InteractionJankMonitorWrapper.begin(mRecentsView,
                            InteractionJankMonitorWrapper.CUJ_QUICK_SWITCH, 2000 /* ms timeout */);
                    InteractionJankMonitorWrapper.begin(mRecentsView,
                            InteractionJankMonitorWrapper.CUJ_APP_CLOSE_TO_HOME);

                    mRecentsView.post(() ->
                            mRecentsView.getViewTreeObserver().removeOnDrawListener(this));
                }
            });
        }
        notifyGestureStartedAsync();
        setIsLikelyToStartNewTask(isLikelyToStartNewTask, false /* animate */);
        mStateCallback.setStateOnUiThread(STATE_GESTURE_STARTED);
        mGestureStarted = true;
        SystemUiProxy.INSTANCE.get(mContext).notifySwipeUpGestureStarted();
    }

    /**
     * Notifies the launcher that the swipe gesture has started. This can be called multiple times.
     */
    @UiThread
    private void notifyGestureStartedAsync() {
        final T curActivity = mActivity;
        if (curActivity != null) {
            // Once the gesture starts, we can no longer transition home through the button, so
            // reset the force override of the activity visibility
            mActivity.clearForceInvisibleFlag(STATE_HANDLER_INVISIBILITY_FLAGS);
        }
    }

    /**
     * Called as a result on ACTION_CANCEL to return the UI to the start state.
     */
    @UiThread
    public void onGestureCancelled() {
        updateDisplacement(0);
        mStateCallback.setStateOnUiThread(STATE_GESTURE_COMPLETED);
        handleNormalGestureEnd(0, false, new PointF(), true /* isCancel */);
    }

    /**
     * @param endVelocity The velocity in the direction of the nav bar to the middle of the screen.
     * @param velocity The x and y components of the velocity when the gesture ends.
     * @param downPos The x and y value of where the gesture started.
     */
    @UiThread
    public void onGestureEnded(float endVelocity, PointF velocity, PointF downPos) {
        float flingThreshold = mContext.getResources()
                .getDimension(R.dimen.quickstep_fling_threshold_speed);
        boolean isFling = mGestureStarted && !mIsMotionPaused
                && Math.abs(endVelocity) > flingThreshold;
        mStateCallback.setStateOnUiThread(STATE_GESTURE_COMPLETED);
        boolean isVelocityVertical = Math.abs(velocity.y) > Math.abs(velocity.x);
        if (isVelocityVertical) {
            mLogDirectionUpOrLeft = velocity.y < 0;
        } else {
            mLogDirectionUpOrLeft = velocity.x < 0;
        }
        mDownPos = downPos;
        Runnable handleNormalGestureEndCallback = () ->
                handleNormalGestureEnd(endVelocity, isFling, velocity, /* isCancel= */ false);
        if (mRecentsView != null) {
            mRecentsView.runOnPageScrollsInitialized(handleNormalGestureEndCallback);
        } else {
            handleNormalGestureEndCallback.run();
        }
    }

    private void endRunningWindowAnim(boolean cancel) {
        if (mRunningWindowAnim != null) {
            if (cancel) {
                for (RunningWindowAnim r : mRunningWindowAnim) {
                    if (r != null) {
                        r.cancel();
                    }
                }
            } else {
                for (RunningWindowAnim r : mRunningWindowAnim) {
                    if (r != null) {
                        r.end();
                    }
                }
            }
        }
        if (mParallelRunningAnim != null) {
            // Unlike the above animation, the parallel animation won't have anything to take up
            // the work if it's canceled, so just end it instead.
            mParallelRunningAnim.end();
        }
    }

    private void onSettledOnEndTarget() {
        // Fast-finish the attaching animation if it's still running.
        maybeUpdateRecentsAttachedState(false);
        final GestureEndTarget endTarget = mGestureState.getEndTarget();
        // Wait until the given View (if supplied) draws before resuming the last task.
        View postResumeLastTask = mActivityInterface.onSettledOnEndTarget(endTarget);

        if (endTarget != NEW_TASK) {
            InteractionJankMonitorWrapper.cancel(
                    InteractionJankMonitorWrapper.CUJ_QUICK_SWITCH);
        }
        if (endTarget != HOME) {
            InteractionJankMonitorWrapper.cancel(
                    InteractionJankMonitorWrapper.CUJ_APP_CLOSE_TO_HOME);
        }

        switch (endTarget) {
            case HOME:
                mStateCallback.setState(STATE_SCALED_CONTROLLER_HOME | STATE_CAPTURE_SCREENSHOT);
                // Notify swipe-to-home (recents animation) is finished
                SystemUiProxy.INSTANCE.get(mContext).notifySwipeToHomeFinished();
                break;
            case RECENTS:
                mStateCallback.setState(STATE_SCALED_CONTROLLER_RECENTS | STATE_CAPTURE_SCREENSHOT
                        | STATE_SCREENSHOT_VIEW_SHOWN);
                break;
            case NEW_TASK:
                mStateCallback.setState(STATE_START_NEW_TASK | STATE_CAPTURE_SCREENSHOT);
                break;
            case LAST_TASK:
                if (postResumeLastTask != null) {
                    ViewUtils.postFrameDrawn(postResumeLastTask,
                            () -> mStateCallback.setState(STATE_RESUME_LAST_TASK));
                } else {
                    mStateCallback.setState(STATE_RESUME_LAST_TASK);
                }
                if (mRecentsAnimationTargets != null) {
                    setDividerShown(true /* shown */, true /* immediate */);
                }
                break;
        }
        ActiveGestureLog.INSTANCE.addLog("onSettledOnEndTarget " + endTarget);
    }

    /** @return Whether this was the task we were waiting to appear, and thus handled it. */
    protected boolean handleTaskAppeared(RemoteAnimationTargetCompat[] appearedTaskTarget) {
        if (mStateCallback.hasStates(STATE_HANDLER_INVALIDATED)) {
            return false;
        }
        boolean hasStartedTaskBefore = Arrays.stream(appearedTaskTarget).anyMatch(
                targetCompat -> targetCompat.taskId == mGestureState.getLastStartedTaskId());
        if (mStateCallback.hasStates(STATE_START_NEW_TASK) && hasStartedTaskBefore) {
            reset();
            return true;
        }
        return false;
    }

    private GestureEndTarget calculateEndTarget(PointF velocity, float endVelocity,
            boolean isFlingY, boolean isCancel) {
        if (mGestureState.isHandlingAtomicEvent()) {
            // Button mode, this is only used to go to recents
            return RECENTS;
        }
        final GestureEndTarget endTarget;
        final boolean goingToNewTask;
        if (mRecentsView != null) {
            if (!hasTargets()) {
                // If there are no running tasks, then we can assume that this is a continuation of
                // the last gesture, but after the recents animation has finished
                goingToNewTask = true;
            } else {
                final int runningTaskIndex = mRecentsView.getRunningTaskIndex();
                final int taskToLaunch = mRecentsView.getNextPage();
                goingToNewTask = runningTaskIndex >= 0 && taskToLaunch != runningTaskIndex;
            }
        } else {
            goingToNewTask = false;
        }
        final boolean reachedOverviewThreshold = mCurrentShift.value >= MIN_PROGRESS_FOR_OVERVIEW;
        final boolean isFlingX = Math.abs(velocity.x) > mContext.getResources()
                .getDimension(R.dimen.quickstep_fling_threshold_speed);
        if (!isFlingY) {
            if (isCancel) {
                endTarget = LAST_TASK;
            } else if (mDeviceState.isFullyGesturalNavMode()) {
                if (goingToNewTask && isFlingX) {
                    // Flinging towards new task takes precedence over mIsMotionPaused (which only
                    // checks y-velocity).
                    endTarget = NEW_TASK;
                } else if (mIsMotionPaused) {
                    endTarget = RECENTS;
                } else if (goingToNewTask) {
                    endTarget = NEW_TASK;
                } else {
                    endTarget = !reachedOverviewThreshold ? LAST_TASK : HOME;
                }
            } else {
                endTarget = reachedOverviewThreshold && mGestureStarted
                        ? RECENTS
                        : goingToNewTask
                                ? NEW_TASK
                                : LAST_TASK;
            }
        } else {
            // If swiping at a diagonal, base end target on the faster velocity.
            boolean isSwipeUp = endVelocity < 0;
            boolean willGoToNewTaskOnSwipeUp =
                    goingToNewTask && Math.abs(velocity.x) > Math.abs(endVelocity);

            if (mDeviceState.isFullyGesturalNavMode() && isSwipeUp && !willGoToNewTaskOnSwipeUp) {
                endTarget = HOME;
            } else if (mDeviceState.isFullyGesturalNavMode() && isSwipeUp) {
                // If swiping at a diagonal, base end target on the faster velocity.
                endTarget = NEW_TASK;
            } else if (isSwipeUp) {
                endTarget = !reachedOverviewThreshold && willGoToNewTaskOnSwipeUp
                        ? NEW_TASK : RECENTS;
            } else {
                endTarget = goingToNewTask ? NEW_TASK : LAST_TASK;
            }
        }

        if (mDeviceState.isOverviewDisabled() && (endTarget == RECENTS || endTarget == LAST_TASK)) {
            return LAST_TASK;
        }
        return endTarget;
    }

    @UiThread
    private void handleNormalGestureEnd(float endVelocity, boolean isFling, PointF velocity,
            boolean isCancel) {
        long duration = MAX_SWIPE_DURATION;
        float currentShift = mCurrentShift.value;
        final GestureEndTarget endTarget = calculateEndTarget(velocity, endVelocity,
                isFling, isCancel);
        // Set the state, but don't notify until the animation completes
        mGestureState.setEndTarget(endTarget, false /* isAtomic */);
        mAnimationFactory.setEndTarget(endTarget);

        float endShift = endTarget.isLauncher ? 1 : 0;
        final float startShift;
        if (!isFling) {
            long expectedDuration = Math.abs(Math.round((endShift - currentShift)
                    * MAX_SWIPE_DURATION * SWIPE_DURATION_MULTIPLIER));
            duration = Math.min(MAX_SWIPE_DURATION, expectedDuration);
            startShift = currentShift;
        } else {
            startShift = Utilities.boundToRange(currentShift - velocity.y
                    * getSingleFrameMs(mContext) / mTransitionDragLength, 0, mDragLengthFactor);
            if (mTransitionDragLength > 0) {
                    float distanceToTravel = (endShift - currentShift) * mTransitionDragLength;

                    // we want the page's snap velocity to approximately match the velocity at
                    // which the user flings, so we scale the duration by a value near to the
                    // derivative of the scroll interpolator at zero, ie. 2.
                    long baseDuration = Math.round(Math.abs(distanceToTravel / velocity.y));
                    duration = Math.min(MAX_SWIPE_DURATION, 2 * baseDuration);
            }
        }
        Interpolator interpolator;
        S state = mActivityInterface.stateFromGestureEndTarget(endTarget);
        if (state.displayOverviewTasksAsGrid(mDp)) {
            interpolator = ACCEL_DEACCEL;
        } else if (endTarget == RECENTS) {
            interpolator = OVERSHOOT_1_2;
        } else {
            interpolator = DEACCEL;
        }

        if (endTarget.isLauncher) {
            mInputConsumerProxy.enable();
        }
        if (endTarget == HOME) {
            duration = HOME_DURATION;
            // Early detach the nav bar once the endTarget is determined as HOME
            if (mRecentsAnimationController != null) {
                mRecentsAnimationController.detachNavigationBarFromApp(true);
            }
        } else if (endTarget == RECENTS) {
            if (mRecentsView != null) {
                int nearestPage = mRecentsView.getDestinationPage();
                if (nearestPage == INVALID_PAGE) {
                    // Allow the snap to invalid page to catch future error cases.
                    Log.e(TAG,
                            "RecentsView destination page is invalid",
                            new IllegalStateException());
                }

                boolean isScrolling = false;
                if (mRecentsView.getNextPage() != nearestPage) {
                    // We shouldn't really scroll to the next page when swiping up to recents.
                    // Only allow settling on the next page if it's nearest to the center.
                    mRecentsView.snapToPage(nearestPage, Math.toIntExact(duration));
                    isScrolling = true;
                }
                if (mRecentsView.getScroller().getDuration() > MAX_SWIPE_DURATION) {
                    mRecentsView.snapToPage(mRecentsView.getNextPage(), (int) MAX_SWIPE_DURATION);
                    isScrolling = true;
                }
                if (!mGestureState.isHandlingAtomicEvent() || isScrolling) {
                    duration = Math.max(duration, mRecentsView.getScroller().getDuration());
                }
            }
        }

        // Let RecentsView handle the scrolling to the task, which we launch in startNewTask()
        // or resumeLastTask().
        if (mRecentsView != null) {
            mRecentsView.setOnPageTransitionEndCallback(
                    () -> mGestureState.setState(STATE_RECENTS_SCROLLING_FINISHED));
        } else {
            mGestureState.setState(STATE_RECENTS_SCROLLING_FINISHED);
        }

        animateToProgress(startShift, endShift, duration, interpolator, endTarget, velocity);
    }

    private void doLogGesture(GestureEndTarget endTarget, @Nullable TaskView targetTask) {
        if (mDp == null || !mDp.isGestureMode || mDownPos == null) {
            // We probably never received an animation controller, skip logging.
            return;
        }

        StatsLogManager.EventEnum event;
        switch (endTarget) {
            case HOME:
                event = LAUNCHER_HOME_GESTURE;
                break;
            case RECENTS:
                event = LAUNCHER_OVERVIEW_GESTURE;
                break;
            case LAST_TASK:
            case NEW_TASK:
                event = mLogDirectionUpOrLeft ? LAUNCHER_QUICKSWITCH_LEFT
                        : LAUNCHER_QUICKSWITCH_RIGHT;
                break;
            default:
                event = IGNORE;
        }
        StatsLogger logger = StatsLogManager.newInstance(mContext).logger()
                .withSrcState(LAUNCHER_STATE_BACKGROUND)
                .withDstState(endTarget.containerType);
        if (targetTask != null) {
            logger.withItemInfo(targetTask.getItemInfo());
        }

        int pageIndex = endTarget == LAST_TASK || mRecentsView == null
                ? LOG_NO_OP_PAGE_INDEX
                : mRecentsView.getNextPage();
        logger.withRank(pageIndex);
        logger.log(event);
    }

    /** Animates to the given progress, where 0 is the current app and 1 is overview. */
    @UiThread
    private void animateToProgress(float start, float end, long duration, Interpolator interpolator,
            GestureEndTarget target, PointF velocityPxPerMs) {
        runOnRecentsAnimationAndLauncherBound(() -> animateToProgressInternal(start, end, duration,
                interpolator, target, velocityPxPerMs));
    }

    protected abstract HomeAnimationFactory createHomeAnimationFactory(
            ArrayList<IBinder> launchCookies, long duration, boolean isTargetTranslucent,
            boolean appCanEnterPip, RemoteAnimationTargetCompat runningTaskTarget);

    private final TaskStackChangeListener mActivityRestartListener = new TaskStackChangeListener() {
        @Override
        public void onActivityRestartAttempt(ActivityManager.RunningTaskInfo task,
                boolean homeTaskVisible, boolean clearedTask, boolean wasVisible) {
            if (task.taskId == mGestureState.getRunningTaskId()
                    && task.configuration.windowConfiguration.getActivityType()
                    != ACTIVITY_TYPE_HOME) {
                // Since this is an edge case, just cancel and relaunch with default activity
                // options (since we don't know if there's an associated app icon to launch from)
                endRunningWindowAnim(true /* cancel */);
                TaskStackChangeListeners.getInstance().unregisterTaskStackListener(
                        mActivityRestartListener);
                ActivityManagerWrapper.getInstance().startActivityFromRecents(task.taskId, null);
            }
        }
    };

    @UiThread
    private void animateToProgressInternal(float start, float end, long duration,
            Interpolator interpolator, GestureEndTarget target, PointF velocityPxPerMs) {
        maybeUpdateRecentsAttachedState();

        // If we are transitioning to launcher, then listen for the activity to be restarted while
        // the transition is in progress
        if (mGestureState.getEndTarget().isLauncher) {
            TaskStackChangeListeners.getInstance().registerTaskStackListener(
                    mActivityRestartListener);

            mParallelRunningAnim = mActivityInterface.getParallelAnimationToLauncher(
                    mGestureState.getEndTarget(), duration,
                    mTaskAnimationManager.getCurrentCallbacks());
            if (mParallelRunningAnim != null) {
                mParallelRunningAnim.addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        mParallelRunningAnim = null;
                    }
                });
                mParallelRunningAnim.start();
            }
        }

        if (mGestureState.getEndTarget() == HOME) {
            getOrientationHandler().adjustFloatingIconStartVelocity(velocityPxPerMs);
            final RemoteAnimationTargetCompat runningTaskTarget = mRecentsAnimationTargets != null
                    ? mRecentsAnimationTargets.findTask(mGestureState.getRunningTaskId())
                    : null;
            final ArrayList<IBinder> cookies = runningTaskTarget != null
                    ? runningTaskTarget.taskInfo.launchCookies
                    : new ArrayList<>();
            boolean isTranslucent = runningTaskTarget != null && runningTaskTarget.isTranslucent;
            boolean appCanEnterPip = !mDeviceState.isPipActive()
                    && runningTaskTarget != null
                    && runningTaskTarget.allowEnterPip
                    && runningTaskTarget.taskInfo.pictureInPictureParams != null
                    && runningTaskTarget.taskInfo.pictureInPictureParams.isAutoEnterEnabled();
            HomeAnimationFactory homeAnimFactory =
                    createHomeAnimationFactory(cookies, duration, isTranslucent, appCanEnterPip,
                            runningTaskTarget);
            mIsSwipingPipToHome = !mIsSwipeForStagedSplit && appCanEnterPip;
            final RectFSpringAnim[] windowAnim;
            if (mIsSwipingPipToHome) {
                mSwipePipToHomeAnimator = createWindowAnimationToPip(
                        homeAnimFactory, runningTaskTarget, start);
                mSwipePipToHomeAnimators[0] = mSwipePipToHomeAnimator;
                windowAnim = mSwipePipToHomeAnimators;
            } else {
                mSwipePipToHomeAnimator = null;
                windowAnim = createWindowAnimationToHome(start, homeAnimFactory);

                windowAnim[0].addAnimatorListener(new AnimationSuccessListener() {
                    @Override
                    public void onAnimationSuccess(Animator animator) {
                        if (mRecentsAnimationController == null) {
                            // If the recents animation is interrupted, we still end the running
                            // animation (not canceled) so this is still called. In that case,
                            // we can skip doing any future work here for the current gesture.
                            return;
                        }
                        // Finalize the state and notify of the change
                        mGestureState.setState(STATE_END_TARGET_ANIMATION_FINISHED);
                    }
                });
            }
            mRunningWindowAnim = new RunningWindowAnim[windowAnim.length];
            for (int i = 0, windowAnimLength = windowAnim.length; i < windowAnimLength; i++) {
                RectFSpringAnim windowAnimation = windowAnim[i];
                if (windowAnimation == null) {
                    continue;
                }
                windowAnimation.start(mContext, velocityPxPerMs);
                mRunningWindowAnim[i] = RunningWindowAnim.wrap(windowAnimation);
            }
            homeAnimFactory.setSwipeVelocity(velocityPxPerMs.y);
            homeAnimFactory.playAtomicAnimation(velocityPxPerMs.y);
            mLauncherTransitionController = null;

            if (mRecentsView != null) {
                mRecentsView.onPrepareGestureEndAnimation(null, mGestureState.getEndTarget(),
                        getRemoteTaskViewSimulators());
            }
        } else {
            AnimatorSet animatorSet = new AnimatorSet();
            ValueAnimator windowAnim = mCurrentShift.animateToValue(start, end);
            windowAnim.addUpdateListener(valueAnimator -> {
                computeRecentsScrollIfInvisible();
            });
            windowAnim.addListener(new AnimationSuccessListener() {
                @Override
                public void onAnimationSuccess(Animator animator) {
                    if (mRecentsAnimationController == null) {
                        // If the recents animation is interrupted, we still end the running
                        // animation (not canceled) so this is still called. In that case, we can
                        // skip doing any future work here for the current gesture.
                        return;
                    }
                    if (mRecentsView != null) {
                        int taskToLaunch = mRecentsView.getNextPage();
                        int runningTask = getLastAppearedTaskIndex();
                        boolean hasStartedNewTask = hasStartedNewTask();
                        if (target == NEW_TASK && taskToLaunch == runningTask
                                && !hasStartedNewTask) {
                            // We are about to launch the current running task, so use LAST_TASK
                            // state instead of NEW_TASK. This could happen, for example, if our
                            // scroll is aborted after we determined the target to be NEW_TASK.
                            mGestureState.setEndTarget(LAST_TASK);
                        } else if (target == LAST_TASK && hasStartedNewTask) {
                            // We are about to re-launch the previously running task, but we can't
                            // just finish the controller like we normally would because that would
                            // instead resume the last task that appeared, and not ensure that this
                            // task is restored to the top. To address this, re-launch the task as
                            // if it were a new task.
                            mGestureState.setEndTarget(NEW_TASK);
                        }
                    }
                    mGestureState.setState(STATE_END_TARGET_ANIMATION_FINISHED);
                }
            });
            animatorSet.play(windowAnim);
            if (mRecentsView != null) {
                mRecentsView.onPrepareGestureEndAnimation(
                        animatorSet, mGestureState.getEndTarget(),
                        getRemoteTaskViewSimulators());
            }
            animatorSet.setDuration(duration).setInterpolator(interpolator);
            animatorSet.start();
            mRunningWindowAnim = new RunningWindowAnim[]{RunningWindowAnim.wrap(animatorSet)};
        }
    }

    private int calculateWindowRotation(RemoteAnimationTargetCompat runningTaskTarget,
            RecentsOrientedState orientationState) {
        if (runningTaskTarget.rotationChange != 0
                && TaskAnimationManager.ENABLE_SHELL_TRANSITIONS) {
            return Math.abs(runningTaskTarget.rotationChange) == ROTATION_90
                    ? ROTATION_270 : ROTATION_90;
        } else {
            return orientationState.getDisplayRotation();
        }
    }

    /**
     * TODO(b/195473090) handle multiple task simulators (if needed) for PIP
     */
    private SwipePipToHomeAnimator createWindowAnimationToPip(HomeAnimationFactory homeAnimFactory,
            RemoteAnimationTargetCompat runningTaskTarget, float startProgress) {
        // Directly animate the app to PiP (picture-in-picture) mode
        final ActivityManager.RunningTaskInfo taskInfo = runningTaskTarget.taskInfo;
        final RecentsOrientedState orientationState = mRemoteTargetHandles[0].getTaskViewSimulator()
                .getOrientationState();
        final int windowRotation = calculateWindowRotation(runningTaskTarget, orientationState);
        final int homeRotation = orientationState.getRecentsActivityRotation();

        final Matrix[] homeToWindowPositionMaps = new Matrix[mRemoteTargetHandles.length];
        final RectF startRect = updateProgressForStartRect(homeToWindowPositionMaps,
                startProgress)[0];
        final Matrix homeToWindowPositionMap = homeToWindowPositionMaps[0];
        // Move the startRect to Launcher space as floatingIconView runs in Launcher
        final Matrix windowToHomePositionMap = new Matrix();
        homeToWindowPositionMap.invert(windowToHomePositionMap);
        windowToHomePositionMap.mapRect(startRect);

        final Rect destinationBounds = SystemUiProxy.INSTANCE.get(mContext)
                .startSwipePipToHome(taskInfo.topActivity,
                        taskInfo.topActivityInfo,
                        runningTaskTarget.taskInfo.pictureInPictureParams,
                        homeRotation,
                        mDp.hotseatBarSizePx);
        final SwipePipToHomeAnimator.Builder builder = new SwipePipToHomeAnimator.Builder()
                .setContext(mContext)
                .setTaskId(runningTaskTarget.taskId)
                .setComponentName(taskInfo.topActivity)
                .setLeash(runningTaskTarget.leash)
                .setSourceRectHint(
                        runningTaskTarget.taskInfo.pictureInPictureParams.getSourceRectHint())
                .setAppBounds(taskInfo.configuration.windowConfiguration.getBounds())
                .setHomeToWindowPositionMap(homeToWindowPositionMap)
                .setStartBounds(startRect)
                .setDestinationBounds(destinationBounds)
                .setCornerRadius(mRecentsView.getPipCornerRadius())
                .setShadowRadius(mRecentsView.getPipShadowRadius())
                .setAttachedView(mRecentsView);
        // We would assume home and app window always in the same rotation While homeRotation
        // is not ROTATION_0 (which implies the rotation is turned on in launcher settings).
        if (homeRotation == ROTATION_0
                && (windowRotation == ROTATION_90 || windowRotation == ROTATION_270)) {
            builder.setFromRotation(mRemoteTargetHandles[0].getTaskViewSimulator(), windowRotation,
                    taskInfo.displayCutoutInsets);
        }
        final SwipePipToHomeAnimator swipePipToHomeAnimator = builder.build();
        AnimatorPlaybackController activityAnimationToHome =
                homeAnimFactory.createActivityAnimationToHome();
        swipePipToHomeAnimator.addAnimatorListener(new AnimatorListenerAdapter() {
            private boolean mHasAnimationEnded;
            @Override
            public void onAnimationStart(Animator animation) {
                if (mHasAnimationEnded) return;
                // Ensure Launcher ends in NORMAL state
                activityAnimationToHome.dispatchOnStart();
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (mHasAnimationEnded) return;
                mHasAnimationEnded = true;
                activityAnimationToHome.getAnimationPlayer().end();
                if (mRecentsAnimationController == null) {
                    // If the recents animation is interrupted, we still end the running
                    // animation (not canceled) so this is still called. In that case, we can
                    // skip doing any future work here for the current gesture.
                    return;
                }
                // Finalize the state and notify of the change
                mGestureState.setState(STATE_END_TARGET_ANIMATION_FINISHED);
            }
        });
        setupWindowAnimation(new RectFSpringAnim[]{swipePipToHomeAnimator});
        return swipePipToHomeAnimator;
    }

    private void startInterceptingTouchesForGesture() {
        if (mRecentsAnimationController == null) {
            return;
        }

        mRecentsAnimationController.enableInputConsumer();

        // Start hiding the divider
        setDividerShown(false /* shown */, true /* immediate */);
    }

    private void computeRecentsScrollIfInvisible() {
        if (mRecentsView != null && mRecentsView.getVisibility() != View.VISIBLE) {
            // Views typically don't compute scroll when invisible as an optimization,
            // but in our case we need to since the window offset depends on the scroll.
            mRecentsView.computeScroll();
        }
    }

    private void continueComputingRecentsScrollIfNecessary() {
        if (!mGestureState.hasState(STATE_RECENTS_SCROLLING_FINISHED)
                && !mStateCallback.hasStates(STATE_HANDLER_INVALIDATED)
                && !mCanceled) {
            computeRecentsScrollIfInvisible();
            mRecentsView.postOnAnimation(this::continueComputingRecentsScrollIfNecessary);
        }
    }

    /**
     * Creates an animation that transforms the current app window into the home app.
     * @param startProgress The progress of {@link #mCurrentShift} to start the window from.
     * @param homeAnimationFactory The home animation factory.
     */
    @Override
    protected RectFSpringAnim[] createWindowAnimationToHome(float startProgress,
            HomeAnimationFactory homeAnimationFactory) {
        RectFSpringAnim[] anim =
                super.createWindowAnimationToHome(startProgress, homeAnimationFactory);
        setupWindowAnimation(anim);
        return anim;
    }

    private void setupWindowAnimation(RectFSpringAnim[] anims) {
        anims[0].addOnUpdateListener((r, p) -> {
            updateSysUiFlags(Math.max(p, mCurrentShift.value));
        });
        anims[0].addAnimatorListener(new AnimationSuccessListener() {
            @Override
            public void onAnimationSuccess(Animator animator) {
                if (mRecentsView != null) {
                    mRecentsView.post(mRecentsView::resetTaskVisuals);
                }
                // Make sure recents is in its final state
                maybeUpdateRecentsAttachedState(false);
                mActivityInterface.onSwipeUpToHomeComplete(mDeviceState);
            }
        });
        if (mRecentsAnimationTargets != null) {
            mRecentsAnimationTargets.addReleaseCheck(anims[0]);
        }
    }

    public void onConsumerAboutToBeSwitched() {
        if (mActivity != null) {
            // In the off chance that the gesture ends before Launcher is started, we should clear
            // the callback here so that it doesn't update with the wrong state
            mActivity.clearRunOnceOnStartCallback();
            resetLauncherListeners();
        }
        if (mGestureState.isRecentsAnimationRunning() && mGestureState.getEndTarget() != null
                && !mGestureState.getEndTarget().isLauncher) {
            // Continued quick switch.
            cancelCurrentAnimation();
        } else {
            mStateCallback.setStateOnUiThread(STATE_FINISH_WITH_NO_END);
            reset();
        }
    }

    public boolean isCanceled() {
        return mCanceled;
    }

    @UiThread
    private void resumeLastTask() {
        if (mRecentsAnimationController != null) {
            mRecentsAnimationController.finish(false /* toRecents */, null);
            ActiveGestureLog.INSTANCE.addLog("finishRecentsAnimation", false);
        }
        doLogGesture(LAST_TASK, null);
        reset();
    }

    @UiThread
    private void startNewTask() {
        TaskView taskToLaunch = mRecentsView == null ? null : mRecentsView.getNextPageTaskView();
        startNewTask(success -> {
            if (!success) {
                reset();
                // We couldn't launch the task, so take user to overview so they can
                // decide what to do instead of staying in this broken state.
                endLauncherTransitionController();
                updateSysUiFlags(1 /* windowProgress == overview */);
            }
            doLogGesture(NEW_TASK, taskToLaunch);
        });
    }

    /**
     * Called when we successfully startNewTask() on the task that was previously running. Normally
     * we call resumeLastTask() when returning to the previously running task, but this handles a
     * specific edge case: if we switch from A to B, and back to A before B appears, we need to
     * start A again to ensure it stays on top.
     */
    @androidx.annotation.CallSuper
    protected void onRestartPreviouslyAppearedTask() {
        // Finish the controller here, since we won't get onTaskAppeared() for a task that already
        // appeared.
        if (mRecentsAnimationController != null) {
            mRecentsAnimationController.finish(false, null);
        }
        reset();
    }

    private void reset() {
        mStateCallback.setStateOnUiThread(STATE_HANDLER_INVALIDATED);
        if (mActivity != null) {
            mActivity.unregisterActivityLifecycleCallbacks(mLifecycleCallbacks);
        }
    }

    /**
     * Cancels any running animation so that the active target can be overriden by a new swipe
     * handler (in case of quick switch).
     */
    private void cancelCurrentAnimation() {
        mCanceled = true;
        mCurrentShift.cancelAnimation();

        // Cleanup when switching handlers
        mInputConsumerProxy.unregisterCallback();
        mActivityInitListener.unregister();
        ActivityManagerWrapper.getInstance().unregisterTaskStackListener(mActivityRestartListener);
        mTaskSnapshot = null;
    }

    private void invalidateHandler() {
        if (!ENABLE_QUICKSTEP_LIVE_TILE.get() || !mActivityInterface.isInLiveTileMode()
                || mGestureState.getEndTarget() != RECENTS) {
            mInputConsumerProxy.destroy();
            mTaskAnimationManager.setLiveTileCleanUpHandler(null);
        }
        mInputConsumerProxy.unregisterCallback();
        endRunningWindowAnim(false /* cancel */);

        if (mGestureEndCallback != null) {
            mGestureEndCallback.run();
        }

        mActivityInitListener.unregister();
        TaskStackChangeListeners.getInstance().unregisterTaskStackListener(
                mActivityRestartListener);
        mTaskSnapshot = null;
    }

    private void invalidateHandlerWithLauncher() {
        endLauncherTransitionController();

        mRecentsView.onGestureAnimationEnd();
        resetLauncherListeners();
    }

    private void endLauncherTransitionController() {
        mHasEndedLauncherTransition = true;

        if (mLauncherTransitionController != null) {
            // End the animation, but stay at the same visual progress.
            mLauncherTransitionController.getNormalController().dispatchSetInterpolator(
                    t -> Utilities.boundToRange(mCurrentShift.value, 0, 1));
            mLauncherTransitionController.getNormalController().getAnimationPlayer().end();
            mLauncherTransitionController = null;
        }

        if (mRecentsView != null) {
            mRecentsView.abortScrollerAnimation();
        }
    }

    /**
     * Unlike invalidateHandlerWithLauncher, this is called even when switching consumers, e.g. on
     * continued quick switch gesture, which cancels the previous handler but doesn't invalidate it.
     */
    private void resetLauncherListeners() {
        // Reset the callback for deferred activity launches
        if (!ENABLE_QUICKSTEP_LIVE_TILE.get()) {
            mActivityInterface.setOnDeferredActivityLaunchCallback(null);
        }
        mActivity.getRootView().setOnApplyWindowInsetsListener(null);

        mRecentsView.removeOnScrollChangedListener(mOnRecentsScrollListener);
    }

    private void resetStateForAnimationCancel() {
        boolean wasVisible = mWasLauncherAlreadyVisible || mGestureStarted;
        mActivityInterface.onTransitionCancelled(wasVisible, mGestureState.getEndTarget());

        if (mRecentsAnimationTargets != null) {
            setDividerShown(true /* shown */, true /* immediate */);
        }

        // Leave the pending invisible flag, as it may be used by wallpaper open animation.
        if (mActivity != null) {
            mActivity.clearForceInvisibleFlag(INVISIBLE_BY_STATE_HANDLER);
        }
    }

    protected void switchToScreenshot() {
        if (!hasTargets()) {
            // If there are no targets, then we don't need to capture anything
            mStateCallback.setStateOnUiThread(STATE_SCREENSHOT_CAPTURED);
        } else {
            final int runningTaskId = mGestureState.getRunningTaskId();
            final boolean refreshView = !ENABLE_QUICKSTEP_LIVE_TILE.get() /* refreshView */;
            boolean finishTransitionPosted = false;
            if (mRecentsAnimationController != null) {
                // Update the screenshot of the task
                if (mTaskSnapshot == null) {
                    UI_HELPER_EXECUTOR.execute(() -> {
                        if (mRecentsAnimationController == null) return;
                        final ThumbnailData taskSnapshot =
                                mRecentsAnimationController.screenshotTask(runningTaskId);
                        MAIN_EXECUTOR.execute(() -> {
                            mTaskSnapshot = taskSnapshot;
                            if (!updateThumbnail(runningTaskId, refreshView)) {
                                setScreenshotCapturedState();
                            }
                        });
                    });
                    return;
                }
                finishTransitionPosted = updateThumbnail(runningTaskId, refreshView);
            }
            if (!finishTransitionPosted) {
                setScreenshotCapturedState();
            }
        }
    }

    // Returns whether finish transition was posted.
    private boolean updateThumbnail(int runningTaskId, boolean refreshView) {
        boolean finishTransitionPosted = false;
        final TaskView taskView;
        if (mGestureState.getEndTarget() == HOME || mGestureState.getEndTarget() == NEW_TASK) {
            // Capture the screenshot before finishing the transition to home or quickswitching to
            // ensure it's taken in the correct orientation, but no need to update the thumbnail.
            taskView = null;
        } else {
            taskView = mRecentsView.updateThumbnail(runningTaskId, mTaskSnapshot, refreshView);
        }
        if (taskView != null && refreshView && !mCanceled) {
            // Defer finishing the animation until the next launcher frame with the
            // new thumbnail
            finishTransitionPosted = ViewUtils.postFrameDrawn(taskView,
                    () -> mStateCallback.setStateOnUiThread(STATE_SCREENSHOT_CAPTURED),
                    this::isCanceled);
        }
        return finishTransitionPosted;
    }

    private void setScreenshotCapturedState() {
        // If we haven't posted a draw callback, set the state immediately.
        Object traceToken = TraceHelper.INSTANCE.beginSection(SCREENSHOT_CAPTURED_EVT,
                TraceHelper.FLAG_CHECK_FOR_RACE_CONDITIONS);
        mStateCallback.setStateOnUiThread(STATE_SCREENSHOT_CAPTURED);
        TraceHelper.INSTANCE.endSection(traceToken);
    }

    private void finishCurrentTransitionToRecents() {
        if (ENABLE_QUICKSTEP_LIVE_TILE.get()) {
            mStateCallback.setStateOnUiThread(STATE_CURRENT_TASK_FINISHED);
            if (mRecentsAnimationController != null) {
                mRecentsAnimationController.detachNavigationBarFromApp(true);
            }
        } else if (!hasTargets() || mRecentsAnimationController == null) {
            // If there are no targets or the animation not started, then there is nothing to finish
            mStateCallback.setStateOnUiThread(STATE_CURRENT_TASK_FINISHED);
        } else {
            mRecentsAnimationController.finish(true /* toRecents */,
                    () -> mStateCallback.setStateOnUiThread(STATE_CURRENT_TASK_FINISHED));
        }
        ActiveGestureLog.INSTANCE.addLog("finishRecentsAnimation", true);
    }

    private void finishCurrentTransitionToHome() {
        if (!hasTargets() || mRecentsAnimationController == null) {
            // If there are no targets or the animation not started, then there is nothing to finish
            mStateCallback.setStateOnUiThread(STATE_CURRENT_TASK_FINISHED);
        } else {
            maybeFinishSwipeToHome();
            finishRecentsControllerToHome(
                    () -> mStateCallback.setStateOnUiThread(STATE_CURRENT_TASK_FINISHED));
        }
        ActiveGestureLog.INSTANCE.addLog("finishRecentsAnimation", true);
        doLogGesture(HOME, mRecentsView == null ? null : mRecentsView.getCurrentPageTaskView());
    }

    /**
     * Notifies SysUI that transition is finished if applicable and also pass leash transactions
     * from Launcher to WM.
     * This should happen before {@link #finishRecentsControllerToHome(Runnable)}.
     */
    private void maybeFinishSwipeToHome() {
        if (mIsSwipingPipToHome && mSwipePipToHomeAnimators[0] != null) {
            SystemUiProxy.INSTANCE.get(mContext).stopSwipePipToHome(
                    mSwipePipToHomeAnimator.getTaskId(),
                    mSwipePipToHomeAnimator.getComponentName(),
                    mSwipePipToHomeAnimator.getDestinationBounds(),
                    mSwipePipToHomeAnimator.getContentOverlay());
            mRecentsAnimationController.setFinishTaskTransaction(
                    mSwipePipToHomeAnimator.getTaskId(),
                    mSwipePipToHomeAnimator.getFinishTransaction(),
                    mSwipePipToHomeAnimator.getContentOverlay());
            mIsSwipingPipToHome = false;
        } else if (mIsSwipeForStagedSplit) {
            // Transaction to hide the task to avoid flicker for entering PiP from split-screen.
            PictureInPictureSurfaceTransaction tx =
                    new PictureInPictureSurfaceTransaction.Builder()
                            .setAlpha(0f)
                            .build();
            int[] taskIds = TopTaskTracker.INSTANCE.get(mContext).getRunningSplitTaskIds();
            for (int taskId : taskIds) {
                mRecentsAnimationController.setFinishTaskTransaction(taskId,
                        tx, null /* overlay */);
            }
        }
    }

    protected abstract void finishRecentsControllerToHome(Runnable callback);

    private void setupLauncherUiAfterSwipeUpToRecentsAnimation() {
        if (mStateCallback.hasStates(STATE_HANDLER_INVALIDATED)) {
            return;
        }
        endLauncherTransitionController();
        mRecentsView.onSwipeUpAnimationSuccess();
        if (ENABLE_QUICKSTEP_LIVE_TILE.get()) {
            mTaskAnimationManager.setLiveTileCleanUpHandler(() -> {
                mRecentsView.cleanupRemoteTargets();
                mInputConsumerProxy.destroy();
            });
            mTaskAnimationManager.enableLiveTileRestartListener();
        }

        SystemUiProxy.INSTANCE.get(mContext).onOverviewShown(false, TAG);
        doLogGesture(RECENTS, mRecentsView.getCurrentPageTaskView());
        reset();
    }

    private static boolean isNotInRecents(RemoteAnimationTargetCompat app) {
        return app.isNotInRecents
                || app.activityType == ACTIVITY_TYPE_HOME;
    }

    /**
     * To be called at the end of constructor of subclasses. This calls various methods which can
     * depend on proper class initialization.
     */
    protected void initAfterSubclassConstructor() {
        initTransitionEndpoints(mRemoteTargetHandles[0].getTaskViewSimulator()
                        .getOrientationState().getLauncherDeviceProfile());
    }

    protected void performHapticFeedback() {
        VibratorWrapper.INSTANCE.get(mContext).vibrate(OVERVIEW_HAPTIC);
    }

    public Consumer<MotionEvent> getRecentsViewDispatcher(float navbarRotation) {
        return mRecentsView != null ? mRecentsView.getEventDispatcher(navbarRotation) : null;
    }

    public void setGestureEndCallback(Runnable gestureEndCallback) {
        mGestureEndCallback = gestureEndCallback;
    }

    protected void linkRecentsViewScroll() {
        SurfaceTransactionApplier.create(mRecentsView, applier -> {
            runActionOnRemoteHandles(remoteTargetHandle -> remoteTargetHandle.getTransformParams()
                            .setSyncTransactionApplier(applier));
            runOnRecentsAnimationAndLauncherBound(() ->
                    mRecentsAnimationTargets.addReleaseCheck(applier));
        });

        mRecentsView.addOnScrollChangedListener(mOnRecentsScrollListener);
        runOnRecentsAnimationAndLauncherBound(() ->
                mRecentsView.setRecentsAnimationTargets(mRecentsAnimationController,
                        mRecentsAnimationTargets));
        mRecentsViewScrollLinked = true;
    }

    private void onRecentsViewScroll() {
        if (moveWindowWithRecentsScroll()) {
            updateFinalShift();
        }
    }

    protected void startNewTask(Consumer<Boolean> resultCallback) {
        // Launch the task user scrolled to (mRecentsView.getNextPage()).
        if (!mCanceled) {
            TaskView nextTask = mRecentsView.getNextPageTaskView();
            if (nextTask != null) {
                int taskId = nextTask.getTask().key.id;
                mGestureState.updateLastStartedTaskId(taskId);
                boolean hasTaskPreviouslyAppeared = mGestureState.getPreviouslyAppearedTaskIds()
                        .contains(taskId);
                nextTask.launchTask(success -> {
                    resultCallback.accept(success);
                    if (success) {
                        if (hasTaskPreviouslyAppeared) {
                            onRestartPreviouslyAppearedTask();
                        }
                    } else {
                        mActivityInterface.onLaunchTaskFailed();
                        if (mRecentsAnimationController != null) {
                            mRecentsAnimationController.finish(true /* toRecents */, null);
                        }
                    }
                }, true /* freezeTaskList */);
            } else {
                mActivityInterface.onLaunchTaskFailed();
                Toast.makeText(mContext, R.string.activity_not_available, LENGTH_SHORT).show();
                if (mRecentsAnimationController != null) {
                    mRecentsAnimationController.finish(true /* toRecents */, null);
                }
            }
        }
        mCanceled = false;
    }

    /**
     * Runs the given {@param action} if the recents animation has already started and Launcher has
     * been created and bound to the TouchInteractionService, or queues it to be run when it this
     * next happens.
     */
    private void runOnRecentsAnimationAndLauncherBound(Runnable action) {
        mRecentsAnimationStartCallbacks.add(action);
        flushOnRecentsAnimationAndLauncherBound();
    }

    private void flushOnRecentsAnimationAndLauncherBound() {
        if (mRecentsAnimationTargets == null ||
                !mStateCallback.hasStates(STATE_LAUNCHER_BIND_TO_SERVICE)) {
            return;
        }

        if (!mRecentsAnimationStartCallbacks.isEmpty()) {
            for (Runnable action : new ArrayList<>(mRecentsAnimationStartCallbacks)) {
                action.run();
            }
            mRecentsAnimationStartCallbacks.clear();
        }
    }

    /**
     * TODO can we remove this now that we don't finish the controller until onTaskAppeared()?
     * @return whether the recents animation has started and there are valid app targets.
     */
    protected boolean hasTargets() {
        return mRecentsAnimationTargets != null && mRecentsAnimationTargets.hasTargets();
    }

    @Override
    public void onRecentsAnimationFinished(RecentsAnimationController controller) {
        if (!controller.getFinishTargetIsLauncher()) {
            setDividerShown(true /* shown */, false /* immediate */);
        }
        mRecentsAnimationTargets = null;
        if (mRecentsView != null) {
            mRecentsView.setRecentsAnimationTargets(null, null);
        }
    }

    @Override
    public void onTasksAppeared(RemoteAnimationTargetCompat[] appearedTaskTargets) {
        if (mRecentsAnimationController != null) {
            if (handleTaskAppeared(appearedTaskTargets)) {
                mRecentsAnimationController.finish(false /* toRecents */,
                        null /* onFinishComplete */);
                mActivityInterface.onLaunchTaskSuccess();
                ActiveGestureLog.INSTANCE.addLog("finishRecentsAnimation", false);
            }
        }
    }

    /**
     * @return The index of the TaskView in RecentsView whose taskId matches the task that will
     * resume if we finish the controller.
     */
    protected int getLastAppearedTaskIndex() {
        return mGestureState.getLastAppearedTaskId() != -1
                ? mRecentsView.getTaskIndexForId(mGestureState.getLastAppearedTaskId())
                : mRecentsView.getRunningTaskIndex();
    }

    /**
     * @return Whether we are continuing a gesture that already landed on a new task,
     * but before that task appeared.
     */
    protected boolean hasStartedNewTask() {
        return mGestureState.getLastStartedTaskId() != -1;
    }

    /**
     * Registers a callback to run when the activity is ready.
     */
    public void initWhenReady() {
        // Preload the plan
        RecentsModel.INSTANCE.get(mContext).getTasks(null);

        mActivityInitListener.register();
    }

    /**
     * Applies the transform on the recents animation
     */
    protected void applyScrollAndTransform() {
        // No need to apply any transform if there is ongoing swipe-to-home animator
        //    swipe-to-pip handles the leash solely
        //    swipe-to-icon animation is handled by RectFSpringAnim anim
        boolean notSwipingToHome = mRecentsAnimationTargets != null
                && mGestureState.getEndTarget() != HOME;
        boolean setRecentsScroll = mRecentsViewScrollLinked && mRecentsView != null;
        for (RemoteTargetHandle remoteHandle : mRemoteTargetHandles) {
            AnimatorControllerWithResistance playbackController =
                    remoteHandle.getPlaybackController();
            if (playbackController != null) {
                playbackController.setProgress(Math.max(mCurrentShift.value,
                        getScaleProgressDueToScroll()), mDragLengthFactor);
            }

            if (notSwipingToHome) {
                TaskViewSimulator taskViewSimulator = remoteHandle.getTaskViewSimulator();
                if (setRecentsScroll) {
                    taskViewSimulator.setScroll(mRecentsView.getScrollOffset());
                }
                taskViewSimulator.apply(remoteHandle.getTransformParams());
            }
        }
        ProtoTracer.INSTANCE.get(mContext).scheduleFrameUpdate();
    }

    // Scaling of RecentsView during quick switch based on amount of recents scroll
    private float getScaleProgressDueToScroll() {
        if (mActivity == null || !mActivity.getDeviceProfile().isTablet || mRecentsView == null
                || !mRecentsViewScrollLinked) {
            return 0;
        }

        float scrollOffset = Math.abs(mRecentsView.getScrollOffset(mRecentsView.getCurrentPage()));
        int maxScrollOffset = mRecentsView.getPagedOrientationHandler().getPrimaryValue(
                mRecentsView.getLastComputedTaskSize().width(),
                mRecentsView.getLastComputedTaskSize().height());
        maxScrollOffset += mRecentsView.getPageSpacing();

        float maxScaleProgress =
                MAX_QUICK_SWITCH_RECENTS_SCALE_PROGRESS * mRecentsView.getMaxScaleForFullScreen();
        float scaleProgress = maxScaleProgress;

        if (scrollOffset < mQuickSwitchScaleScrollThreshold) {
            scaleProgress = Utilities.mapToRange(scrollOffset, 0, mQuickSwitchScaleScrollThreshold,
                    0, maxScaleProgress, ACCEL_DEACCEL);
        } else if (scrollOffset > (maxScrollOffset - mQuickSwitchScaleScrollThreshold)) {
            scaleProgress = Utilities.mapToRange(scrollOffset,
                    (maxScrollOffset - mQuickSwitchScaleScrollThreshold), maxScrollOffset,
                    maxScaleProgress, 0, ACCEL_DEACCEL);
        }

        return scaleProgress;
    }

    private void setDividerShown(boolean shown, boolean immediate) {
        if (mDividerAnimator != null) {
            mDividerAnimator.cancel();
        }
        mDividerAnimator = TaskViewUtils.createSplitAuxiliarySurfacesAnimator(
                mRecentsAnimationTargets.nonApps, shown, (dividerAnimator) -> {
                    dividerAnimator.start();
                    if (immediate) {
                        dividerAnimator.end();
                    }
                });
    }

    /**
     * Used for winscope tracing, see launcher_trace.proto
     * @see com.android.systemui.shared.tracing.ProtoTraceable#writeToProto
     * @param inputConsumerProto The parent of this proto message.
     */
    public void writeToProto(InputConsumerProto.Builder inputConsumerProto) {
        SwipeHandlerProto.Builder swipeHandlerProto = SwipeHandlerProto.newBuilder();

        mGestureState.writeToProto(swipeHandlerProto);

        swipeHandlerProto.setIsRecentsAttachedToAppWindow(
                mAnimationFactory.isRecentsAttachedToAppWindow());
        swipeHandlerProto.setScrollOffset(mRecentsView == null
                ? 0
                : mRecentsView.getScrollOffset());
        swipeHandlerProto.setAppToOverviewProgress(mCurrentShift.value);

        inputConsumerProto.setSwipeHandler(swipeHandlerProto);
    }

    public interface Factory {
        AbsSwipeUpHandler newHandler(GestureState gestureState, long touchTimeMs);
    }
}
