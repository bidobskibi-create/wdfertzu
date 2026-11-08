MIUI Home v2 (com.archdroid.miuihome) - now with drag and drop
=================================================================
Build (same as SwipeNav): push this folder to a GitHub repo -> Actions -> "build" ->
download the artifact MiuiHome-flash (MiuiHome-flash.zip). Or run ./build.sh with ANDROID_HOME set.
Flash MiuiHome-flash.zip in TWRP (installs /system/app/MiuiHome.apk), reboot, press Home,
choose "MIUI Home" -> Always.   (Replaces the old MiuiHome.apk, so no uninstall needed.)

Using it
  * Tap an app: open it.      * Swipe sideways: change page.
  * PRESS AND HOLD an app: it lifts under your finger, the others make room.
      - drag within the grid to reorder
      - hold at the left/right screen edge to flip to the previous/next page
      - drop on the dock (4 apps max); dropping on a full dock swaps with that app
      - drag to the top bar: left half = App info, right half = Uninstall
      - lift your finger without moving = app menu (App info / Uninstall)
  * Press and hold empty space: Wallpaper / System settings.
  * Order and dock are saved. New apps are added at the end; removed apps disappear.
  * Home button / Back: back to the first page (cancels a drag).

Files: src/com/archdroid/miuihome/{App,HomeActivity,HomeView}.java
This was rebuilt from the compiled v1 APK (its source was not available), keeping its features: 4-column
paged grid, dock with browser/contacts/messaging/dialer by default, clock + date, page dots, wallpaper
scrolling, install/remove and clock receivers, and its menus.
