// SPDX-FileCopyrightText: 2026 Cossale <hello@auna.li>
// SPDX-License-Identifier: AGPL-3.0-or-later

package app.reseam.runtime;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.app.Activity;
import android.app.Application;
import android.app.Dialog;
import android.content.ComponentCallbacks;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsetsController;
import android.widget.FrameLayout;
import android.window.OnBackInvokedDispatcher;

import app.reseam.runtime.ui.Motion;
import app.reseam.runtime.ui.Theme;
import app.reseam.runtime.ui.Ui;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Shows a bundle's settings pages in a full-screen dialog over the app's current screen. A dialog
 * needs no activity in the manifest, so the same build works installed or mounted over the
 * installed app, where the system keeps the installed manifest.
 */
final class SettingsPanel implements Application.ActivityLifecycleCallbacks, ComponentCallbacks {
    private static final String TAG = "ReseamSettings";
    private static final String ROOT_PAGE = "";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static SettingsPanel instance;

    private WeakReference<Activity> resumed = new WeakReference<>(null);
    private ReseamBundle bundle;
    private Ui ui;
    private Activity host;
    private Dialog dialog;
    private FrameLayout frame;
    private View page;
    private Animator transition;
    private final List<String> pageIds = new ArrayList<>();
    private Map<String, ?> opened;
    private List<String> reopen;
    private Class<?> reopenIn;

    private SettingsPanel() {}

    static void track(Application app) {
        instance = new SettingsPanel();
        app.registerActivityLifecycleCallbacks(instance);
        app.registerComponentCallbacks(instance);
    }

    static void open(ReseamBundle bundle) {
        SettingsPanel panel = instance;
        if (panel == null) throw new IllegalStateException("Reseam.init has not run");
        MAIN.post(() -> panel.show(bundle, Collections.singletonList(ROOT_PAGE)));
    }

    private void push(String pageId) {
        pageIds.add(pageId);
        display(Direction.FORWARD);
    }

    private void back() {
        if (pageIds.size() == 1) {
            dialog.dismiss();
            return;
        }
        pageIds.remove(pageIds.size() - 1);
        display(Direction.BACKWARD);
    }

    private void display(Direction direction) {
        if (transition != null) transition.end();
        View previous = page;
        page = SettingsScreen.page(bundle, ui, pageIds.get(pageIds.size() - 1), this::back, this::push);
        frame.addView(page);
        // Insets reach only the views attached when the window first lays out.
        frame.requestApplyInsets();
        if (previous == null) return;
        if (direction == Direction.NONE) {
            frame.removeView(previous);
            return;
        }
        transition = Motion.sharedAxisX(previous, page, direction == Direction.FORWARD);
        transition.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                frame.removeView(previous);
                transition = null;
            }
        });
        transition.start();
    }

    private enum Direction {
        NONE,
        FORWARD,
        BACKWARD,
    }

    private void show(ReseamBundle bundle, List<String> ids) {
        Activity activity = resumed.get();
        if (dialog != null) return;
        if (activity == null) {
            Log.e(TAG, "Cannot open settings: no screen of the app is in the foreground");
            return;
        }
        if (opened == null) opened = bundle.settings().snapshot();
        this.bundle = bundle;
        host = activity;
        dialog = new Dialog(activity, android.R.style.Theme_DeviceDefault_NoActionBar) {
            @Override
            public void onBackPressed() {
                back();
            }
        };
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Hosts that opt in to predictive Back never call onBackPressed.
            dialog.getOnBackInvokedDispatcher()
                    .registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::back);
        }
        dialog.setOnDismissListener(d -> closed());
        frame = new FrameLayout(dialog.getContext());
        dialog.setContentView(frame);
        dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        pageIds.addAll(ids);
        theme();
        dialog.show();
    }

    @SuppressWarnings("deprecation")
    private void theme() {
        ui = bundle.ui(dialog.getContext());
        Window window = dialog.getWindow();
        int surface = ui.colors().surface;
        window.getDecorView().setBackgroundColor(surface);
        window.setStatusBarColor(surface);
        window.setNavigationBarColor(surface);
        boolean light = !ui.theme.dark;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            int appearance = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            window.getInsetsController().setSystemBarsAppearance(light ? appearance : 0, appearance);
        } else {
            View decor = window.getDecorView();
            int flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            int visibility = decor.getSystemUiVisibility();
            decor.setSystemUiVisibility(light ? visibility | flags : visibility & ~flags);
        }
        display(Direction.NONE);
    }

    private void closed() {
        Activity activity = host;
        ReseamBundle closedBundle = bundle;
        Map<String, ?> before = opened;
        release();
        opened = null;
        RestartPrompt.offer(activity, closedBundle, before);
    }

    private void release() {
        if (transition != null) transition.end();
        page = null;
        host = null;
        dialog = null;
        frame = null;
        ui = null;
        pageIds.clear();
    }

    /** Hosts that handle night mode themselves stay alive when it changes, so the pages follow here. */
    @Override
    public void onConfigurationChanged(Configuration configuration) {
        if (dialog != null && ui.theme.dark != Theme.of(bundle.brand).dark) theme();
    }

    @Override
    public void onLowMemory() {}

    @Override
    public void onActivityResumed(Activity activity) {
        resumed = new WeakReference<>(activity);
        if (reopen == null || activity.getClass() != reopenIn) return;
        List<String> ids = reopen;
        reopen = null;
        reopenIn = null;
        show(bundle, ids);
    }

    @Override
    public void onActivityDestroyed(Activity activity) {
        if (activity != host) return;
        // The dismiss listener runs later, after a recreated host has already resumed.
        dialog.setOnDismissListener(null);
        dialog.dismiss();
        // A recreated host gets the same pages back; a finished one takes the panel with it.
        if (activity.isChangingConfigurations()) {
            reopen = new ArrayList<>(pageIds);
            reopenIn = activity.getClass();
        } else {
            opened = null;
        }
        release();
    }

    @Override
    public void onActivityCreated(Activity activity, Bundle savedInstanceState) {}

    @Override
    public void onActivityStarted(Activity activity) {}

    @Override
    public void onActivityPaused(Activity activity) {}

    @Override
    public void onActivityStopped(Activity activity) {}

    @Override
    public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}
}
