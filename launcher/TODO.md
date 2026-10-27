# Tomboy home: to do

- [ ] **Remote's Inputs button → Tomboy home.** Fire OS handles the button itself, so Guard
  has to replace the screen it opens (`guard.replace` in home.conf, already supported).
  Needed: that screen's name. With the TV on Tomboy's home: `adb logcat -c`, press Inputs,
  then `adb logcat -d | grep -iE "START u0|Displayed"`. (`dumpsys window | grep mCurrentFocus`
  showed Tomboy's own Input screen, so Amazon's panel may not take focus.)
