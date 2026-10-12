// SPDX-FileCopyrightText: 2026 Cossale <hello@auna.li>
// SPDX-License-Identifier: AGPL-3.0-or-later

package app.reseam.runtime;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;

import java.util.Map;

/** Offers a restart after the settings close with changed values, since most patches read their settings once at startup. */
final class RestartPrompt {
    private RestartPrompt() {}

    static void offer(Activity activity, ReseamBundle bundle, Map<String, ?> opened) {
        if (activity.isFinishing() || opened.equals(bundle.settings().snapshot())) return;
        bundle.ui(activity).dialog()
                .title("Restart to apply changes?")
                .message("Most settings take effect after the app restarts.")
                .dismissAction("Later")
                .action("Restart", () -> restart(activity))
                .show();
    }

    private static void restart(Context context) {
        Intent launch = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        context.startActivity(Intent.makeRestartActivityTask(launch.getComponent()));
        Runtime.getRuntime().exit(0);
    }
}
