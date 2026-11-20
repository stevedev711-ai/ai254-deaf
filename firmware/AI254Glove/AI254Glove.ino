#include <Arduino.h>
#include <Wire.h>
#include <BLEDevice.h>
#include <BLE2902.h>
#include <atomic>

constexpr int FLEX_PINS[] = {32, 33, 34, 35, 36};
constexpr int MPU = 0x68;
constexpr char SERVICE[] = "2aa25400-37d1-4b3a-88cf-eb1ccc48c305";
constexpr char DATA[] = "2aa25401-37d1-4b3a-88cf-eb1ccc48c305";
BLECharacteristic *stream;
std::atomic<bool> connected(false);
std::atomic<bool> advertiseAgain(false);
uint16_t sequence = 0;
uint32_t nextSample = 0;

class Connection : public BLEServerCallbacks {
  void onConnect(BLEServer *) override { connected = true; }
  void onDisconnect(BLEServer *) override {
    connected = false;
    advertiseAgain = true;
  }
};

bool readAcceleration(int16_t *axis) {
  Wire.beginTransmission(MPU);
  Wire.write(0x3B);
  if (Wire.endTransmission(false) != 0 || Wire.requestFrom(static_cast<uint8_t>(MPU), static_cast<uint8_t>(6)) != 6) return false;
  for (int i = 0; i < 3; i++) {
    int high = Wire.read();
    int low = Wire.read();
    axis[i] = static_cast<int16_t>((high << 8) | low);
  }
  return true;
}

void setup() {
  Serial.begin(115200);
  Wire.begin(21, 22);
  Wire.beginTransmission(MPU);
  Wire.write(0x6B);
  Wire.write(0x00);
  if (Wire.endTransmission() != 0) {
    Serial.println("MPU-6050 not found; check I2C wiring and address.");
    while (true) delay(1000);
  }
  analogReadResolution(12);
  BLEDevice::init("AI254 Glove");
  BLEServer *server = BLEDevice::createServer();
  server->setCallbacks(new Connection());
  BLEService *service = server->createService(SERVICE);
  stream = service->createCharacteristic(DATA, BLECharacteristic::PROPERTY_NOTIFY);
  stream->addDescriptor(new BLE2902());
  service->start();
  BLEAdvertising *advertising = BLEDevice::getAdvertising();
  advertising->addServiceUUID(SERVICE);
  advertising->setScanResponse(true);
  advertising->start();
}

void loop() {
  if (advertiseAgain.exchange(false)) BLEDevice::startAdvertising();
  if (static_cast<int32_t>(millis() - nextSample) < 0) {
    delay(1);
    return;
  }
  nextSample = millis() + 50;
  uint16_t values[9];
  values[0] = sequence++;
  for (int i = 0; i < 5; i++) values[i + 1] = analogRead(FLEX_PINS[i]);
  int16_t axis[3];
  if (!readAcceleration(axis)) return;
  for (int i = 0; i < 3; i++) values[i + 6] = static_cast<uint16_t>(axis[i]);
  uint8_t packet[18];
  for (int i = 0; i < 9; i++) {
    packet[2 * i] = values[i] & 0xff;
    packet[2 * i + 1] = values[i] >> 8;
  }
  if (connected) {
    stream->setValue(packet, sizeof(packet));
    stream->notify();
  }
  for (int i = 1; i < 9; i++) {
    if (i > 1) Serial.print(',');
    Serial.print(i < 6 ? values[i] : static_cast<int16_t>(values[i]));
  }
  Serial.println();
}
