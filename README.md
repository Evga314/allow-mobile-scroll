# Allow Mobile Scroll

**Allow Mobile Scroll** is a **Minecraft 26.3 Fabric mod** that adds touch-friendly scrolling to Minecraft Java Edition when played through mobile Java launchers.

It allows scrollable GUI screens to be navigated with a simple **swipe up or down with your finger**, without requiring a physical mouse wheel.

## Features

* 📱 Swipe to scroll Minecraft GUI screens using a touchscreen.
* 👆 Scroll by simply moving your finger up or down.
* 🖱️ Normal left-click interactions continue to work.
* 🔄 Scrolling does not reset when starting a new touch.
* ⚙️ Configurable **Scroll Threshold**.

  * Default: **4 px**
  * Minimum: **1 px**
  * Maximum: **50 px**
(Soon)

* 🧩 Partial support for non-vanilla scrolling interfaces used by mods, including:

  * Sodium
  * Mod Menu
  * soon other mods
* 📲 Designed specifically with mobile Java Minecraft launchers and their mouse/touch emulation behavior in mind.

## Compatibility

* **Minecraft:** 26.3
* **Mod Loader:** Fabric
* **Environment:** Client-side

The mod is primarily designed for mobile Java launchers where touchscreen input is translated into mouse events.

Support for custom scrolling implementations is currently being developed. Some mods may use their own GUI scrolling systems and may require additional compatibility work.

## Dependencies

* **Minecraft 26.3**
* **Fabric Loader 0.19.5**
* **Fabric API 0.161.0+26.3**
* **Mod Menu 21.0.0-beta.1** — required for accessing the mod's configuration screen

## Configuration

The mod's settings can be accessed through **Mod Menu**.

### Scroll Threshold

**Scroll Threshold** defines the minimum distance the finger must travel before a touch is recognized as a scroll gesture.

Default:

```text
4 px
```

Allowed range:

```text
1–50 px
```

A lower value makes scrolling activate with shorter finger movements, while a higher value requires a longer swipe before scrolling begins.

## Building

### Requirements

* **JDK 25**
* Internet access for downloading Gradle and Minecraft/Fabric dependencies

Clone the repository:

```bash
git clone https://github.com/Evga314/allow-mobile-scroll.git
cd allow-mobile-scroll
```

Make the Gradle wrapper executable:

```bash
chmod +x gradlew
```

Clean the project:

```bash
./gradlew clean
```

Build the mod:

```bash
./gradlew build
```

The compiled JAR will be available in:

```text
build/libs/
```

## Project Status

**Alpha**

Basic scrolling for standard vanilla Minecraft GUI screens is currently working.

Partial support for custom scrolling interfaces used by mods such as **Sodium** and **Mod Menu** has also been implemented and is still being improved.

## License

This project is licensed under the **MIT License**.
