# Ovra Dash

Turns an Android phone (built for the realme X2 Pro) into a dashcam and parking alarm.

*Coded by Pranav Tagadpallewar · an Ovra app*

## Driving
- Loop recording (1/3/5-minute clips, 1080p by default, oldest unsaved clips deleted past the storage limit - 30 GB by default)
- Road camera with a cabin-camera inset (when the phone allows both cameras at once)
- Date, time, speed and GPS position stamped on the video; GPS track saved next to each clip (.csv)
- Crash detection saves (locks) the current and previous clip; optional SOS text with your location after a big impact
- Starts when the car charger is plugged in, stops after unplugging, keeps recording with the screen off

## Parking guard (car alarm)
- Arms automatically after you park (or manually)
- Movement outside → records a saved clip
- Movement inside the car, bumps/knocks, or the phone being moved → records, loud siren and flashing light, optional alert text
- PIN or fingerprint needed to turn the guard off or silence the alarm

## Sentry mode
- A warning light for when the car is parked: black screen with a red light that double-flashes every 2 seconds, like a car alarm LED
- Its own button and settings, completely separate from security (works with security off)
- Switches on after parking (optional) and off on low battery

## Security
- PIN / fingerprint for clips, deleting/unsaving clips, settings and the guard
- Clips are private to the app (not in the gallery); export or share from the Clips screen

## Build
GitHub Actions builds the APK on every push to `main` and publishes it under Releases.
