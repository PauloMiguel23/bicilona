# Bicilona 🚲

Android app that helps you plan Bicing bike-sharing trips in Barcelona.

## Features
- Shows all Bicing stations on Google Maps (color-coded by availability)
- Given your GPS location + a destination, finds:
  - **Nearest pickup station** (with bikes available) to your location
  - **Nearest dropoff station** (with docks available) to your destination
- Displays a 3-step route: 🚶 walk → 🚲 ride → 🚶 walk
- Estimated times for each segment

## Setup

1. **Open in Android Studio** (Hedgehog or newer)
2. **Add your Google Maps API key** in `AndroidManifest.xml`:
   ```xml
   <meta-data
       android:name="com.google.android.geo.API_KEY"
       android:value="YOUR_ACTUAL_KEY_HERE" />
   ```
   You need the **Maps SDK for Android** enabled in your Google Cloud Console.
3. **Build and run** on a device/emulator

## How to use

- **Search**: Type an address in the search bar and press Enter
- **Long-press**: Long-press anywhere on the map to set it as destination
- The app will automatically find the best pickup/dropoff stations

## API

Uses the public GBFS (General Bikeshare Feed Specification) endpoint:
- Station info: `https://barcelona.publicbikesystem.net/customer/gbfs/v2/en/station_information.json`
- Station status: `https://barcelona.publicbikesystem.net/customer/gbfs/v2/en/station_status.json`

No API key needed for Bicing data.

## Architecture

```
com.bicilona/
├── data/
│   ├── api/          # Retrofit API interface + client
│   ├── model/        # Data classes (StationInfo, StationStatus, BicilonaStation, BicilonaRoute)
│   └── repository/   # BicilonaRepository (merges data, finds nearest stations)
├── ui/               # MainActivity + MainViewModel
└── util/             # LocationUtils (haversine distance, formatting)
```
