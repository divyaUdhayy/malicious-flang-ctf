
> [!Caution]
> This is a copy of Jannis's FlangAndroid application.
> 
> This project intentionally contains vulnerabilities, malicious elements, and flags to be found.

---

# Malicious Flang Android

## The App
This repository contains a copy of Jannis's FlangAndroid application at commit `40a71fa489` in `app/` used under the GNUv3 license. It intentionally includes malicious code, flags to be found, and vulnerabilities for educational purposes. The application's original README.md with the added disclaimer can be found in app/README.md. The original source code is available at: https://codeberg.org/jannis/FlangAndroid

To launch the app, open the `app/` directory in Android Studio and run the app on an emulator or a physical device.

For analyzing, do not use or analyze the source code. The `apk` file should be analyzed.
Intended analysis tools include:
- Mobile emulators
- apktool
- JADX
- Ghidra
- Scripts or command line tools
- Other online tools

There are intentional vulnerabilities to be exploited, malicious elements to be found, and flags in the format "FLAG{flag_here}" to be discovered, of various difficulty levels. The original source code is based on an open-source project and, ideally, the original source code should NOT be analyzed in its original form.

## The Server
The server code is located in the `server/` directory and serves as a server for requests. It should NOT be analyzed, and doing so would constitute a violation of the rules of this challenge. The server represents a remote server that the application would connect to.

To launch the server, run the following command in the `server/` directory:

```bash
npm install
npm rebuild # if you are on a different platform
node server.js
```
