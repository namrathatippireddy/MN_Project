package org.c19x.sensor.ble;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;

import org.c19x.sensor.PayloadDataSupplier;
import org.c19x.sensor.SensorDelegate;
import org.c19x.sensor.data.ConcreteSensorLogger;
import org.c19x.sensor.data.SensorLogger;
import org.c19x.sensor.datatype.BluetoothState;
import org.c19x.sensor.datatype.Callback;
import org.c19x.sensor.datatype.Data;
import org.c19x.sensor.datatype.PayloadData;
import org.c19x.sensor.datatype.RSSI;
import org.c19x.sensor.datatype.TargetIdentifier;
import org.c19x.sensor.datatype.TimeInterval;
import org.c19x.sensor.datatype.Tuple;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class ConcreteBLEReceiver extends ScanCallback implements BLEReceiver, BluetoothStateManagerDelegate {
    // Scan ON/OFF durations
    private final static long scanOnDurationMillis = TimeInterval.seconds(8).millis();
    private final static long scanOffDurationMillis = TimeInterval.seconds(4).millis();
    private SensorLogger logger = new ConcreteSensorLogger("Sensor", "BLE.ConcreteBLEReceiver");
    private final Context context;
    private final BluetoothStateManager bluetoothStateManager;
    private final PayloadDataSupplier payloadDataSupplier;
    private final BLEDatabase database;
    private final BLETransmitter transmitter;
    private final Handler handler;
    private final ExecutorService operationQueue = Executors.newSingleThreadExecutor();
    private final Queue<ScanResult> scanResults = new ConcurrentLinkedQueue<>();
    private BluetoothLeScanner bluetoothLeScanner;
    private AtomicBoolean startScanLoop = new AtomicBoolean(false);

    /**
     * Receiver starts automatically when Bluetooth is enabled.
     */
    public ConcreteBLEReceiver(Context context, BluetoothStateManager bluetoothStateManager, PayloadDataSupplier payloadDataSupplier, BLEDatabase database, BLETransmitter transmitter) {
        this.context = context;
        this.bluetoothStateManager = bluetoothStateManager;
        this.payloadDataSupplier = payloadDataSupplier;
        this.database = database;
        this.transmitter = transmitter;
        this.handler = new Handler(Looper.getMainLooper());
        bluetoothStateManager.delegates.add(this);
        bluetoothStateManager(bluetoothStateManager.state());
    }

    // MARK:- BLEReceiver

    @Override
    public void add(SensorDelegate delegate) {
        delegates.add(delegate);
    }

    @Override
    public void start() {
        logger.debug("start");
        // startScanLoop is started by Bluetooth state
    }

    @Override
    public void stop() {
        logger.debug("stop");
        // startScanLoop is stopped by Bluetooth state
    }

    // MARK:- BluetoothStateManagerDelegate

    @Override
    public void bluetoothStateManager(BluetoothState didUpdateState) {
        logger.debug("didUpdateState (state={})", didUpdateState);
        if (didUpdateState == BluetoothState.poweredOn) {
            startScanLoop();
        }
    }

    // MARK:- Scan loop for startScan-wait-stopScan-processScanResults-wait-repeat

    private void startScanLoop() {
        logger.debug("startScanLoop (on={},off={})", scanOnDurationMillis, scanOffDurationMillis);
        final BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter == null) {
            logger.fault("startScanLoop denied, Bluetooth adapter unavailable");
            return;
        }
        final BluetoothLeScanner bluetoothLeScanner = bluetoothAdapter.getBluetoothLeScanner();
        if (bluetoothLeScanner == null) {
            logger.fault("startScanLoop denied, Bluetooth LE scanner unavailable");
            return;
        }
        if (bluetoothStateManager.state() != BluetoothState.poweredOn) {
            logger.fault("startScanLoop denied, Bluetooth is not powered on");
            return;
        }
        if (!startScanLoop.compareAndSet(false, true)) {
            logger.fault("startScanLoop denied, already started");
            return;
        }
        startScan(bluetoothLeScanner, new Callback<Boolean>() {
            @Override
            public void accept(Boolean value) {
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        stopScan(bluetoothLeScanner, new Callback<Boolean>() {
                            @Override
                            public void accept(Boolean value) {
                                handler.postDelayed(new Runnable() {
                                    @Override
                                    public void run() {
                                        startScanLoop.set(false);
                                        startScanLoop();
                                    }
                                }, scanOffDurationMillis);
                            }
                        });
                    }
                }, scanOnDurationMillis);
            }
        });
    }

    /// Get BLE scanner and start scan
    private void startScan(final BluetoothLeScanner bluetoothLeScanner, final Callback<Boolean> callback) {
        logger.debug("startScan");
        operationQueue.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    scanForPeripherals(bluetoothLeScanner);
                    logger.debug("startScan successful");
                    if (callback != null) {
                        callback.accept(true);
                    }
                } catch (Throwable e) {
                    logger.fault("startScan failed", e);
                    if (callback != null) {
                        callback.accept(false);
                    }
                }
            }
        });
    }


    /// Scan for devices advertising sensor service and all Apple devices as
    // iOS background advert does not include service UUID. There is a risk
    // that the sensor will spend time communicating with Apple devices that
    // are not running the sensor code repeatedly, but there is no reliable
    // way of filtering this as the service may be absent only because of
    // transient issues. This will be handled in taskConnect.
    private void scanForPeripherals(final BluetoothLeScanner bluetoothLeScanner) {
        logger.debug("scanForPeripherals");
        final List<ScanFilter> filter = new ArrayList<>(2);
        filter.add(new ScanFilter.Builder().setManufacturerData(
                BLESensorConfiguration.manufacturerIdForApple, new byte[0], new byte[0]).build());
        filter.add(new ScanFilter.Builder().setServiceUuid(
                new ParcelUuid(BLESensorConfiguration.serviceUUID),
                new ParcelUuid(new UUID(0xFFFFFFFFFFFFFFFFL, 0)))
                .build());
        final ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                .setReportDelay(0)
                .build();
        bluetoothLeScanner.startScan(filter, settings, this);
    }

    /// Get BLE scanner and stop scan
    private void stopScan(final BluetoothLeScanner bluetoothLeScanner, final Callback<Boolean> callback) {
        logger.debug("stopScan");
        final ScanCallback scanCallback = this;
        operationQueue.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    bluetoothLeScanner.stopScan(scanCallback);
                } catch (Throwable e) {
                    logger.fault("stopScan warning, bluetoothLeScanner.stopScan error", e);
                }
                try {
                    final BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
                    if (bluetoothAdapter != null) {
                        bluetoothAdapter.cancelDiscovery();
                    }
                } catch (Throwable e) {
                    logger.fault("stopScan warning, bluetoothAdapter.cancelDiscovery error", e);
                }
                try {
                    processScanResults();
                } catch (Throwable e) {
                    logger.fault("stopScan warning, processScanResults error", e);
                }
                logger.debug("stopScan successful");
                callback.accept(true);
            }
        });
    }


    // MARK:- ScanCallback

    /// Add scan result to scan results.
    @Override
    public void onScanResult(int callbackType, ScanResult result) {
        logger.debug("onScanResult (result={})", result);
        scanResults.add(result);
    }

    @Override
    public void onScanFailed(int errorCode) {
        logger.fault("onScanFailed (error={})", onScanFailedErrorCodeToString(errorCode));
    }


    /// Process scan results.
    private void processScanResults() {
        final long t0 = System.currentTimeMillis();
        logger.debug("processScanResults (results={})", scanResults.size());
        // Identify devices discovered in last scan
        final Set<BLEDevice> devices = didDiscover();
        // taskRegisterConnectedDevices(); // Unnecessary on Android
        // taskResolveDevicePeripherals(); // Unnecessary on Android
        taskRemoveExpiredDevices();
        // taskRemoveDuplicatePeripherals(); // Unnecessary on Android
//        taskConnect();
        final long t1 = System.currentTimeMillis();
//        taskWriteBack(devices);
        final long t2 = System.currentTimeMillis();
        logger.debug("processScanResults (results={},devices={},elapsed={}ms,read={}ms,write={}ms)", scanResults.size(), devices.size(), (t2 - t0), (t1 - t0), (t2 - t1));
    }

    /**
     * Process scan results to
     * 1. Create BLEDevice from scan result for new devices
     * 2. Read RSSI
     * 3. Identify operating system
     */
    private Set<BLEDevice> didDiscover() {
        // Take current copy of concurrently modifiable scan results
        final List<ScanResult> scanResultList = new ArrayList<>(scanResults.size());
        while (scanResults.size() > 0) {
            scanResultList.add(scanResults.poll());
        }

        // Process scan results and return devices created/updated in scan results
        logger.debug("didDiscover");
        final Set<BLEDevice> devices = new HashSet<>();
        for (ScanResult scanResult : scanResultList) {
            logger.debug("didDiscover, processing scan result (scanResult={})", scanResult);
            final BLEDevice device = database.device(scanResult.getDevice());
            device.lastDiscoveredAt = new Date();
            if (devices.add(device)) {
                logger.debug("didDiscover, device (device={})", device);
            }
            // Read RSSI from scan result
            device.rssi(new RSSI(scanResult.getRssi()));
            // Don't ignore devices forever just because
            // sensor service was not found at some point
            if (device.operatingSystem() == BLEDeviceOperatingSystem.ignore &&
                    device.timeIntervalSinceLastOperatingSystemUpdate().value > TimeInterval.minutes(2).value) {
                logger.debug("didDiscover, re-introducing ignored device (device={})", device);
                device.operatingSystem(BLEDeviceOperatingSystem.unknown);
            }
            // Identify operating system from scan record where possible
            // - Sensor service found + Manufacturer is Apple -> iOS (Foreground)
            // - Sensor service found + Manufacturer not Apple -> Android
            // - Sensor service not found + Manufacturer is Apple -> iOS (Background) or Apple device not advertising sensor service, to be resolved later
            // - Sensor service not found + Manufacturer not Apple -> Ignore (shouldn't be possible as we are scanning for Apple or with service)
            final boolean hasSensorService = hasSensorService(scanResult);
            final boolean isAppleDevice = isAppleDevice(scanResult);
            if (hasSensorService && isAppleDevice) {
                // Definitely iOS device offering sensor service in foreground mode
                device.operatingSystem(BLEDeviceOperatingSystem.ios);
            } else if (hasSensorService && !isAppleDevice) {
                // Definitely Android device offering sensor service
                device.operatingSystem(BLEDeviceOperatingSystem.android);
            } else if (!hasSensorService && isAppleDevice) {
                // Maybe iOS device offering sensor service in background mode,
                // can't be sure without additional checks after connection, so
                // only set operating system if it is unknown to offer a guess.
                if (device.operatingSystem() == BLEDeviceOperatingSystem.unknown) {
                    device.operatingSystem(BLEDeviceOperatingSystem.ios);
                }
            } else {
                // Sensor service not found + Manufacturer not Apple should be impossible (!hasSensorService && !isAppleDevice)
                // Shouldn't be possible as we are scanning for devices with sensor service or Apple device.
                logger.fault("didDiscover, invalid non-Apple device without sensor service (device={})", device);
                device.operatingSystem(BLEDeviceOperatingSystem.ignore);
            }
        }
        return devices;
    }

    /// Does scan result include advert for sensor service?
    private static boolean hasSensorService(final ScanResult scanResult) {
        final ScanRecord scanRecord = scanResult.getScanRecord();
        if (scanRecord == null) {
            return false;
        }
        final List<ParcelUuid> serviceUuids = scanRecord.getServiceUuids();
        if (serviceUuids == null || serviceUuids.size() == 0) {
            return false;
        }
        for (ParcelUuid serviceUuid : serviceUuids) {
            if (serviceUuid.getUuid().equals(BLESensorConfiguration.serviceUUID)) {
                return true;
            }
        }
        return false;
    }

    /// Does scan result indicate device was manufactured by Apple?
    private static boolean isAppleDevice(final ScanResult scanResult) {
        final ScanRecord scanRecord = scanResult.getScanRecord();
        if (scanRecord == null) {
            return false;
        }
        final byte[] data = scanRecord.getManufacturerSpecificData(BLESensorConfiguration.manufacturerIdForApple);
        return data != null;
    }

    /// Register connected devices to catch devices not found via discovery, e.g. connection initiated by peer
    private void taskRegisterConnectedDevices() {
        final BluetoothManager bluetoothManager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        final List<BluetoothDevice> bluetoothDevices = bluetoothManager.getConnectedDevices(BluetoothProfile.GATT);
        for (BluetoothDevice bluetoothDevice : bluetoothDevices) {
            if (bluetoothDevice.getType() == BluetoothDevice.DEVICE_TYPE_LE) {
                final TargetIdentifier identifier = new TargetIdentifier(bluetoothDevice);
                final BLEDevice device = database.device(identifier);
                if (device.peripheral() == null || device.peripheral() != bluetoothDevice) {
                    logger.debug("taskRegisterConnectedPeripherals (device={})", device);
                    database.device(bluetoothDevice);
                }
            }
        }
    }

    /// Resolve BluetoothDevice for all devices where possible
    private void taskResolveDevicePeripherals() {
        final BluetoothManager bluetoothManager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        final BluetoothAdapter bluetoothAdapter = bluetoothManager.getAdapter();
        for (BLEDevice device : database.devices()) {
            if (device.peripheral() != null) {
                continue;
            }
            final String address = device.identifier.value;
            if (!bluetoothAdapter.checkBluetoothAddress(address)) {
                continue;
            }
            try {
                final BluetoothDevice bluetoothDevice = bluetoothAdapter.getRemoteDevice(address);
                if (bluetoothDevice == null) {
                    continue;
                }
                logger.debug("taskResolveDevicePeripherals (resolved={})", device);
                device.peripheral(bluetoothDevice);
            } catch (Throwable e) {
            }
        }
    }

    /// Remove devices that have not been updated for over an hour, as the UUID
    // is likely to have changed after being out of range for over 20 minutes,
    // so it will require discovery.
    private void taskRemoveExpiredDevices() {
        final List<BLEDevice> devicesToRemove = new ArrayList<>();
        for (BLEDevice device : database.devices()) {
            if (device.timeIntervalSinceLastUpdate().value > TimeInterval.hour.value) {
                devicesToRemove.add(device);
            }
        }
        for (BLEDevice device : devicesToRemove) {
            logger.debug("taskRemoveExpiredDevices (remove={})", device);
            database.delete(device.identifier);
            logger.debug("disconnect (source=taskRemoveExpiredDevices,device={})", device);
            device.disconnect();
        }
    }

    /// Remove devices with the same payload data but different peripherals.
    private void taskRemoveDuplicatePeripherals() {
        final Map<PayloadData, BLEDevice> index = new HashMap<>();
        for (BLEDevice device : database.devices()) {
            if (device.payloadData() == null) {
                continue;
            }
            final BLEDevice duplicate = index.get(device.payloadData());
            if (duplicate == null) {
                index.put(device.payloadData(), device);
                continue;
            }
            BLEDevice keeping = device;
            if (device.peripheral() != null && duplicate.peripheral() == null) {
                keeping = device;
            } else if (duplicate.peripheral() != null && device.peripheral() == null) {
                keeping = duplicate;
            } else if (device.payloadDataLastUpdatedAt().getTime() > duplicate.payloadDataLastUpdatedAt().getTime()) {
                keeping = device;
            } else {
                keeping = duplicate;
            }
            final BLEDevice discarding = (keeping == device ? duplicate : device);
            index.put(keeping.payloadData(), keeping);
            database.delete(discarding.identifier);
            logger.debug("taskRemoveDuplicatePeripherals (payload={},device={},duplicate={},keeping={}",
                    keeping.payloadData().shortName(),
                    device, duplicate, keeping);
        }
    }

    private void taskConnect() {
        final Tuple<List<BLEDevice>, List<BLEDevice>> devicesSeparatedByConnectionState = taskConnectSeparateByConnectionState(database.devices());
        final List<BLEDevice> connected = devicesSeparatedByConnectionState.getA("connected");
        final List<BLEDevice> disconnected = devicesSeparatedByConnectionState.getB("disconnected");
        final List<BLEDevice> pending = taskConnectPendingDevices(disconnected);
        final int capacity = BLESensorConfiguration.concurrentConnectionQuota - connected.size();
//        final Tuple<Integer, List<BLEDevice>> requestConnectionCapacity = taskConnectRequestConnectionCapacity(connected, pending);
//        final int capacity = requestConnectionCapacity.getA("capacity");
//        final List<BLEDevice> keepConnected = requestConnectionCapacity.getB("keepConnected");
        taskConnectInitiateConnectionToPendingDevices(pending, capacity);
//        taskConnectRefreshKeepConnectedDevices(keepConnected: keepConnected)
    }

    /// Separate devices by current connection state
    private Tuple<List<BLEDevice>, List<BLEDevice>> taskConnectSeparateByConnectionState(final List<BLEDevice> devices) {
        final List<BLEDevice> connected = new ArrayList<>(devices.size());
        final List<BLEDevice> disconnected = new ArrayList<>(devices.size());
        for (BLEDevice device : devices) {
            if (device.peripheral() == null) {
                continue;
            }
            if (device.state() == BLEDeviceState.connected) {
                connected.add(device);
            } else if (device.state() == BLEDeviceState.disconnected) {
                disconnected.add(device);
            }
        }
        logger.debug("taskConnect status summary (connected={},disconnected={}})", connected.size(), disconnected.size());
        for (BLEDevice device : connected) {
            logger.debug("taskConnect status connected (device={},upTime={})", device, device.timeIntervalBetweenLastPayloadDataUpdateAndLastAdvert());
        }
        for (BLEDevice device : disconnected) {
            logger.debug("taskConnect status disconnected (device={},downTime={})", device, device.timeIntervalSinceLastDisconnectedAt());
        }
        return new Tuple<>("connected", connected, "disconnected", disconnected);
    }

    /// Establish pending connections for disconnected devices
    private List<BLEDevice> taskConnectPendingDevices(List<BLEDevice> disconnected) {
        final List<BLEDevice> pending = new ArrayList<>(disconnected.size());
        for (BLEDevice device : disconnected) {
            if (device.operatingSystem() == BLEDeviceOperatingSystem.ignore) {
                continue;
            }
            if (device.goal() == BLEDeviceGoal.rssi) {
                // RSSI acquired by scan, connect unnecessary
                continue;
            }
            pending.add(device);
        }
        Collections.sort(pending, new Comparator<BLEDevice>() {
            @Override
            public int compare(BLEDevice d0, BLEDevice d1) {
                return Long.compare(d1.timeIntervalSinceLastConnectRequestedAt().value, d0.timeIntervalSinceLastConnectRequestedAt().value);
            }
        });
        if (pending.size() > 0) {
            logger.debug("taskConnect pending summary (devices={})", pending.size());
            for (int i = 0; i < pending.size(); i++) {
                final BLEDevice device = pending.get(i);
                logger.debug("taskConnect pending, queue (priority={},device={},timeSinceLastRequest={}})", i + 1, device, device.timeIntervalSinceLastConnectRequestedAt());
            }
        }
        return pending;
    }

    /// Free connection capacity for pending devices if possible by disconnecting long running connections to iOS devices
    private Tuple<Integer, List<BLEDevice>> taskConnectRequestConnectionCapacity(List<BLEDevice> connected, List<BLEDevice> pending) {
        final int quota = BLESensorConfiguration.concurrentConnectionQuota;
        final int capacityRequest = 1;
        if (!(pending.size() > 0 && (capacityRequest + connected.size()) > quota)) {
            final int capacity = quota - connected.size();
            return new Tuple<>("capacity", capacity, "keepConnected", connected);
        }
        // All connections are transient in Android (unlike iOS)
        final List<BLEDevice> transientDevices = connected;
        logger.debug("taskConnect capacity summary (quota={},connected={},transient={},pending={})", quota, connected.size(), transientDevices.size(), pending.size());
        // Only disconnect devices if there is no transient device that will naturally free up capacity in the near future
        if (!(transientDevices.size() == 0)) {
            logger.debug("taskConnect capacity, wait for disconnection by transient devices");
            return new Tuple<>("capacity", 0, "keepConnected", connected);
        }
        // Candidate devices for disconnection
        // - Device has been tracked for > 1 minute (up time)
        // - Device has been connected for > 30 seconds
        // - Sort device by up time (longest first)
        final List<BLEDevice> candidates = new ArrayList<>(connected.size());
        for (BLEDevice device : connected) {
            if (device.timeIntervalBetweenLastPayloadDataUpdateAndLastAdvert().value > TimeInterval.minute.value &&
                    device.timeIntervalSinceLastConnectedAt().value > TimeInterval.seconds(30).value) {
                candidates.add(device);
            }
        }
        Collections.sort(candidates, new Comparator<BLEDevice>() {
            @Override
            public int compare(BLEDevice d0, BLEDevice d1) {
                return Long.compare(d1.timeIntervalBetweenLastPayloadDataUpdateAndLastAdvert().value, d0.timeIntervalBetweenLastPayloadDataUpdateAndLastAdvert().value);
            }
        });
        final List<String> candidateList = new ArrayList<>(candidates.size());
        for (BLEDevice device : candidates) {
            candidateList.add(device + ":" + device.timeIntervalBetweenLastPayloadDataUpdateAndLastAdvert());
        }
        logger.debug("taskConnect capacity, candidates (devices={})", candidateList);
        // Disconnect devices to meet capacity request
        final List<BLEDevice> keepConnected = new ArrayList<>(candidates.size());
        final List<BLEDevice> willDisconnect = new ArrayList<>(candidates.size());
        for (int i = 0; i < candidates.size(); i++) {
            if (i < capacityRequest) {
                willDisconnect.add(candidates.get(i));
            } else {
                keepConnected.add(candidates.get(i));
            }
        }
        logger.debug("taskConnect capacity, plan (willDisconnect={},,keepConnected={})", willDisconnect.size(), keepConnected.size());
        for (BLEDevice device : willDisconnect) {
            if (device.gatt != null) {
                logger.debug("taskConnect capacity, disconnect (device={})", device);
                device.disconnect();
            }
        }
        return new Tuple<>("capacity", willDisconnect.size(), "keepConnected", keepConnected);
    }

    /// Initiate connection to pending devices, up to maximum capacity
    private void taskConnectInitiateConnectionToPendingDevices(List<BLEDevice> pending, int capacity) {
        if (pending.size() == 0) {
            return;
        }
        if (capacity == 0) {
            return;
        }
        final List<BLEDevice> readyForConnection = new ArrayList<>();
        for (BLEDevice device : pending) {
            if (device.peripheral() != null && readyForConnection.size() < capacity) {
                readyForConnection.add(device);
            }
        }
        logger.debug("taskConnect initiate connection summary (pending={},capacity={},connectingTo={})", pending.size(), capacity, readyForConnection.size());
        if (readyForConnection.size() == 0) {
            return;
        }
        for (final BLEDevice device : readyForConnection) {
            try {
                boolean successful = true;
                logger.debug("taskConnect initiate connection, connect (device={})", device);
                if (device.goal() == BLEDeviceGoal.payloadSharing) {
                    successful = readPayloadSharing(device);
                } else {
                    successful = readPayload(device);
                }
                if (successful) {
                    logger.debug("taskConnect initiate connection, successful (device={})", device);
                } else {
                    logger.fault("taskConnect initiate connection, failed (device={})", device);
                }
            } catch (Throwable e) {
                logger.fault("taskConnect initiate connection, error (device={})", device, e);
            }
        }
    }

    /**
     * Write RSSI and payload data to central via signal characteristic if this device cannot transmit.
     */
    private void taskWriteBack(final Set<BLEDevice> devices) {
        if (transmitter.isSupported()) {
            return;
        }
        // Establish capacity
        final Tuple<List<BLEDevice>, List<BLEDevice>> devicesSeparatedByConnectionState = taskConnectSeparateByConnectionState(database.devices());
        final List<BLEDevice> connected = devicesSeparatedByConnectionState.getA("connected");
        final List<BLEDevice> disconnected = devicesSeparatedByConnectionState.getB("disconnected");
        final int capacity = BLESensorConfiguration.concurrentConnectionQuota - connected.size();
        logger.debug("taskWriteBack (transmitter={},devices={},capacity={})", transmitter.isSupported(), devices.size(), capacity);

        // Prioritise devices to write payload (least recent first)
        // - RSSI data is always fresh enough given the devices are passed in from taskProcessScanResults
        final List<BLEDevice> pending = new ArrayList<>(devices.size());
        for (BLEDevice device : devices) {
            if (device.operatingSystem() == BLEDeviceOperatingSystem.ios || device.operatingSystem() == BLEDeviceOperatingSystem.android) {
                pending.add(device);
            }
        }
        Collections.sort(pending, new Comparator<BLEDevice>() {
            @Override
            public int compare(BLEDevice d0, BLEDevice d1) {
                return Long.compare(d1.timeIntervalSinceLastWriteBack().value, d0.timeIntervalSinceLastWriteBack().value);
            }
        });
        // Initiate write back
        final List<String> pendingQueue = new ArrayList<>();
        for (BLEDevice device : pending) {
            pendingQueue.add(device.operatingSystem() + ":" + device.timeIntervalSinceLastWriteBack().value);
        }
        final List<BLEDevice> readyForConnection = new ArrayList<>();
        for (BLEDevice device : pending) {
            if (device.peripheral() != null && readyForConnection.size() < capacity) {
                readyForConnection.add(device);
            }
        }
        logger.debug("taskWriteBack summary (capacity={},pending={},queue={},connectingTo={})", capacity, pending.size(), pendingQueue, readyForConnection);
        if (readyForConnection.size() == 0) {
            return;
        }
        for (final BLEDevice device : readyForConnection) {
            try {
                logger.debug("taskWriteBack request (device={})", device);
                writeBack(device);
            } catch (Throwable e) {
                logger.fault("taskWriteBack request, error (device={})", device, e);
            }
        }
    }

    private boolean readPayload(final BLEDevice device) {
        logger.debug("readPayload (device={})", device);
        return connect(device, "readPayload", BLESensorConfiguration.payloadCharacteristicUUID, new Callback<byte[]>() {
            @Override
            public void accept(byte[] value) {
                final PayloadData payloadData = new PayloadData(value);
                device.payloadData(payloadData);
            }
        }, null);
    }

    private boolean readPayloadSharing(final BLEDevice device) {
        logger.debug("readPayloadSharing (device={})", device);
        return connect(device, "readPayloadSharing", BLESensorConfiguration.payloadSharingCharacteristicUUID, new Callback<byte[]>() {
            @Override
            public void accept(byte[] value) {
                final List<PayloadData> payloadSharingData = payloadDataSupplier.payload(new Data(value));
                device.payloadSharingData(payloadSharingData);
            }
        }, null);
    }

    private boolean writeBack(final BLEDevice device) {
        logger.debug("writePayload (device={})", device);
        // Write payload not possible for unknown or ignore devices
        if (device.operatingSystem() == BLEDeviceOperatingSystem.unknown) {
            logger.fault("writePayload denied, unknown operating system (device={})", device);
            return false;
        }
        if (device.operatingSystem() == BLEDeviceOperatingSystem.ignore) {
            logger.fault("writePayload denied, ignore device (device={})", device);
            return false;
        }
        // Establish signal characteristic based on operating system
        final UUID characteristicUUID =
                (device.operatingSystem() == BLEDeviceOperatingSystem.ios ?
                        BLESensorConfiguration.iosSignalCharacteristicUUID :
                        BLESensorConfiguration.androidSignalCharacteristicUUID);
        // Priority
        // 1. Write payload
        // 2. Write RSSI
        // 3. Write payload sharing
        if (device.payloadData() != null && (device.lastWritePayloadAt == null || device.timeIntervalSinceLastWritePayload().value > TimeInterval.hour.value)) {
            final byte[] data = signalData(BLESensorConfiguration.signalCharacteristicActionWritePayload, transmitter.payloadData().value);
            device.writePayload(connect(device, "writePayload", characteristicUUID, null, data));
        } else if (transmitter instanceof ConcreteBLETransmitter && (device.lastWritePayloadSharingAt == null || device.timeIntervalSinceLastWritePayloadSharing().value > BLESensorConfiguration.payloadSharingTimeInterval.value)) {
            final ConcreteBLETransmitter.PayloadSharingData payloadSharingData = ((ConcreteBLETransmitter) transmitter).payloadSharingData(device);
            final byte[] data = signalData(BLESensorConfiguration.signalCharacteristicActionWritePayloadSharing, payloadSharingData.data.value);
            device.writePayloadSharing(connect(device, "writePayloadSharing", characteristicUUID, null, data));
        } else if (device.rssi() != null) {
            final byte[] data = signalData(BLESensorConfiguration.signalCharacteristicActionWriteRSSI, device.rssi().value);
            device.writeRssi(connect(device, "writeRSSI", characteristicUUID, null, data));
        }
        return true;
    }

    private static byte[] signalData(final byte actionCode, final byte[] data) {
        return signalData(actionCode, data.length, data);
    }

    private static byte[] signalData(final byte actionCode, final int shortValue) {
        return signalData(actionCode, shortValue, null);
    }

    /// Create data bundle for writing to signal characteristic
    private static byte[] signalData(final byte actionCode, final int shortValue, final byte[] data) {
        final ByteBuffer byteBuffer = ByteBuffer.allocate(3 + (data == null ? 0 : data.length));
        byteBuffer.order(ByteOrder.LITTLE_ENDIAN);
        byteBuffer.put(0, actionCode);
        byteBuffer.putShort(1, Integer.valueOf(shortValue).shortValue());
        if (data != null) {
            byteBuffer.position(3);
            byteBuffer.put(data);
        }
        return byteBuffer.array();
    }

    // MARK:- BLEDevice connection

    /// Connect device and perform read/write operation on characteristic
    private boolean connect(final BLEDevice device, final String task, final UUID characteristicUUID, final Callback<byte[]> readData, final byte[] writeData) {
        device.lastConnectRequestedAt = new Date();
        if (device.peripheral() == null) {
            return false;
        }
        if (readData != null && writeData != null) {
            logger.fault("task {} denied, cannot read and write at the same time (device={})", task, device);
            return false;
        }
        final AtomicBoolean success = new AtomicBoolean(false);
        final CountDownLatch blocking = new CountDownLatch(1);
        final Callback<String> disconnect = new Callback<String>() {
            @Override
            public void accept(String source) {
                logger.debug("task {}, disconnect (source={},device={})", task, source, device);
                final BluetoothGatt gatt = device.gatt;
                if (gatt != null) {
                    try {
                        device.state(BLEDeviceState.disconnecting);
                        gatt.disconnect();
                    } catch (Throwable e) {
                        logger.fault("task {}, disconnect failed (device={},error={})", task, device, e);
                    }
                    try {
                        gatt.close();
                    } catch (Throwable e) {
                        logger.fault("task {}, close failed (device={},error={})", task, device, e);
                    }
                    device.gatt = null;
                    device.lastDisconnectedAt(new Date());
                }
                device.lastDisconnectedAt(new Date());
                device.state(BLEDeviceState.disconnected);
                logger.debug("task {}, disconnected (source={},device={})", task, source, device);
                blocking.countDown();
            }
        };
        final BluetoothGattCallback callback = new BluetoothGattCallback() {
            @Override
            public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
                logger.debug("task {}, onConnectionStateChange (device={},status={},state={})", task, device, status, onConnectionStatusChangeStateToString(newState));
                switch (newState) {
                    case BluetoothProfile.STATE_CONNECTING: {
                        device.state(BLEDeviceState.connecting);
                        break;
                    }
                    case BluetoothProfile.STATE_CONNECTED: {
                        logger.debug("task {}, didConnect (device={})", task, device);
                        device.state(BLEDeviceState.connected);
                        device.lastConnectedAt(new Date());
                        device.gatt = gatt;
                        gatt.discoverServices();
                        break;
                    }
                    case BluetoothProfile.STATE_DISCONNECTING: {
                        device.state(BLEDeviceState.disconnecting);
                        break;
                    }
                    case BluetoothProfile.STATE_DISCONNECTED: {
                        device.gatt = null;
                        device.lastDisconnectedAt(new Date());
                        if (status != 0) {
                            device.operatingSystem(BLEDeviceOperatingSystem.ignore);
                        }
                        disconnect.accept("onConnectionStateChange");
                        break;
                    }
                }
            }

            @Override
            public void onServicesDiscovered(BluetoothGatt gatt, int status) {
                final BluetoothGattService service = gatt.getService(BLESensorConfiguration.serviceUUID);
                if (service == null) {
                    logger.fault("task {}, onServicesDiscovered, service not found (device={})", task, device);
                    device.operatingSystem(BLEDeviceOperatingSystem.ignore);
                    disconnect.accept("onServicesDiscovered|serviceNotFound");
                    return;
                }
                logger.debug("task {}, onServicesDiscovered, service found (device={})", task, device);
                for (BluetoothGattCharacteristic characteristic : service.getCharacteristics()) {
                    if (characteristic.getUuid().equals(BLESensorConfiguration.androidSignalCharacteristicUUID)) {
                        device.operatingSystem(BLEDeviceOperatingSystem.android);
                        logger.debug("task {}, onServicesDiscovered, found Android signal characteristic (device={})", task, device);
                    } else if (characteristic.getUuid().equals(BLESensorConfiguration.iosSignalCharacteristicUUID)) {
                        device.operatingSystem(BLEDeviceOperatingSystem.ios);
                        logger.debug("task {}, onServicesDiscovered, found iOS signal characteristic (device={})", task, device);
                    }
                }
                final BluetoothGattCharacteristic characteristic = service.getCharacteristic(characteristicUUID);
                if (characteristic != null) {
                    if (readData != null) {
                        logger.debug("task {}, readCharacteristic (device={})", task, device);
                        if (!gatt.readCharacteristic(characteristic)) {
                            disconnect.accept("onServicesDiscovered|readCharacteristicFailed");
                        }
                    } else if (writeData != null) {
                        logger.debug("task {}, writeCharacteristic (device={})", task, device);
                        characteristic.setValue(writeData);
                        characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                        if (!gatt.writeCharacteristic(characteristic)) {
                            disconnect.accept("onServicesDiscovered|writeCharacteristicFailed");
                        }
                    } else {
                        // This should not be possible as read=null and write=null is check earlier
                        disconnect.accept("onServicesDiscovered|noReadNorWrite");
                    }
                } else {
                    disconnect.accept("onServicesDiscovered|characteristicNotFound");
                }
            }

            @Override
            public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
                logger.debug("task {}, onCharacteristicRead (device={},success={},value={})",
                        task, device,
                        (status == BluetoothGatt.GATT_SUCCESS),
                        (characteristic.getValue() == null ? "NULL" : characteristic.getValue().length));
                if (status == BluetoothGatt.GATT_SUCCESS && characteristic.getValue() != null) {
                    readData.accept(characteristic.getValue());
                    success.set(true);
                }
                disconnect.accept("onCharacteristicRead");
            }

            @Override
            public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
                logger.debug("task {}, onCharacteristicWrite (device={},success={})", task, device, (status == BluetoothGatt.GATT_SUCCESS));
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    success.set(true);
                }
                disconnect.accept("onCharacteristicWrite");
            }
        };
        logger.debug("task {}, connect (device={})", task, device);
        device.state(BLEDeviceState.connecting);
        try {
            device.gatt = device.peripheral().connectGatt(context, false, callback);
            if (device.gatt == null) {
                disconnect.accept("connect|noGatt");
            } else {
                try {
                    blocking.await(10, TimeUnit.SECONDS);
                } catch (Throwable e) {
                    logger.debug("task {}, timeout (device={})", task, device);
                    disconnect.accept("connect|timeout");
                }
            }
        } catch (Throwable e) {
            logger.fault("task {}, connect failed (device={},error={})", device, e);
            disconnect.accept("connect|noGatt");
        }
        return success.get();
    }

    // MARK:- Bluetooth code transformers

    private static String onCharacteristicWriteStatusToString(final int status) {
        switch (status) {
            case BluetoothGatt.GATT_SUCCESS:
                return "GATT_SUCCESS";
            case BluetoothGatt.GATT_CONNECTION_CONGESTED:
                return "GATT_CONNECTION_CONGESTED";
            case BluetoothGatt.GATT_FAILURE:
                return "GATT_FAILURE";
            case BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION:
                return "GATT_INSUFFICIENT_AUTHENTICATION";
            case BluetoothGatt.GATT_INSUFFICIENT_ENCRYPTION:
                return "GATT_INSUFFICIENT_ENCRYPTION";
            case BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH:
                return "GATT_INVALID_ATTRIBUTE_LENGTH";
            case BluetoothGatt.GATT_INVALID_OFFSET:
                return "GATT_INVALID_OFFSET";
            case BluetoothGatt.GATT_READ_NOT_PERMITTED:
                return "GATT_READ_NOT_PERMITTED";
            case BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED:
                return "GATT_REQUEST_NOT_SUPPORTED";
            case BluetoothGatt.GATT_SERVER:
                return "GATT_SERVER";
            case BluetoothGatt.GATT_WRITE_NOT_PERMITTED:
                return "GATT_WRITE_NOT_PERMITTED";
            default:
                return "UNKNOWN_STATUS_" + status;
        }
    }

    private static String onScanFailedErrorCodeToString(final int errorCode) {
        switch (errorCode) {
            case ScanCallback.SCAN_FAILED_ALREADY_STARTED:
                return "SCAN_FAILED_ALREADY_STARTED";
            case ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED:
                return "SCAN_FAILED_APPLICATION_REGISTRATION_FAILED";
            case ScanCallback.SCAN_FAILED_INTERNAL_ERROR:
                return "SCAN_FAILED_INTERNAL_ERROR";
            case ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED:
                return "SCAN_FAILED_FEATURE_UNSUPPORTED";
            default:
                return "UNKNOWN_ERROR_CODE_" + errorCode;
        }
    }

    private static String onConnectionStatusChangeStateToString(final int state) {
        switch (state) {
            case BluetoothProfile.STATE_CONNECTED:
                return "STATE_CONNECTED";
            case BluetoothProfile.STATE_DISCONNECTED:
                return "STATE_DISCONNECTED";
            default:
                return "UNKNOWN_STATE_" + state;
        }
    }

}
