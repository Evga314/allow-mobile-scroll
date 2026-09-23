# Allow Mobile Scroll

* 🧩 Partial support for non-vanilla scrolling interfaces used by mods, including:


  * Sodium
  * Mod Menu
  * Cloth Config
  * Easy install
  * Yet another config lib
  * Malilib
  * T Render
* 📲 Designed specifically for mobile Java Minecraft launchers and their mouse/touch emulation behavior in mind.


## Compatibility


* **Minecraft:** 26.3
* **Mod Loader:** Fabric
* **Environment:** Client-side




Support for custom scrolling implementations is currently being developed. Some mods may use their own GUI scrolling systems and may require additional compatibility work.


## Dependencies


* **Fabric API**
* **Mod Menu** — required for accessing the mod's configuration screen


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


### Inertia strength




Default: 70. Range: from 0 to 200


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



Build the mod:

```bash
./gradlew build
```

The compiled JAR will be available in:

```text
build/libs/
```

## Project Status

**Beta**

Basic scrolling for standard vanilla Minecraft GUI screens is currently working fine

Partial support for custom scrolling interfaces used by mods such as **Sodium** and **Mod Menu** has also been implemented and is still being improved.

**I DON'T GUARANTEE THIS MOD WILL WORKS FINE ON PC's. I MADE IT SPECIFICALLY FOR MOBILE PHONES**

## License

This project is licensed under the **MIT License**.


