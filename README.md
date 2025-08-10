# ALTS-Android-app
## Prerequisites
Before running the Android Studio project, you’ll need:
Android Studio (latest stable version, with Android SDK & Gradle installed)
An Android device or emulator (physical device recommended for microphone access)
Java 11 or later installed (Android Studio will install this automatically)
Your ALTS backend server running (from your previous Python STT+TTS setup)

## Project Setup in Android Studio
Clone or copy your Android code into a folder.
Open Android Studio → Open Project → select your folder.
Ensure Gradle sync completes without errors.
Connect your phone via USB (with Developer Mode + USB Debugging enabled).

## Running the App
python alts.py
In the Android app, enter your server’s IP & port in the text field.
Tap Start Recording → speak → tap again to stop.
The app sends your voice recording to /process_audio endpoint on the server.
The server:
  Transcribes voice with Whisper
  Sends text to LLaMA
  Synthesizes speech with Coqui TTS
The app:
  Displays the text in the TextView
  Plays the AI’s voice reply

## Backend Networking — Inbound Rule Setup
This is critical for the Android app to reach your backend.If your server is on a cloud VM (e.g., GCP, AWS, Azure)
Open port 8000 in the VM’s firewall/security group.
 Example (GCP):
> Go to VPC network → Firewall rules → Create firewall rule
> Name: allow-alts
> Direction: Ingress
Targets: All instances (or specific one)
> Source IP ranges: 0.0.0.0/0 (or restrict to your home IP for security)
> Protocols and ports: tcp:8000
> Save.

If your server is on local network
You need port forwarding on your router to forward port 8000 to your PC’s local IP.
Or, if phone & PC are on the same Wi-Fi, just use your PC’s LAN IP (e.g., 192.168.0.25:8000) — no port forwarding needed.

Python backend must listen on all interfaces
When starting your backend:

**uvicorn main:app --host 0.0.0.0 --port 8000**

