# AI254 for Deaf and hard-of-hearing students

This reference build contains an Android app, firmware for one sensor glove,
and a way to collect recordings and train a small Kenyan Sign Language (KSL)
classifier. It has no internet permission. Active lessons and sensor frames
are held in memory. Imported models and labels stay in the app's private files.
The separate recorder writes data only when explicitly run on a training computer.

## Build the Android app

1. Open this directory in Android Studio using JDK 17 or 21. Install Android
   SDK 35, the NDK and CMake 3.22.1 from SDK Manager. The project uses Android
   Gradle Plugin 8.7.3 with Gradle 8.9. Set up a local Gradle 8.9 installation
   or run `gradle wrapper --gradle-version 8.9` with Gradle 8.9.
2. Build and run the app on an Android 13 or newer ARM64 phone. The initial
   build needs internet to download Gradle dependencies and whisper.cpp 1.7.6.
3. Download a multilingual whisper.cpp model from the
   [official model list](https://github.com/ggml-org/whisper.cpp/blob/master/models/README.md).
   Start with Small or a quantized Small variant. Use **Import Whisper model**
   to copy its .bin file onto the phone. The stock model is a baseline.
   Import your fine-tuned, whisper.cpp-compatible export when you have one.
4. Install an Android text-to-speech voice with offline English data. Verify
   it works in airplane mode before testing glove speech.
5. Set the caption language, press **Start captions**, and grant microphone
   permission. Caption inference starts after four seconds of audio. Measure
   the delay on the actual phone. If inference regularly takes more than four
   seconds, audio will eventually fall behind or be lost. Code-switching within
   a chunk is not separately tuned or validated by this reference build.

## Build the reference glove

Use a classic ESP32 development board, an MPU-6050 accelerometer breakout
with 3.3 V-compatible I2C, five flex sensors and five 47 kΩ resistors as
starting values. This pin map is for the classic ESP32, not other variants.
Use USB power on the bench, disconnect power before changing connections, and
verify the breakout's power and logic levels before connecting it.

| Part | ESP32 connection |
| --- | --- |
| Thumb, index, middle, ring, little voltage divider outputs | GPIO 32, 33, 34, 35, 36 |
| MPU-6050 SDA, SCL | GPIO 21, 22 |
| MPU-6050 address | 0x68 (AD0 low) |
| Ground | Shared GND |

Wire each flex sensor between regulated 3.3 V and its ADC input. Wire its
fixed resistor from that ADC input to GND. Use the same circuit and sensor
placement for training and inference. Load the sketch with Arduino IDE and
the Espressif ESP32 boards package 2.0.17, selecting **ESP32 Dev Module**.
Check for comma-separated readings in the serial monitor at 115200 baud before
connecting the phone. The reference glove advertises unencrypted BLE sensor
notifications. Use it on a controlled bench. Add authenticated pairing and
encrypted characteristics before a field deployment.

## Train the sign classifier

Use Python 3.11 on the training computer. Choose a small classroom vocabulary
with KSL users and review the exact spoken labels with them. Collect repeated
examples from multiple consenting signers, including resting hands, unrelated
gestures and near matches labelled `_unknown`. The glove captures five finger
bends and acceleration on three axes. It does not capture facial expressions,
other hand movements or all the information needed for general KSL translation.

```sh
python -m venv .venv
source .venv/bin/activate
pip install -r training/requirements.txt
mkdir -p samples
python training/record.py /dev/ttyUSB0 signer01 "_unknown" samples/window001.csv
python training/train.py samples export --held-out signer05
```

Run the recorder once per take with a unique filename. Match how you prompt
signers in training to the two-second **Read one sign** window used in the app.
Keep the consent records apart from the sensor data. Supply repeated takes for
every label from both training and held-out signers. The script prints per-label
results on held-out signers and exports the classifier and its label file. The
0.85 threshold is a starting value, not a measured guarantee. Evaluate false
accepts for unknown signs and false rejects for supported signs before use.
Import both exported files into the app whenever you update the vocabulary.

## Test the classroom exchange

1. Turn on airplane mode, then manually turn Bluetooth back on. Keep Wi-Fi
   and mobile data off. Connect the glove and start captions.
2. Confirm that reading one sign without model files gives a clear message.
   After importing a trained model, tap **Read one sign** and produce a supported
   sign. Check the phrase on screen before tapping **Speak recognised phrase**.
3. Have the lecturer respond. Check whether the reply reaches the caption
   view in time to follow the conversation. Log caption delay, missed phrases,
   incorrect signs and the phone model. The microphone may also caption the
   phone's own spoken phrase.
4. Disconnect the glove mid-window and test unknown signs. Verify that the
   app doesn't speak unless the student confirms the displayed phrase. Stop the
   caption session and verify the display clears.

## Data format for contributors

The BLE payload is 18 bytes, little-endian: a 16-bit sequence number,
five 12-bit flex readings stored as 16-bit integers, then three signed 16-bit
acceleration readings in MPU-6050 ±2g units. Frames arrive at about 20 Hz. The
classifier takes 40 consecutive frames as float32 with shape [1,40,8]. Divide
the flex values by 4095 and the acceleration values by 16384. The output is a
float32 probability vector whose indices match labels.txt, starting with
`_unknown`. The serial output omits sequence numbers and is for recording only.
