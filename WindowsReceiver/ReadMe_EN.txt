UtaMaru - WindowsReceiver setup (English)
=========================================

This folder lets UtaMaru for Android send microphone audio to your Windows PC
for use with Discord, OBS, games, or other apps. Works with USB or local Wi-Fi.

REQUIRED (download separately from the official sites):
  * VB-CABLE (VB-Audio Virtual Cable): https://vb-audio.com/Cable/
  * For USB only: Google Android SDK Platform-Tools (ADB):
    https://developer.android.com/tools/releases/platform-tools

VB-CABLE and ADB are NOT bundled. Keep the WindowsReceiver folder intact;
you may place it anywhere. You do not need Android Studio if you install ADB
from the Google Platform-Tools package.

Language of WindowsReceiver launch instructions
------------------------------------------------
Japanese Windows UI language -> Japanese; all other Windows UI languages -> English.
To override, open Command Prompt and run:
  set UTAMARU_LANG=en
  START_PC_USB.bat
Or replace en with ja, and/or use START_PC_WIFI.bat.
The override applies only to that Command Prompt session.
The receiver executable's own diagnostic output is in English.

Initial PC setup (both USB and Wi-Fi)
------------------------------------
1. Download and install VB-CABLE from the official site. Restart if required.
2. Confirm that the playback device is CABLE Input and the recording device
   is CABLE Output in Windows sound settings.
3. Place the WindowsReceiver folder anywhere on your PC. Keep all of its files
   together, including the BAT, PS1, EXE, and LOCALIZATION.ps1 files.
4. Double-click the appropriate BAT file.
5. On the VB-CABLE startup check, choose whether to download VB-CABLE,
   retry detection, or continue if you have verified the devices in Windows.

USB connection
--------------
1. On your Android phone, enable Developer options (usually tap Build number
   7 times in About phone), then turn on USB debugging.
2. Connect the phone using a data-capable USB cable. Approve the debugging
   authorization dialog on the phone. Approve only your own trusted PC.
3. Install Google Platform-Tools, or use your existing Android SDK installation.
4. Start START_PC_USB.bat. If ADB is not detected, choose the platform-tools
   folder containing adb.exe, AdbWinApi.dll, and AdbWinUsbApi.dll.
5. If multiple Android devices are connected, choose the intended device.
6. After the USB port forward is ready, enter 127.0.0.1 as the PC host in
   UtaMaru, then start sending converted or raw microphone audio from the app.
7. Stopping WindowsReceiver will also remove the selected ADB reverse rule.

Wi-Fi connection
----------------
1. Connect phone and PC to the same trusted private LAN. They must be able
   to communicate with each other; guest-network isolation may block this.
2. Start START_PC_WIFI.bat. ADB/USB debugging is NOT required for Wi-Fi.
3. Find your PC's local IPv4 address from the console output (example:
   192.168.1.10). Enter that address as the PC host in the UtaMaru app.
4. Start sending converted or raw microphone audio from UtaMaru.
5. If Windows Firewall prompts you, allow PRIVATE networks only.
   NEVER open or forward TCP port 50005 to the public Internet.

Use in PC voice apps
--------------------
WindowsReceiver plays incoming sound to CABLE Input (PLAYBACK side).
Set the MICROPHONE or RECORDING device to CABLE Output in Discord, OBS, games,
or other Windows applications. These two similar names are easy to mix up.

Troubleshooting
---------------
* ADB not found: verify Platform-Tools is installed and select its directory.
* Phone unauthorized: approve USB debugging on the phone screen.
* No USB device: check the cable, USB debugging, and the device selection.
* Wi-Fi connection fails: confirm the host IPv4 address, LAN connectivity,
  Windows Firewall settings, and private-network access.
* No sound: verify VB-CABLE and select CABLE Output as the app microphone.
* Avoid acoustic feedback by using headphones during monitoring.

To stop sending, press the active PC-send button again in UtaMaru.
You can then close WindowsReceiver.
