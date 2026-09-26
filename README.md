# Allow Mobile Scroll







Support for custom scrolling implementations is currently being developed. Some mods may use their own GUI scrolling systems and may require additional compatibility work. 




## Dependencies




* **Fabric API**
* **Mod Menu** — required for accessing the mod's configuration screen




## Configuration




The mod's settings can be accessed through **Mod Menu**.





**Mouse wheel emulator** — simply add a button with the numpad_divine key to your launcher, and you can now open a convenient mouse wheel emulator in the game menu! This can help in cases where a specific mod or a list of mods is not currently supported by our mod. The controls are very simple — the wheel scrolls from the last position where the cursor was located before you touched the emulator (you don't need to press anything). Don't forget to disable the virtual mouse in your launcher before scrolling with this feature.



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


**Pre-release**


Basic scrolling for standard vanilla Minecraft GUI screens is currently working fine, also you can use our mouse wheel somulator to scroll unsupported mods

