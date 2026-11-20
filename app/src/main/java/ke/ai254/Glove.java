package ke.ai254;

import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

final class Glove {
    private static final UUID SERVICE = UUID.fromString("2aa25400-37d1-4b3a-88cf-eb1ccc48c305");
    private static final UUID DATA = UUID.fromString("2aa25401-37d1-4b3a-88cf-eb1ccc48c305");
    private static final UUID CCC = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Consumer<String> status;
    private final Consumer<short[]> readings;
    private BluetoothLeScanner scanner;
    private BluetoothGatt connection;
    private boolean ready;

    Glove(Context context, Consumer<String> status, Consumer<short[]> readings) {
        this.context = context;
        this.status = status;
        this.readings = readings;
    }

    private final Runnable timeout = () -> fail("Glove connection timed out");
    private final ScanCallback scan = new ScanCallback() {
        @Override public void onScanResult(int kind, ScanResult result) {
            main.post(() -> {
                if (scanner == null) return;
                stopScan();
                status.accept("Connecting to glove");
                connection = result.getDevice().connectGatt(context, false, gatt, BluetoothDevice.TRANSPORT_LE);
                if (connection == null) fail("Could not connect");
            });
        }
        @Override public void onScanFailed(int code) { main.post(() -> fail("Glove scan failed: " + code)); }
    };

    private final BluetoothGattCallback gatt = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt link, int code, int state) {
            main.post(() -> {
                if (link != connection) return;
                if (code != BluetoothGatt.GATT_SUCCESS || state == BluetoothProfile.STATE_DISCONNECTED) {
                    fail("Glove disconnected");
                } else if (state == BluetoothProfile.STATE_CONNECTED && !link.discoverServices()) {
                    fail("Could not discover glove service");
                }
            });
        }
        @Override public void onServicesDiscovered(BluetoothGatt link, int code) {
            main.post(() -> {
                if (link != connection) return;
                BluetoothGattService service = link.getService(SERVICE);
                BluetoothGattCharacteristic stream = service == null ? null : service.getCharacteristic(DATA);
                BluetoothGattDescriptor descriptor = stream == null ? null : stream.getDescriptor(CCC);
                if (code != BluetoothGatt.GATT_SUCCESS || descriptor == null
                        || !link.setCharacteristicNotification(stream, true)
                        || link.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                            != BluetoothStatusCodes.SUCCESS) fail("Glove sensor stream unavailable");
            });
        }
        @Override public void onDescriptorWrite(BluetoothGatt link, BluetoothGattDescriptor descriptor, int code) {
            main.post(() -> {
                if (link != connection) return;
                if (code != BluetoothGatt.GATT_SUCCESS) {
                    fail("Could not subscribe to the glove");
                    return;
                }
                main.removeCallbacks(timeout);
                ready = true;
                status.accept("Glove connected");
            });
        }
        @Override public void onCharacteristicChanged(BluetoothGatt link, BluetoothGattCharacteristic characteristic, byte[] value) {
            if (!DATA.equals(characteristic.getUuid()) || value.length != 18) return;
            short[] sample = new short[9];
            ByteBuffer bytes = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < sample.length; i++) sample[i] = bytes.getShort();
            main.post(() -> { if (link == connection && ready) readings.accept(sample); });
        }
    };

    void start() {
        stop();
        BluetoothManager manager = context.getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        scanner = adapter == null ? null : adapter.getBluetoothLeScanner();
        if (scanner == null) {
            status.accept("Turn on Bluetooth to connect the glove");
            return;
        }
        status.accept("Looking for the glove");
        scanner.startScan(List.of(new ScanFilter.Builder().setServiceUuid(new ParcelUuid(SERVICE)).build()),
                new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scan);
        main.postDelayed(timeout, 15000);
    }

    private void stopScan() {
        if (scanner != null) {
            scanner.stopScan(scan);
            scanner = null;
        }
    }

    void stop() {
        main.removeCallbacks(timeout);
        stopScan();
        ready = false;
        if (connection != null) {
            connection.close();
            connection = null;
        }
    }

    private void fail(String message) {
        stop();
        status.accept(message);
    }
}
