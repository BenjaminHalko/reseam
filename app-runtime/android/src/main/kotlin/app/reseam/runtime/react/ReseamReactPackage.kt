// SPDX-FileCopyrightText: 2026 Cossale <hello@auna.li>
// SPDX-License-Identifier: AGPL-3.0-or-later

package app.reseam.runtime.react

import app.reseam.runtime.Export
import com.facebook.react.BaseReactPackage
import com.facebook.react.ReactPackage
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.module.model.ReactModuleInfo
import com.facebook.react.module.model.ReactModuleInfoProvider

object ReseamReactPackage : BaseReactPackage() {
    /** Adds the package once, however many bundles call this. */
    @Export
    @JvmStatic
    fun addTo(packages: MutableList<ReactPackage>) {
        if (packages.none { it is ReseamReactPackage }) packages.add(this)
    }

    override fun getModule(name: String, context: ReactApplicationContext): NativeModule? =
        if (name == SETTINGS_MODULE_NAME) ReseamSettingsModule(context) else null

    override fun getReactModuleInfoProvider(): ReactModuleInfoProvider {
        val info =
            ReactModuleInfo(
                SETTINGS_MODULE_NAME,
                ReseamSettingsModule::class.java.name,
                false,
                false,
                false,
                false,
            )
        return ReactModuleInfoProvider { mapOf(SETTINGS_MODULE_NAME to info) }
    }
}
