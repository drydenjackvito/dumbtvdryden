# Tomboy home: to do

- [ ] **Remote's Inputs button → Tomboy home.** Fire OS handles the button itself, so Guard
  has to replace the screen it opens (`guard.replace` in home.conf, already supported).
  Needed: that screen's name. With the TV on Tomboy's home: `adb logcat -c`, press Inputs,
  then `adb logcat -d | grep -iE "START u0|Displayed"`. (`dumpsys window | grep mCurrentFocus`
  showed Tomboy's own Input screen, so Amazon's panel may not take focus.)
- [ ] **Test Untitled on the TV.** ☰ › Untitled: sign in, play a song, Home, then the PS5.
  Check the card (song, artist, artwork), ⏯ ⏩ ⏪, channel ▲ ▼ (ceiling) and ☰ back to
  Untitled. If the card is wrong: `adb logcat -d | grep "untitled:"`.
- [ ] **Confirm on the TV:** casting from the phone's YouTube app (link with the code,
  corner window on an input, AirPlay on the home screen), the video window closing when
  the video ends, Spotify ready after a reboot with no flash, the input LEDs, the
  slimmer top bar.
- [ ] **Greeting placement.** Centred in the open space or across the whole screen: pick
  one from the side-by-side mock-up.
- [ ] **Amazon's home flashing at boot** before Tomboy takes over.
