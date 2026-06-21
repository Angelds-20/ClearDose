# ClearDose

ClearDose is an Android application designed to calculate the exact amount of water and detergent needed to wash a vehicle. The app uses two methods to estimate vehicle dimensions and dirt levels: a 3D scan using ARCore, or a photo analysis via the Google Gemini API.

## Features

- **AR Measurement:** Scan and mark physical vertices of the vehicle using ARCore to calculate the total surface area.
- **AI Estimation:** Send a photo to the Gemini API (`gemini-2.5-flash`) to detect the vehicle type, fetch its standard dimensions, and assess the dirtiness level (Low, Medium, High).
- **Dosage Calculator:** Computes water and soap requirements based on the vehicle area and wash method (pressure washer, bucket, or hose).
- **Water Savings History:** Compares the dosage against a standard 150-liter wash to track and display total water saved over time.
- **Custom UI:** Modern dark theme built with Jetpack Compose, featuring custom OpenGL rendering, interactive Canvas graphics, and neon accents.

## Tech Stack

- **Language:** Kotlin
- **UI:** Jetpack Compose & Material 3
- **AR & Rendering:** ARCore, GLSurfaceView, OpenGL ES
- **Networking:** OkHttp3 & Gson
- **AI:** Google Gemini API

## Getting Started

1. Clone this repository:
   ```bash
   git clone https://github.com/Angelds-20/ClearDose.git
   ```
2. Open the project in Android Studio (Jellyfish or newer).
3. Build and run the app on an ARCore-supported device.
4. To use the photo-based estimation, get a free API key from [Google AI Studio](https://aistudio.google.com/) and enter it in the **Profile** screen.
