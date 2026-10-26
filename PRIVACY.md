# Data and permissions

- Profile information: Android's account picker selects an account identity. The displayed name/photo comes from a verified local cache or an optional profile import protected by Android's `WRITE_SECURE_SETTINGS` permission; selecting an account does not automatically fetch its Google profile photo. Account switching and adding accounts are delegated to Android's account interfaces.
- Connection status: passive Android network callbacks expose LAN/Wi-Fi/connection state and a numeric IP address. The status indicator does not scan Wi-Fi networks or upload this status to a project service.
- Weather: device/network location or a saved location may be used. Approximate IP lookup contacts `https://ipwho.is/`; weather contacts `https://api.open-meteo.com/` with latitude/longitude. Those services can receive the device's public IP. Resolving coordinates to a city also uses Android/OEM Geocoder, which may contact its provider's network service with those coordinates. Review the providers' terms and privacy policies for your use.
- Settings: layout, scene options and profile caches are stored in application/device storage. Theme files and app-launch requests can refer to local device content.
- Home handoff: the optional accessibility service observes relevant Home/window events to restore the launcher on firmware that ignores the normal Home preference. Enable it only if you want that behavior.

No device account cache, photo, ADB authorization key, signing key, local IP configuration or verification screenshot is bundled with this source repository. No project analytics backend is configured.

Removing an installed app can remove its private data. Keep your own recovery and signing material outside Git.
