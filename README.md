# Anime HD Player

An offline-first Android video player built with Kotlin and AndroidX Media3.

## Current milestone
- Local video selection using Android's system file picker
- Playback for formats supported by Media3/device decoders
- Player controls and audio/subtitle track selection
- No advertising SDKs and no internet permission

## Planned milestones
- Real-time brightness, contrast, saturation, and sharpness
- HD scaling presets (scaling does not create genuine source detail)
- Improved local library and subtitle workflows

## Build
Pushes to main trigger the GitHub Actions Android debug build. The APK is published as a workflow artifact.

This is an early development build, not a finished MX Player replacement.
