# Nintendo Switch Joy-Con (R) IR Live Camera for Android

Eine native Android-App (Kotlin, Jetpack Compose, Material 3) zum Auslesen und Anzeigen des Live-Videostreams der **Near-Infrared (IR) Motion Camera** eines Nintendo Switch Joy-Con (R) über Bluetooth.

![Joy-Con IR Camera Stream](https://user-images.githubusercontent.com/placeholder.png)

---

## Features

- 📹 **Echter Live-Stream**: Direkte Kommunikation mit dem internen STMicroelectronics MCU / OmniVision NIR Sensor des Joy-Con (R) über HID Report `0x31`.
- 🎨 **Farbpaletten**:
  - **Wärmebild (Ironbow)**: Pseudothermische Farbverläufe (Lila -> Rot -> Gelb -> Weiß).
  - **Nachtsicht**: Klassischer grüner Nachtsicht-Look.
  - **Graustufen**: Echte 8-Bit Grayscale-Sensordaten.
  - **Infrarot**: Monochromes Rotlicht.
- 📐 **Dynamische Auflösungen**:
  - `30x40`: Flüssigster Video-Stream (~15 FPS, 4 Fragmente/Frame)
  - `60x80`: Standardmodus (~4 FPS, 16 Fragmente/Frame)
  - `120x160`: Höhere Detailstufe (~1 FPS, 64 Fragmente/Frame)
  - `240x320`: Maximale Sensor-Auflösung (~0.25 FPS / HQ-Foto)
- ✨ **Bilineare Bild-Glättung (Anti-Aliasing)**:
  - Per Switch umschaltbar zwischen weicher Subpixel-Interpolation und scharfem Retro-Pixel-Look.
- 📸 **Schnappschuss-Funktion**:
  - Speichert das aktuelle Bild mit einem Klick in der Android-Galerie (`Pictures/JoyConCam`).
- ⚡ **Kamerasteuerung & Tuning**:
  - Belichtungszeit (Exposure in µs)
  - Digital Gain
  - IR-LEDs (Schmal / Weit / Beide)

---

## Voraussetzungen

1. **Nintendo Switch Joy-Con (R)** (Rechter Joy-Con mit IR-Kamera an der Unterseite).
2. **Android-Smartphone** mit:
   - Android 8.0+ (getestet auf Android 14)
   - **Root-Zugriff** (z. B. Magisk / KernelSU / APatch), da Linux/Android standardmäßig den Zugriff von normalen Apps auf `/dev/hidraw*` blockiert.

---

## Technische Details & Protokoll

Der Joy-Con (R) kommuniziert über Bluetooth HID:
1. **Input Report Mode 0x31**: Aktiviert erweiterte IMU- und MCU-Datenpakete.
2. **MCU Initialisierung (Subcmd 0x22, 0x01)**: Weckt den STM32 Mikrocontroller auf.
3. **MCU Modus IR (0x21, 0x00, 0x05)**: Versetzt die MCU in den Infrarot-Sensor-Modus.
4. **Image Transfer Mode (0x23, 0x01, 0x07)**: Schaltet den Sensor in den Bildübertragungsmodus (300 Bytes Nutzlast pro Fragment).
5. **Register-Konfiguration**: Setzt Belichtung, Sensor-Binning, Skipping, Gain, Denoise und LED-Strobe.
6. **Streaming & ACK-Loop**: Für jedes empfangene Fragment muss der Controller über Output Report `0x11` (Subcommand `0x03`) bestätigt werden, um das nächste Fragment abzurufen.

---

## Installation & Build

```bash
# Repository klonen
git clone https://github.com/kyropytis/nintendo-switch-joycon-live-camera-.git
cd nintendo-switch-joycon-live-camera-

# Debug APK bauen
./gradlew assembleDebug

# Auf verbundenem Smartphone installieren
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## Lizenz & Danksagung

- Inspiriert durch das Reverse-Engineering von CTCaer (`jc_toolkit`) und dekuNukem (`Nintendo_Switch_Reverse_Engineering`).
- Lizenziert unter der MIT-Lizenz.
