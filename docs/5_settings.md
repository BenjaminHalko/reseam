---
description: Add switches to the patched app so users can turn features on and off.
---

# Settings inside the app

[Options](4_patches.md#options) are chosen once, before patching. Settings live inside the patched app: a settings screen with switches the user can change at any time, and patched code that checks them.

It takes three parts:

1. **Settings**, declared in Kotlin.
2. **A settings host**, an internal patch that adds your bundle's settings screen to the app.
3. **Gates**, patched code that reads a setting when the app runs.

## The app runtime

The settings screen, the store patched code reads, and the [branded components](#branded-components) are the *app runtime*, which the engine provides. The engine links it into the app the first time a patch uses it. Your bundle ships none of it, and any number of bundles use it side by side. It adds nothing to the manifest, so it works in installed and [mount builds](9_app_files.md#mount-builds) alike.

The app needs an `Application` class: the settings host starts the app runtime first in its `onCreate`.

## Declare settings

```kotlin
object AppSettings {
    val hideAds by toggle("Hide ads", default = true)
    val feedStyle by choice("Feed style", default = "compact", choices = listOf(Choice("compact", "Compact"), Choice("cards", "Cards")))
}
```

`toggle`, `text`, `folder`, and `choice` each take a title, an optional summary, an optional [icon](#icons), and a default. The storage key comes from the object and property names: `AppSettings.hideAds` is stored as `app_settings.hide_ads`. Each bundle stores its settings apart, so a key only needs to be unique within your bundle.

> [!WARNING]
> Renaming the object or the property changes the key and resets the setting for every user. Pin it with `key = "..."` before your first release.

## Add the settings screen

```kotlin
val appSettings = settingsHost("example") {
    compatibleWith(EXAMPLE_APP)
    brand(exampleBrand)

    install {
        settingsRow.after { openSettings() }
    }
}
```

`install` runs after every patch that added settings. Use it to give users a way in, typically a row in the app's own settings. `openSettings()` in a code block shows your bundle's settings over the app's current screen, from any thread. When the app builds the row from an intent, such as an androidx `Preference` with an `<intent>`, call `opensSettings()` on that `<intent>` element and redirect the call that starts it to `Reseam.startActivity`.

The screen opens with an about card: your brand's logo and name, your bundle's version, the engine version, and the `license` in your [`manifest.toml`](13_bundles.md#manifesttoml).

## Brand

A brand decides how your settings screen and your [branded components](#branded-components) look. The screen follows the system's light or dark theme, so a brand has a `ColorScheme` for each.

```kotlin
private object Files

val exampleBrand = Brand(
    name = "Example",
    light = ColorScheme(primary = Color.hex("#296A48"), onPrimary = Color.hex("#FFFFFF"), ...),
    dark = ColorScheme(primary = Color.hex("#A2EBBD"), onPrimary = Color.hex("#000000"), ...),
    logo = Logo(Vector.svg(Files::class.java.getResource("/brand/logo.svg")!!.readText())),
    font = Font(Files::class.java.getResource("/brand/Inter.ttf")!!.readBytes()),
    typography = Typography(titleLarge = TextStyle(size = 22f, lineHeight = 28f, weight = 600)),
)
```

- `ColorScheme` takes the Material 3 roles the screen paints with.
- `Logo(light, dark)` takes a vector for each theme, or one for both.
- `Font` takes a TrueType or OpenType file. A variable font with a `wght` axis renders every weight the type roles name.
- `Typography` defaults to Material 3's type scale; override the roles you want different.

A host without a brand uses `Brand.DEFAULT`: Material 3's baseline colors in the system font.

Put the files in your patch module's `src/main/resources`, which ends up in the patch jar.

## Icons

Settings and pages can lead with an icon:

```kotlin
private val ads = Vector.svg(Files::class.java.getResource("/icons/megaphone.svg")!!.readText())

val hideAds by toggle("Hide ads", default = true, icon = ads)
```

`Vector.svg` reads an SVG when your patches load: paths and basic shapes, with fills and strokes. `currentColor` draws in the screen's icon color, so line icons such as [Lucide](https://lucide.dev) work as they are. Transforms, `style` attributes, gradients, and text fail, which fails the build.

## Register a patch's settings

```kotlin
val hideAds = patch("Hide ads") {
    compatibleWith(EXAMPLE_APP)
    settings(appSettings, section("Ads", AppSettings.hideAds))

    execute {
        gate(AppSettings.hideAds) {
            showAd.alwaysReturn()
        }
    }
}
```

`settings(host, section(...))` adds the section to the screen and makes the host a dependency. Only settings from patches that actually run appear. Inside `gate`, the method returns early whenever the toggle is on. See [Gates](8_changing_code.md#gates).

When a setting depends on an option, register it from a block. It runs after `execute`, so it can read options:

```kotlin
settings(appSettings) {
    listOf(section("Name", AppSettings.appName))
}
```

## Pages

Group sections behind a row on the screen with a page:

```kotlin
val mediaPage = SettingsPage("media", "Media", order = 10, summary = "Playback and quality", icon = media)

settings(appSettings, section(mediaPage, "Playback", AppSettings.autoplay))
```

A page can have a `parent` page. Lower `order` comes first. Sections with the same page and title merge into one. Pages without settings from running patches are left out.

## Read settings in extensions

[Extensions](11_extensions.md) compile against the app runtime. They reach your bundle by its name from `manifest.toml`:

```java
Settings settings = Reseam.bundle("my-patches").settings();
if (settings.getBoolean("app_settings.hide_ads", true)) { ... }
String style = settings.getChoice("app_settings.feed_style");
```

`getChoice` returns the stored value when the setting still lists it, and the default otherwise. `setBoolean` and `setString` store values, including ones that are not settings on the screen.

## Branded components

Extensions can build their own screens and dialogs in your brand:

```java
Ui ui = Reseam.bundle("my-patches").ui(activity);
ui.dialog()
        .title("Download finished")
        .message("Saved to Downloads.")
        .dismissAction("Close")
        .action("Open", () -> open(file))
        .show();
```

`Ui` builds text in the brand's type roles, buttons, list items in groups, switches, search bars, outlined text fields, top bars, and dialogs, all in the system's current theme.

## React Native apps

[Hermes gates](15_hermes.md#gate-on-a-setting) read settings through a React Native module. Add it from your host's `install`, at the hook on the app's package list:

```kotlin
getPackages.after { call(ReseamReactPackage.addTo, capture("result")) }
```

It is added once, however many bundles call it.

Next: [Finding code](6_finding_code.md).
