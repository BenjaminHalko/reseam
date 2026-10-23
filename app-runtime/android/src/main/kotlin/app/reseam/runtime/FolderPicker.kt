// SPDX-FileCopyrightText: 2026 Cossale <hello@auna.li>
// SPDX-License-Identifier: AGPL-3.0-or-later

package app.reseam.runtime

import android.app.Activity
import android.app.Fragment
import android.content.Intent
import android.os.Bundle
import android.util.Log

/** A framework fragment receives folder results without requiring the host to forward them. */
@Export
@Suppress("DEPRECATION")
class FolderPicker : Fragment() {
    // Not kept across recreation: a picker the system rebuilds stores the folder without acting on
    // it.
    internal var onPicked: Runnable? = null

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        fragmentManager!!.beginTransaction().remove(this).commit()
        if (requestCode != REQUEST_CODE || resultCode != Activity.RESULT_OK || data == null) return
        val uri = data.data ?: return
        try {
            activity!!
                .contentResolver
                .takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
        } catch (e: SecurityException) {
            Log.w(TAG, "Could not persist permission for $uri", e)
        }
        val args = arguments!!
        Reseam.bundle(args.getString(BUNDLE)!!)
            .settings
            .setString(args.getString(KEY)!!, uri.toString())
        onPicked?.run()
    }
}

private const val TAG = "ReseamSettings"
private const val BUNDLE = "bundle"
private const val KEY = "key"
private const val REQUEST_CODE = 0x57C4

@Suppress("DEPRECATION")
internal fun pickFolder(
    activity: Activity,
    bundle: String,
    key: String,
    onPicked: Runnable? = null,
) {
    val picker =
        FolderPicker().apply {
            this.onPicked = onPicked
            arguments =
                Bundle().apply {
                    putString(BUNDLE, bundle)
                    putString(KEY, key)
                }
        }
    activity.fragmentManager!!.beginTransaction().add(picker, TAG).commitNow()
    val intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
        }
    picker.startActivityForResult(intent, REQUEST_CODE)
}
