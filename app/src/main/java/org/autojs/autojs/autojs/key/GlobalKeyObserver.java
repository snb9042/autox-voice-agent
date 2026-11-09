package org.autojs.autojs.autojs.key;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;

import com.stardust.app.GlobalAppContext;
import com.stardust.autojs.core.inputevent.InputEventObserver;
import com.stardust.autojs.core.inputevent.ShellKeyObserver;
import com.stardust.event.EventDispatcher;
import org.autojs.autojs.Pref;
import org.autojs.autojs.autojs.AutoJs;
import com.stardust.view.accessibility.AccessibilityService;
import com.stardust.view.accessibility.OnKeyListener;

/**
 * Created by Stardust on 2017/8/14.
 */

public class GlobalKeyObserver implements OnKeyListener, ShellKeyObserver.KeyListener {


    public interface OnVolumeDownListener {
        void onVolumeDown();
    }

    public interface OnVolumeUpHoldListener {
        void onVolumeUpHoldStart();
        void onVolumeUpHoldEnd();
    }

    private static final EventDispatcher.Event<OnVolumeDownListener> VOLUME_DOWN_EVENT = OnVolumeDownListener::onVolumeDown;
    private static final String LOG_TAG = "GlobalKeyObserver";
    private static final long EVENT_TIMEOUT = 200;
    private static GlobalKeyObserver sSingleton;
    private EventDispatcher<OnVolumeDownListener> mVolumeDownEventDispatcher = new EventDispatcher<>();
    private boolean mVolumeDownFromShell, mVolumeDownFromAccessibility;
    private boolean mVolumeUpFromShell, mVolumeUpFromAccessibility;
    // Tracks the DOWN edge of volume-up specifically, separately from the
    // existing mVolumeUpFromShell/mVolumeUpFromAccessibility pair above
    // (which dedup the UP edge) - deliberately not reusing or mirroring the
    // KEYCODE_VOLUME_DOWN branch's existing mVolumeUpFromAccessibility
    // mismatch below, which is a pre-existing bug left untouched here.
    private boolean mVolumeUpDownFromShell, mVolumeUpDownFromAccessibility;
    private boolean mVolumeUpHoldConfirmed;
    private static final long VOLUME_UP_HOLD_THRESHOLD_MILLIS = 400;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mVolumeUpHoldConfirmRunnable = this::onVolumeUpHoldConfirmed;
    private final EventDispatcher<OnVolumeUpHoldListener> mVolumeUpHoldEventDispatcher = new EventDispatcher<>();
    private static final EventDispatcher.Event<OnVolumeUpHoldListener> VOLUME_UP_HOLD_START_EVENT = OnVolumeUpHoldListener::onVolumeUpHoldStart;
    private static final EventDispatcher.Event<OnVolumeUpHoldListener> VOLUME_UP_HOLD_END_EVENT = OnVolumeUpHoldListener::onVolumeUpHoldEnd;

    GlobalKeyObserver() {
        AccessibilityService.Companion.getStickOnKeyObserver()
                .addListener(this);
        ShellKeyObserver observer = new ShellKeyObserver();
        observer.setKeyListener(this);
        InputEventObserver.getGlobal(GlobalAppContext.get()).addListener(observer);
    }

    public static GlobalKeyObserver getSingleton() {
        if (sSingleton == null) {
            sSingleton = new GlobalKeyObserver();
        }
        return sSingleton;
    }

    public static void init() {
        getSingleton();
    }

    public void onVolumeUp() {
        Log.d(LOG_TAG, "onVolumeUp at " + System.currentTimeMillis());
        mHandler.removeCallbacks(mVolumeUpHoldConfirmRunnable);
        if (mVolumeUpHoldConfirmed) {
            mVolumeUpHoldConfirmed = false;
            // Route through the main-thread handler even though this method
            // itself may already be running off-main (see this plan's Global
            // Constraints) - dispatchEvent is a plain synchronous loop with no
            // thread hop of its own, and the listener's SpeechRecognizer calls
            // require the main thread.
            mHandler.post(() -> mVolumeUpHoldEventDispatcher.dispatchEvent(VOLUME_UP_HOLD_END_EVENT));
            return;
        }
        if (Pref.isRunningVolumeControlEnabled()) {
            AutoJs.getInstance().getScriptEngineService().stopAllAndToast();
        }
    }

    public void onVolumeDown() {
        Log.d(LOG_TAG, "onVolumeDown at " + System.currentTimeMillis());
        mVolumeDownEventDispatcher.dispatchEvent(VOLUME_DOWN_EVENT);
    }

    public void addVolumeDownListener(OnVolumeDownListener listener) {
        mVolumeDownEventDispatcher.addListener(listener);
    }

    public boolean removeVolumeDownListener(OnVolumeDownListener listener) {
        return mVolumeDownEventDispatcher.removeListener(listener);
    }

    public void addVolumeUpHoldListener(OnVolumeUpHoldListener listener) {
        mVolumeUpHoldEventDispatcher.addListener(listener);
    }

    public boolean removeVolumeUpHoldListener(OnVolumeUpHoldListener listener) {
        return mVolumeUpHoldEventDispatcher.removeListener(listener);
    }

    private void onVolumeUpDown() {
        mVolumeUpHoldConfirmed = false;
        mHandler.removeCallbacks(mVolumeUpHoldConfirmRunnable);
        mHandler.postDelayed(mVolumeUpHoldConfirmRunnable, VOLUME_UP_HOLD_THRESHOLD_MILLIS);
    }

    private void onVolumeUpHoldConfirmed() {
        mVolumeUpHoldConfirmed = true;
        mVolumeUpHoldEventDispatcher.dispatchEvent(VOLUME_UP_HOLD_START_EVENT);
    }

    @Override
    public void onKeyEvent(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP && event.getAction() == KeyEvent.ACTION_DOWN) {
            if (mVolumeUpDownFromShell) {
                mVolumeUpDownFromShell = false;
                return;
            }
            mVolumeUpDownFromAccessibility = true;
            onVolumeUpDown();
            return;
        }
        if (event.getAction() != KeyEvent.ACTION_UP)
            return;
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (mVolumeDownFromShell) {
                mVolumeDownFromShell = false;
                return;
            }
            mVolumeUpFromAccessibility = true;
            onVolumeDown();
        } else if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            if (mVolumeUpFromShell) {
                mVolumeUpFromShell = false;
                return;
            }
            mVolumeUpFromAccessibility = true;
            onVolumeUp();
        }
    }


    @Override
    public void onKeyDown(String keyName) {
        if ("KEY_VOLUMEUP".equals(keyName)) {
            if (mVolumeUpDownFromAccessibility) {
                mVolumeUpDownFromAccessibility = false;
                return;
            }
            mVolumeUpDownFromShell = true;
            onVolumeUpDown();
        }
    }

    @Override
    public void onKeyUp(String keyName) {
        if ("KEY_VOLUMEUP".equals(keyName)) {
            if (mVolumeUpFromAccessibility) {
                mVolumeUpFromAccessibility = false;
                return;
            }
            mVolumeUpFromShell = true;
            onVolumeUp();
        } else if ("KEY_VOLUMEDOWN".equals(keyName)) {
            if (mVolumeDownFromAccessibility) {
                mVolumeDownFromAccessibility = false;
                return;
            }
            mVolumeDownFromShell = true;
            onVolumeDown();
        }
    }
}
