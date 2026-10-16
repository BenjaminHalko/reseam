// SPDX-FileCopyrightText: 2026 Cossale <hello@auna.li>
// SPDX-License-Identifier: AGPL-3.0-or-later

package app.reseam.runtime

import android.app.Activity
import android.app.Fragment
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import java.io.FileNotFoundException
import java.io.IOException
import org.json.JSONException
import org.json.JSONObject

/** A framework fragment receives document results without requiring the host to forward them. */
@Export
@Suppress("DEPRECATION")
class SettingsTransfer : Fragment() {
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        fragmentManager!!.beginTransaction().remove(this).commit()
        val uri = data?.data
        if (resultCode != Activity.RESULT_OK || uri == null) return
        val bundle = Reseam.bundle(arguments!!.getString(BUNDLE)!!)
        when (requestCode) {
            EXPORT -> exportTo(activity!!, bundle, uri)
            IMPORT -> importFrom(activity!!, bundle, uri)
        }
    }
}

private const val TAG = "ReseamSettings"
private const val BUNDLE = "bundle"
private const val SETTINGS = "settings"
private const val EXPORT = 0x57C5
private const val IMPORT = 0x57C6
private const val JSON = "application/json"

// File managers and cloud providers label .json files inconsistently.
private val IMPORT_TYPES = arrayOf(JSON, "text/plain", "application/octet-stream")

internal fun exportSettings(activity: Activity, bundle: ReseamBundle) {
    val app = activity.applicationInfo.loadLabel(activity.packageManager).toString()
    pick(
        activity,
        bundle,
        EXPORT,
        Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(JSON)
            .putExtra(Intent.EXTRA_TITLE, RuntimeStrings.settingsFileName(app)),
    )
}

internal fun importSettings(activity: Activity, bundle: ReseamBundle) =
    pick(
        activity,
        bundle,
        IMPORT,
        Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, IMPORT_TYPES),
    )

@Suppress("DEPRECATION")
private fun pick(activity: Activity, bundle: ReseamBundle, requestCode: Int, intent: Intent) {
    val transfer =
        SettingsTransfer().apply { arguments = Bundle().apply { putString(BUNDLE, bundle.name) } }
    activity.fragmentManager!!.beginTransaction().add(transfer, TAG).commitNow()
    transfer.startActivityForResult(intent, requestCode)
}

private fun exportTo(activity: Activity, bundle: ReseamBundle, uri: Uri) {
    val file = JSONObject().put(BUNDLE, bundle.name).put(SETTINGS, bundle.settings.export())
    try {
        val output =
            activity.contentResolver.openOutputStream(uri, "wt")
                ?: throw FileNotFoundException("No provider opened $uri")
        output.use { it.write(file.toString(2).toByteArray(Charsets.UTF_8)) }
    } catch (e: IOException) {
        Log.w(TAG, "Could not write settings to $uri", e)
        failed(activity, bundle, RuntimeStrings.exportFailed, null)
        return
    }
    Toast.makeText(activity, RuntimeStrings.settingsExported, Toast.LENGTH_SHORT).show()
}

private fun importFrom(activity: Activity, bundle: ReseamBundle, uri: Uri) {
    val file =
        try {
            val input =
                activity.contentResolver.openInputStream(uri)
                    ?: throw FileNotFoundException("No provider opened $uri")
            JSONObject(input.bufferedReader(Charsets.UTF_8).use { it.readText() })
        } catch (e: IOException) {
            Log.w(TAG, "Could not read settings from $uri", e)
            null
        } catch (e: JSONException) {
            Log.w(TAG, "Settings file $uri is not JSON", e)
            null
        }
    val values = file?.optJSONObject(SETTINGS)
    if (values == null) {
        failed(activity, bundle, RuntimeStrings.importFailed, RuntimeStrings.importUnreadable)
        return
    }
    val applied = if (file.optString(BUNDLE) == bundle.name) bundle.settings.import(values) else 0
    if (applied == 0) {
        failed(activity, bundle, RuntimeStrings.importFailed, RuntimeStrings.importNothingApplies)
        return
    }
    Toast.makeText(activity, RuntimeStrings.settingsImported(applied), Toast.LENGTH_SHORT).show()
}

private fun failed(activity: Activity, bundle: ReseamBundle, title: String, message: String?) {
    bundle
        .ui(activity)
        .dialog()
        .title(title)
        .message(message)
        .dismissAction(RuntimeStrings.ok)
        .show()
}
