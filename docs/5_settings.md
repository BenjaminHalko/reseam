---
description: Add switches to the patched app so users can turn features on and off.
---

# Settings inside the app

[Options](4_patches.md#options) are chosen once, before patching. Settings live inside the patched app: a settings screen with switches the user can change at any time, and patched code that checks them.

It takes three parts:

1. **Settings**, declared in Kotlin in a settings module, with their text in string resources.
2. **A settings host**, an internal patch that adds your bundle's settings screen to the app.
3. **Gates**, patched code that reads a setting when the app runs.

## The app runtime

The settings screen, the store patched code reads, and the [branded components](#branded-components) are the *app runtime*, which the engine provides. The engine links it into the app the first time a patch uses it. Your bundle ships none of it, and any number of bundles use it side by side. It adds nothing to the manifest, so it works in installed and [mount builds](9_app_files.md#mount-builds) alike.

The app needs an `Application` class: the settings host starts the app runtime first in its `onCreate`.

## Declare settings

Settings live in a *settings module*, `apps/<app>/settings/` (or `shared/<name>/settings/`), which the app's patches and extensions both use:

```text
apps/example/settings/
  build.gradle.kts
  src/main/kotlin/app/example/settings/AppSettings.kt
  src/main/strings/values/strings.xml
```

```kotlin
// build.gradle.kts
reseamStrings { objectName = "app.example.settings.ExampleStrings" }
```

```kotlin
object AppSettings : SettingsGroup(ExampleStrings) {
    val hideAds by toggle(default = true)
    val feedStyle by choice(
        default = "compact",
        choices = listOf(Choice("compact", ExampleStrings.feedStyleCompact), Choice("cards", ExampleStrings.feedStyleCards)),
    )
}
```

```xml
<resources xmlns:xliff="urn:oasis:names:tc:xliff:document:1.2">
    <string name="hide_ads">Hide ads</string>
    <string name="hide_ads_summary">Removes ads from the feed.</string>
    <string name="feed_style">Feed style</string>
    <string name="feed_style_compact">Compact</string>
    <string name="feed_style_cards">Cards</string>
</resources>
```

`toggle`, `text`, `folder`, and `choice` take a default and an optional [icon](#icons). A setting's title is the string named after its property in snake case, and its summary the same name with `_summary`, when there is one. A missing title fails the build. The build generates `ExampleStrings` from `strings.xml`, with one member per string, for choices, [sections](#register-a-patchs-settings) and [pages](#pages).

The storage key comes from the object and property names: `AppSettings.hideAds` is stored as `app_settings.hide_ads`. Each bundle stores its settings apart, so a key only needs to be unique within your bundle.

> [!WARNING]
> Renaming the object or the property changes the key and resets the setting for every user. Pin it with `key = "..."` before your first release.

A patch module outside the `apps/` and `shared/` layout uses a settings module through `api(project(":settings"))` and applies the `app.reseam.settings` plugin to it.

## Translations

Translations go next to the default strings, in Android's folder names: `values-de/strings.xml`, `values-pt-rBR/strings.xml`, `values-b+sr+Latn/strings.xml`. The app shows each string in the app's current language (including Android's per-app language setting) and falls back to the default when a translation lacks it.

- Escape apostrophes and quotes as `\'` and `\"`.
- A string with arguments wraps each in `xliff:g`, whose `id` names the argument: `Saved to <xliff:g id="folder">%1$s</xliff:g>`. `%s` takes text, `%d` a whole number, `%f` a decimal number.
- `<plurals>` picks its form by an integer argument with id `count`.
- A translation must keep the default's arguments; one that adds, drops or retypes an argument, or translates a string the default lacks, fails the build.

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

`install` runs after every patch that added settings. `strings(ExampleStrings)` ships a string table your extensions read even when no setting on the screen uses it. Use it to give users a way in, typically a row in the app's own settings. `openSettings()` in a code block shows your bundle's settings over the app's current screen, from any thread. When the app builds the row from an intent, such as an androidx `Preference` with an `<intent>`, call `opensSettings()` on that `<intent>` element and redirect the call that starts it to `Reseam.startActivity`.

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

val hideAds by toggle(default = true, icon = ads)
```

`Vector.svg` reads an SVG when your patches load: paths and basic shapes, with fills and strokes. `currentColor` draws in the screen's icon color, so line icons such as [Lucide](https://lucide.dev) work as they are. Transforms, `style` attributes, gradients, and text fail, which fails the build.

## Register a patch's settings

```kotlin
val hideAds = patch("Hide ads") {
    compatibleWith(EXAMPLE_APP)
    settings(appSettings, section(ExampleStrings.sectionAds, AppSettings.hideAds))

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
    listOf(section(ExampleStrings.sectionName, AppSettings.appName))
}
```

## Pages

Group sections behind a row on the screen with a page:

```kotlin
val mediaPage = SettingsPage("media", ExampleStrings.pageMedia, order = 10, summary = ExampleStrings.pageMediaSummary, icon = media)

settings(appSettings, section(mediaPage, ExampleStrings.sectionPlayback, AppSettings.autoplay))
```

A choice whose title the user typed into an option, such as a custom app name, uses `Verbatim(name)` instead of a string, and shows as is in every language.

A page can have a `parent` page. Lower `order` comes first. Sections with the same page and title merge into one. Pages without settings from running patches are left out.

## Read settings in extensions

[Extensions](11_extensions.md) compile against objects the build generates from your settings module: the same names, with a value for each setting, read and written in the app.

```kotlin
import app.example.settings.AppSettings
import app.example.settings.ExampleStrings

if (AppSettings.hideAds.value) hide(view)
val style = AppSettings.feedStyle.value
AppSettings.feedStyle.reset()
Toast.makeText(context, ExampleStrings.downloadSaved(folder), Toast.LENGTH_SHORT).show()
```

A choice reads as the stored value while the setting still lists it, and as the default otherwise. Before the app runtime has started, values read as their defaults. String members without arguments are properties; the others are functions named after the string, taking its `xliff:g` arguments in order.

Outside the `apps/` and `shared/` layout, an extension adds a settings module with `reseamSettings(project(":settings"))`.

## Branded components

Extensions can build their own screens and dialogs in your brand:

```kotlin
val ui = Reseam.bundle("my-patches").ui(activity)
ui.dialog()
    .title(ExampleStrings.downloadFinished)
    .message(ExampleStrings.downloadSavedToDownloads)
    .dismissAction(ExampleStrings.close)
    .action(ExampleStrings.open) { open(file) }
    .show()
```

`Ui` builds text in the brand's type roles, buttons, list items in groups, switches, search bars, outlined text fields, top bars, and dialogs, all in the system's current theme.

## React Native apps

[Hermes gates](15_hermes.md#gate-on-a-setting) read settings through a React Native module. Add it from your host's `install`, at the hook on the app's package list:

```kotlin
getPackages.after { call(ReseamReactPackage.addTo, result) }
```

It is added once, however many bundles call it.

Next: [Finding code](6_finding_code.md).
