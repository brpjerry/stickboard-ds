# StickBoard

An Android keyboard (IME) you type on with a controller's joysticks, in the style of the Steam Deck
keyboard. It's a split layout: the left stick moves a cursor on the left half, the right stick on the
right half, and the matching trigger types the key under that cursor. Touch typing works too.

- **Dual-screen handhelds** (Retroid Pocket Duo, AYN Thor, …): the keyboard is drawn on the bottom
  (smaller) screen while the app stays on the top screen. A setting adds a strip above the keyboard
  showing the text around the caret (off by default, since the field is visible on the top screen).
- **Single-screen handhelds and phones with a controller**: the keyboard sits under the app as usual.

## Controls

| Input | Action |
|---|---|
| Left / right stick | move the cursor on that half |
| L2 / R2 (or L3 / R3) | type the key under that side's cursor |
| A | type under the cursor you moved last |
| X | backspace (hold to repeat) |
| Y | space |
| L1 | shift; press twice quickly for caps lock |
| R1 | symbols / letters |
| Start | enter, or the field's action (Go, Send, Done…) |
| D-pad | move the text caret |
| B | hide the keyboard |
| Select | dual-screen: move the keyboard between screens; single screen: switch keyboard |

"Swap shoulders and triggers" in settings makes L1 / R1 type, L2 shift and R2 symbols.

There are three stick modes, picked in the settings app:

- **Relative:** the stick steers the cursor; how far you push sets the speed.
- **Trackpad:** each stick position (angle and distance) maps straight to one spot on that half,
  like a finger on the Deck's trackpads. Centered is the middle of the half and full tilt reaches the
  edge, with corners included: the round stick gate is stretched onto the rectangular half with an
  elliptical grid mapping rather than clamped. The highlight sticks to a key until the cursor is
  clearly into the next one. If you let go of the stick and pull the trigger at the same moment, the
  key you were aiming at is still typed.
- **Console:** each stick moves a selection one key at a time in eight directions (up, down, left,
  right and diagonals), like a D-pad on a console keyboard. A push steps once; holding repeats after
  a delay, at a set rate (both adjustable). Moving between rows keeps your column, so going down
  through the space bar and back up returns to the same key.

The settings app also sets cursor speed (relative mode), which screen to use, and keyboard heights.
"Number row" hides the digits above the letters (they stay on the symbols layer) and lets the
other rows grow into the space.
"Keyboard width" sets how much of the screen the two halves cover together; they stay docked to
the left and right edges and the gap in the middle takes up the rest.
When the keyboard on the other screen is shorter than the screen, a drag handle in the middle gap
moves it up or down; the position is remembered.
"See-through middle" removes the backdrop and button guide between the halves. When the keyboard is
under the app, touches in that gap go to the app; on the other screen they're ignored, because
Android blocks touches that pass through another app's overlay.

## Build

Uses the system Gradle (`pacman -S gradle`) and the Android SDK in `~/Android/Sdk`:

```sh
gradle :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell ime enable dev.stickboard/.StickBoardIme
adb shell ime set dev.stickboard/.StickBoardIme
adb shell appops set dev.stickboard SYSTEM_ALERT_WINDOW allow   # dual-screen only
```

## How the dual-screen part works

Android always puts the IME window on the screen of the focused text field. On a dual-screen device
StickBoard leaves that window empty and draws the keyboard in a non-focusable overlay on the other
screen. That needs the "display over other apps" permission, and the overlay never takes focus, so
the field on the top screen keeps receiving input.

Apps that can hide other apps' overlays (Settings, the permission controller, Play sign-in) would make
that overlay invisible. For those apps the keyboard goes under the app instead.
