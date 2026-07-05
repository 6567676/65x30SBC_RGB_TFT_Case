package com.example.myapp;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;


@SuppressLint("MissingPermission")
public class MainActivity extends AppCompatActivity {

    private static final String DEVICE_NAME = "zero box";
    private static final UUID SVC_UUID  = UUID.fromString("0000ff01-0000-1000-8000-00805f9b34fb");
    private static final UUID CMD_UUID  = UUID.fromString("0000ff02-0000-1000-8000-00805f9b34fb");
    private static final UUID DATA_UUID = UUID.fromString("0000ff03-0000-1000-8000-00805f9b34fb");

    private static final int SCREEN_W = 320;
    private static final int SCREEN_H = 172;
    private static final long THROTTLE_MS = 100;
    private static final int MAX_VIDEO_FRAMES = 1442;
    private static final int JPEG_QUALITY_IMAGE = 70;
    private static final String VIDEO_DIR = "video_frames";
    private static final int SAMPLE_RATE = 44100;
    private static final int FFT_SIZE = 1024;
    private static final int BAND_COUNT = 7;
    private final Semaphore writeSemaphore = new Semaphore(1);
    private Button btnMode1, btnMode2, btnMode3, btnUploadImage, btnUploadVideo;
    private Button btnAudioToggle;
    private ColorWheelView colorWheel;
    private SeekBar seekBarBrightness;
    private TextView tvStatus, textBrightness;
    private ImageView ivResult;

    private long lastColorSendTime = 0;
    private long lastBrightnessSendTime = 0;
    private SharedPreferences prefs;

    private BluetoothAdapter btAdapter;
    private BluetoothLeScanner btScanner;
    private BluetoothGatt btGatt;
    private BluetoothGattCharacteristic cmdChar;
    private BluetoothGattCharacteristic dataChar;
    private boolean bleConnected = false;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean pendingIsVideo = false;

    private Runnable previewRunnable = null;
    private List<byte[]> currentJpegFrames = null;
    private Bitmap previousPreviewBmp = null;
    private BluetoothGattCharacteristic ackChar;
    private volatile boolean frameAckReceived = false;
    // ===== Audio =====
    private boolean audioActive = false;
    private boolean useMicSource = true;
    private AudioRecord audioRecord;
    private Thread audioThread;
    private volatile boolean audioRunning = false;
    private MediaProjection mediaProjection;
    private volatile boolean transferring = false;
    private final Object writeLock = new Object();
    private volatile boolean writeComplete = false;
    private volatile boolean writeSuccess = false;
    private int negotiatedMtu = 23;
    // 新增一个字段，保存扫描回调引用
    private ScanCallback activeScanCallback = null;

    // ===== Permission Launchers =====
    private void setTransferring(boolean active) {
        transferring = active;
        boolean enabled = !active;
        runOnUiThread(() -> {
            btnMode1.setEnabled(enabled);
            btnMode2.setEnabled(enabled);
            btnMode3.setEnabled(enabled);
            btnUploadImage.setEnabled(enabled);
            btnUploadVideo.setEnabled(enabled);
            btnAudioToggle.setEnabled(enabled);
            colorWheel.setEnabled(enabled);
            seekBarBrightness.setEnabled(enabled);
        });
    }
    private final ActivityResultLauncher<String[]> permLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                boolean allGranted = true;
                for (Boolean granted : result.values()) {
                    if (!granted) { allGranted = false; break; }
                }
                if (allGranted) {
                    initBle();
                } else {
                    tvStatus.setText("需要蓝牙权限");
                }
            });

    private final ActivityResultLauncher<String> audioPermLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    startMicCapture();
                } else {
                    tvStatus.setText("需要麦克风权限");
                }
            });

    private ActivityResultLauncher<Intent> mediaProjectionLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("app_state", MODE_PRIVATE);

        btnMode1 = findViewById(R.id.btn_mode1);
        btnMode2 = findViewById(R.id.btn_mode2);
        btnMode3 = findViewById(R.id.btn_mode3);
        btnUploadImage = findViewById(R.id.btn_upload_image);
        btnUploadVideo = findViewById(R.id.btn_upload_video);
        btnAudioToggle = findViewById(R.id.btn_audio_toggle);

        colorWheel = findViewById(R.id.color_wheel);
        seekBarBrightness = findViewById(R.id.seek_bar_brightness);
        tvStatus = findViewById(R.id.text_status);
        textBrightness = findViewById(R.id.text_brightness);
        ivResult = findViewById(R.id.iv_result);

        mediaProjectionLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        startInternalCapture(result.getResultCode(), result.getData());
                    } else {
                        // 取消授权，外录还在跑，什么都不用做，改回状态就行
                        useMicSource = true;
                        btnAudioToggle.setText("律动 外录");
                        tvStatus.setText("律动中");
                    }
                });

        int savedBrightness = prefs.getInt("brightness", 255);
        seekBarBrightness.setProgress(savedBrightness);
        textBrightness.setText("亮度 " + (savedBrightness * 100 / 255) + "%");

        int savedColor = prefs.getInt("color", Color.WHITE);
        colorWheel.setColor(savedColor);

        if (prefs.getBoolean("has_video", false)) {
            List<byte[]> frames = loadVideoFrames();
            if (frames != null && frames.size() > 0) {
                startPreview(frames);
            }
        } else {
            File imgFile = new File(getFilesDir(), "saved_image.png");
            if (imgFile.exists()) {
                Bitmap bitmap = BitmapFactory.decodeFile(imgFile.getAbsolutePath());
                if (bitmap != null) ivResult.setImageBitmap(bitmap);
            }
        }

        // ===== Mode buttons — 点击时关闭律动 =====
        btnMode1.setOnClickListener(v -> { if (audioActive) stopAudio(); sendCmd("mode1"); });
        btnMode2.setOnClickListener(v -> { if (audioActive) stopAudio(); sendCmd("mode2"); });
        btnMode3.setOnClickListener(v -> { if (audioActive) stopAudio(); sendCmd("mode3"); });

        // ===== 律动按钮 — 首次点击开启，已激活时点击切换声源 =====
        btnAudioToggle.setOnClickListener(v -> {

            if (!audioActive) {
                startAudio();
            } else {
                useMicSource = !useMicSource;
                if (useMicSource) {
                    stopAudio();
                    startAudio();
                } else {
                    requestInternalCapture();
                }
            }
        });


        // ===== Color wheel =====
        colorWheel.setOnColorChangeListener(color -> {
            long now = System.currentTimeMillis();
            if (now - lastColorSendTime < THROTTLE_MS) return;
            lastColorSendTime = now;
            int r = Color.red(color);
            int g = Color.green(color);
            int b = Color.blue(color);
            sendCmd("COLOR:" + r + "," + g + "," + b);
            prefs.edit().putInt("color", color).apply();
        });

        // ===== Brightness =====
        seekBarBrightness.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                textBrightness.setText("亮度: " + (progress * 100 / 255) + "%");
                long now = System.currentTimeMillis();
                if (now - lastBrightnessSendTime < THROTTLE_MS) return;
                lastBrightnessSendTime = now;
                sendCmd("BRIGHT:" + progress);
                prefs.edit().putInt("brightness", progress).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                lastBrightnessSendTime = 0;
                sendCmd("BRIGHT:" + seekBar.getProgress());
                prefs.edit().putInt("brightness", seekBar.getProgress()).apply();
            }
        });

        // ===== Upload =====
        btnUploadImage.setOnClickListener(v -> {
            pendingIsVideo = false;
            Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
            startActivityForResult(intent, 100);
        });

        btnUploadVideo.setOnClickListener(v -> {
            pendingIsVideo = true;
            Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Video.Media.EXTERNAL_CONTENT_URI);
            startActivityForResult(intent, 100);
        });

        // 自动恢复外录
        if (prefs.getBoolean("auto_audio", false)
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            useMicSource = true;
            startMicCapture();
        }
        registerReceiver(btStateReceiver, new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED));
        checkBlePermissions();

        checkBlePermissions();

        checkBlePermissions();

        checkBlePermissions();
    }

    // ==================== Permissions ====================

    private void checkBlePermissions() {
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            initBle();
            return;
        }
        permLauncher.launch(new String[]{
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
        });
    }

    // ==================== BLE ====================
    private final BroadcastReceiver btStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!BluetoothAdapter.ACTION_STATE_CHANGED.equals(intent.getAction())) return;
            int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
            if (state == BluetoothAdapter.STATE_ON) {
                scanAndConnect();
            } else if (state == BluetoothAdapter.STATE_OFF) {
                if (activeScanCallback != null && btScanner != null) {
                    try { btScanner.stopScan(activeScanCallback); } catch (Exception ignored) {}
                    activeScanCallback = null;
                }
                bleConnected = false;
                btGatt = null;
                cmdChar = null;
                dataChar = null;
                ackChar = null;
                runOnUiThread(() -> tvStatus.setText("请开启蓝牙"));
            }
        }
    };
    private void initBle() {
        BluetoothManager mgr = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        btAdapter = mgr.getAdapter();
        if (btAdapter == null) {
            tvStatus.setText("设备不支持BLE蓝牙");
            return;
        }
        if (!btAdapter.isEnabled()) {
            tvStatus.setText("请开启蓝牙");
            return;
        }
        scanAndConnect();
    }

    private void scanAndConnect() {
        btScanner = btAdapter.getBluetoothLeScanner();
        if (btScanner == null) return;
        tvStatus.setText("扫描中");

        activeScanCallback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                String name = result.getDevice().getName();
                if (name != null && DEVICE_NAME.equals(name)) {
                    btScanner.stopScan(this);
                    activeScanCallback = null;
                    runOnUiThread(() -> tvStatus.setText("连接中"));
                    result.getDevice().connectGatt(MainActivity.this, false, gattCallback);
                }
            }

            @Override
            public void onScanFailed(int errorCode) {
                runOnUiThread(() -> tvStatus.setText("扫描失败: " + errorCode));
            }
        };

        btScanner.startScan(null, new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build(), activeScanCallback);

        // 10秒超时：用同一个引用停扫描，然后重试
        handler.postDelayed(() -> {
            if (!bleConnected && activeScanCallback != null) {
                try { btScanner.stopScan(activeScanCallback); } catch (Exception ignored) {}
                activeScanCallback = null;
                handler.postDelayed(() -> scanAndConnect(), 3000);
            }
        }, 10000);
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                btGatt = gatt;
                runOnUiThread(() -> tvStatus.setText("已连接"));
                gatt.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                bleConnected = false;
                btGatt = null;
                cmdChar = null;
                dataChar = null;
                ackChar = null;
                gatt.close();
                handler.postDelayed(() -> scanAndConnect(), 2000);
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            gatt.requestMtu(247);
            BluetoothGattService svc = gatt.getService(SVC_UUID);
            if (svc != null) {
                cmdChar = svc.getCharacteristic(CMD_UUID);
                dataChar = svc.getCharacteristic(DATA_UUID);
                dataChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);

                // ★ 订阅 ACK 通知
                UUID ACK_UUID = UUID.fromString("0000ff04-0000-1000-8000-00805f9b34fb");
                ackChar = svc.getCharacteristic(ACK_UUID);
                if (ackChar != null) {
                    gatt.setCharacteristicNotification(ackChar, true);
                    BluetoothGattDescriptor desc = ackChar.getDescriptor(
                            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"));
                    if (desc != null) {
                        desc.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                        gatt.writeDescriptor(desc);
                    }
                }
                dataChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
                bleConnected = true;

                if (audioActive) {
                    sendCmd("AUDIO:ON");
                    prefs.edit().putBoolean("auto_audio", useMicSource).apply();
                }
            } else {
                runOnUiThread(() -> tvStatus.setText("服务未找到"));
            }
        }

        @Override
        public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                negotiatedMtu = mtu;
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt,
                                            BluetoothGattCharacteristic characteristic) {
            // ★ ESP32 发来 ACK 了
            if (ackChar != null &&
                    characteristic.getUuid().equals(ackChar.getUuid())) {
                byte[] val = characteristic.getValue();
                if (val != null && val.length > 0 && val[0] == 0x03) {
                    frameAckReceived = true;
                }
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt,
                                          BluetoothGattCharacteristic characteristic, int status) {
            if (characteristic.getUuid().equals(DATA_UUID)) {
                synchronized (writeLock) {
                    writeComplete = true;
                    writeSuccess = (status == BluetoothGatt.GATT_SUCCESS);
                    writeLock.notifyAll();
                }
            }
        }
    };

    private void sendCmd(String cmd) {
        if (!bleConnected || btGatt == null || cmdChar == null) return;
        cmdChar.setValue(cmd);
        btGatt.writeCharacteristic(cmdChar);
    }

    // ==================== Write Flow Control ====================

    private boolean writeSync(byte[] value, long timeoutMs) {
        if (!bleConnected || btGatt == null || dataChar == null) return false;
        synchronized (writeLock) {
            writeComplete = false;
        }
        dataChar.setValue(value);
        if (!btGatt.writeCharacteristic(dataChar)) {
            // 队列满，等一下重试一次
            sleep(50);
            dataChar.setValue(value);
            if (!btGatt.writeCharacteristic(dataChar)) {
                return false;
            }
        }
        // 等 BLE 栈处理完这笔写入，防止队列溢出
        synchronized (writeLock) {
            try {
                if (!writeComplete) writeLock.wait(timeoutMs);
            } catch (InterruptedException ignored) {}
        }
        return true;
    }

    // ==================== Audio ====================

    private void startAudio() {
        if (audioActive) return;
        if (useMicSource) {
            startMicCapture();
        } else {
            requestInternalCapture();
        }
    }

    private void startMicCapture() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            audioPermLauncher.launch(Manifest.permission.RECORD_AUDIO);
            return;
        }

        int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int bufSize = Math.max(minBuf, FFT_SIZE * 2);

        try {
            audioRecord = new AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufSize);
        } catch (Exception e) {
            tvStatus.setText("麦克风初始化失败");
            return;
        }

        beginAudioCapture();
    }

    private void requestInternalCapture() {
        if (Build.VERSION.SDK_INT < 29) {
            tvStatus.setText("内录需要 Android 10 以上");
            return;
        }

        // Android 14+ 必须先启动前台服务
        if (Build.VERSION.SDK_INT >= 34) {
            Intent serviceIntent = new Intent(this, AudioCaptureService.class);
            startForegroundService(serviceIntent);
        }

        MediaProjectionManager mpm = (MediaProjectionManager)
                getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        mediaProjectionLauncher.launch(mpm.createScreenCaptureIntent());
    }

    private void startInternalCapture(int resultCode, Intent data) {
        // 授权成功，停掉外录资源
        audioRunning = false;
        if (audioThread != null) {
            try { audioThread.join(1000); } catch (Exception ignored) {}
            audioThread = null;
        }
        if (audioRecord != null) {
            try { audioRecord.stop(); } catch (Exception ignored) {}
            try { audioRecord.release(); } catch (Exception ignored) {}
            audioRecord = null;
        }


        try {
            MediaProjectionManager mpm = (MediaProjectionManager)
                    getSystemService(Context.MEDIA_PROJECTION_SERVICE);

            mediaProjection = mpm.getMediaProjection(resultCode, data);

            if (mediaProjection == null) {
                tvStatus.setText("获取 MediaProjection 失败");
                return;
            }

            if (Build.VERSION.SDK_INT >= 34) {
                mediaProjection.registerCallback(new MediaProjection.Callback() {
                    @Override
                    public void onStop() {
                        if (!useMicSource) {
                            runOnUiThread(() -> stopAudio());
                        }
                    }
                }, handler);
            }

            AudioPlaybackCaptureConfiguration config =
                    new AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                            .addMatchingUsage(AudioAttributes.USAGE_GAME)
                            .build();

            int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            int bufSize = Math.max(minBuf, FFT_SIZE * 2);

            audioRecord = new AudioRecord.Builder()
                    .setAudioPlaybackCaptureConfig(config)
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build())
                    .setBufferSizeInBytes(bufSize)
                    .build();

            beginAudioCapture();

        } catch (Exception e) {
            tvStatus.setText("内录初始化失败: " + e.getMessage());
        }
    }

    private void beginAudioCapture() {
        try {
            audioRecord.startRecording();
        } catch (Exception e) {
            tvStatus.setText("录音启动失败: " + e.getMessage());
            return;
        }

        audioActive = true;
        audioRunning = true;

        sendCmd("AUDIO:ON");
        prefs.edit().putBoolean("auto_audio", useMicSource).apply();

        runOnUiThread(() -> {
            btnAudioToggle.setText(useMicSource ? "频谱 外录" : "频谱 内录");
            btnUploadImage.setEnabled(false);
            btnUploadVideo.setEnabled(false);
        });

        audioThread = new Thread(() -> {
            short[] buffer = new short[FFT_SIZE];
            float[] re = new float[FFT_SIZE];
            float[] im = new float[FFT_SIZE];
            int[] spectrum = new int[BAND_COUNT];
            double[] dbBands = new double[BAND_COUNT];

            while (audioRunning) {
                int read = audioRecord.read(buffer, 0, FFT_SIZE);
                if (read <= 0) continue;

                // ---- RMS 音量 ----
                long sum = 0;
                for (int i = 0; i < read; i++)
                    sum += (long) buffer[i] * buffer[i];
                double rmsNorm = Math.sqrt(sum / (double) read) / 32768.0;
                int level = Math.min(255, Math.max(0, (int) (rmsNorm * 255 * 5)));

                // ---- FFT + 汉宁窗 + PCM 归一化 ----
                for (int i = 0; i < FFT_SIZE; i++) {
                    float w = 0.5f * (1f - (float) Math.cos(2.0 * Math.PI * i / (FFT_SIZE - 1)));
                    re[i] = (i < read ? buffer[i] / 32768.0f : 0) * w;
                    im[i] = 0;
                }
                fft(re, im);

                // ---- 7 段 → dBFS ----
                int half = FFT_SIZE / 2;
                int[] bandEdges = {20, 150, 300, 800, 2000, 4000, 8000, 16000};
                for (int b = 0; b < BAND_COUNT; b++) {
                    int start = Math.max(1, bandEdges[b] * FFT_SIZE / SAMPLE_RATE);
                    int end = Math.min(half, bandEdges[b + 1] * FFT_SIZE / SAMPLE_RATE);
                    if (end <= start) end = start + 1;
                    double energy = 0;
                    for (int j = start; j < end; j++)
                        energy += Math.sqrt(re[j] * re[j] + im[j] * im[j]);
                    energy /= ((double)(end - start) * FFT_SIZE);
                    dbBands[b] = 20 * Math.log10(Math.max(energy, 1e-10));
                }

                // ---- 自动对比度拉伸 ----
                double dbMin = dbBands[0], dbMax = dbBands[0];
                for (int b = 1; b < BAND_COUNT; b++) {
                    if (dbBands[b] < dbMin) dbMin = dbBands[b];
                    if (dbBands[b] > dbMax) dbMax = dbBands[b];
                }
                double range = Math.max(dbMax - dbMin, 1.0);
                for (int b = 0; b < BAND_COUNT; b++) {
                    spectrum[b] = Math.min(255, Math.max(0, (int)((dbBands[b] - dbMin) * 255.0 / range)));
                }

                // ---- 发送 ----
                if (bleConnected && dataChar != null && btGatt != null) {
                    byte[] pkt = new byte[2 + BAND_COUNT];
                    pkt[0] = (byte) 0xAA;
                    pkt[1] = (byte) level;
                    for (int i = 0; i < BAND_COUNT; i++)
                        pkt[2 + i] = (byte) spectrum[i];
                    dataChar.setValue(pkt);
                    btGatt.writeCharacteristic(dataChar);
                }

                try { Thread.sleep(33); } catch (Exception ignored) {}
            }
        }, "AudioCapture");
        audioThread.setPriority(Thread.MAX_PRIORITY);
        audioThread.start();
    }

    private void stopAudio() {
        audioRunning = false;
        audioActive = false;

        if (audioThread != null) {
            try { audioThread.join(1000); } catch (Exception ignored) {}
            audioThread = null;
        }

        if (audioRecord != null) {
            try { audioRecord.stop(); } catch (Exception ignored) {}
            try { audioRecord.release(); } catch (Exception ignored) {}
            audioRecord = null;
        }

        if (mediaProjection != null) {
            try { mediaProjection.stop(); } catch (Exception ignored) {}
            mediaProjection = null;
        }

        if (Build.VERSION.SDK_INT >= 34) {
            stopService(new Intent(this, AudioCaptureService.class));
        }

        // 删掉 sendCmd("AUDIO:OFF");
        prefs.edit().putBoolean("auto_audio", false).apply();

        runOnUiThread(() -> {
            btnAudioToggle.setText("频谱 外录");
            btnUploadImage.setEnabled(true);
            btnUploadVideo.setEnabled(true);
        });
    }

    /** 原地 512 点 Cooley-Tukey FFT */
    private void fft(float[] re, float[] im) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            while ((j & bit) != 0) { j ^= bit; bit >>= 1; }
            j ^= bit;
            if (i < j) {
                float t;
                t = re[i]; re[i] = re[j]; re[j] = t;
                t = im[i]; im[i] = im[j]; im[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            float ang = -2f * (float) Math.PI / len;
            float wR = (float) Math.cos(ang), wI = (float) Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                float cR = 1f, cI = 0f;
                for (int j = 0; j < len / 2; j++) {
                    float tR = cR * re[i + j + len / 2] - cI * im[i + j + len / 2];
                    float tI = cR * im[i + j + len / 2] + cI * re[i + j + len / 2];
                    re[i + j + len / 2] = re[i + j] - tR;
                    im[i + j + len / 2] = im[i + j] - tI;
                    re[i + j] += tR;
                    im[i + j] += tI;
                    float nR = cR * wR - cI * wI;
                    cI = cR * wI + cI * wR;
                    cR = nR;
                }
            }
        }
    }

    // ==================== Data Transfer ====================

    private void sendImageToMCU(Bitmap bitmap) {
        new Thread(() -> {
            setTransferring(true);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY_IMAGE, baos);
            byte[] jpegData = baos.toByteArray();

            if (!bleConnected || btGatt == null || dataChar == null) {
                runOnUiThread(() -> tvStatus.setText("未连接"));
                setTransferring(false);
                return;
            }

            final boolean[] ok = {true};

            if (!writeSync("IMAGE_JPEG".getBytes(), 3000)) {
                ok[0] = false;
            }
            if (ok[0]) {
                sleep(25);
                if (!writeSync(intToBytes(jpegData.length), 3000)) {
                    ok[0] = false;
                }
            }
            if (ok[0]) {
                sleep(25);
                if (!sendDataChunks(jpegData)) {
                    ok[0] = false;
                }
            }

            runOnUiThread(() -> tvStatus.setText(ok[0] ? "传输完成" : "传输失败"));
            sleep(1000);
            runOnUiThread(() -> tvStatus.setText("已连接"));
            setTransferring(false);
        }).start();
    }

    private void sendVideoToMCU(List<byte[]> jpegFrames) {
        new Thread(() -> {
            if (!bleConnected || btGatt == null || dataChar == null) {
                runOnUiThread(() -> tvStatus.setText("未连接"));
                return;
            }
            setTransferring(true);
            runOnUiThread(() -> tvStatus.setText("传输中 0%"));

            int totalFrames = jpegFrames.size();
            int totalBytes = 0;
            for (byte[] f : jpegFrames) totalBytes += f.length;

            if (!writeSync("VIDEO_START".getBytes(), 3000)) {
                runOnUiThread(() -> tvStatus.setText("传输头失败"));
                setTransferring(false);
                return;
            }
            sleep(100);

            if (!writeSync(intToBytes(totalFrames), 3000)) {
                runOnUiThread(() -> tvStatus.setText("传输帧数失败"));
                setTransferring(false);
                return;
            }
            sleep(50);

            waitAck(2000);

            int sentBytes = 0;
            final boolean[] allDone = {true};

            for (int i = 0; i < totalFrames; i++) {
                if (!bleConnected) { allDone[0] = false; break; }
                byte[] jpeg = jpegFrames.get(i);

                if (!writeSync(intToBytes(jpeg.length), 3000)) {
                    final int fi = i;
                    runOnUiThread(() -> tvStatus.setText("帧长度发送失败 帧" + fi));
                    allDone[0] = false;
                    break;
                }

                if (!sendDataChunks(jpeg)) {
                    final int fi = i;
                    runOnUiThread(() -> tvStatus.setText("帧数据发送失败 帧" + fi));
                    allDone[0] = false;
                    break;
                }

                if (!waitAck(5000)) {
                    final int fi = i;
                    runOnUiThread(() -> tvStatus.setText("ACK超时 帧" + fi));
                    allDone[0] = false;
                    break;
                }

                sentBytes += jpeg.length;
                final int pct = (sentBytes * 100) / totalBytes;
                runOnUiThread(() -> tvStatus.setText("传输中 " + pct + "%"));
            }

            sleep(100);
            runOnUiThread(() -> tvStatus.setText(allDone[0] ? "传输完成" : "传输中断"));
            sleep(1000);
            runOnUiThread(() -> tvStatus.setText("已连接"));
            setTransferring(false);
        }).start();
    }

    // ★ 新增一个等待 ACK 的方法
    private boolean waitAck(long timeoutMs) {
        frameAckReceived = false;
        long start = System.currentTimeMillis();
        while (!frameAckReceived && System.currentTimeMillis() - start < timeoutMs) {
            sleep(10);
        }
        return frameAckReceived;
    }

    private boolean sendDataChunks(byte[] data) {
        int chunkSize = Math.max(20, Math.min(180, negotiatedMtu - 3));
        int offset = 0;
        while (offset < data.length) {
            if (!bleConnected || btGatt == null) return false;
            int len = Math.min(chunkSize, data.length - offset);
            byte[] chunk = new byte[len];
            System.arraycopy(data, offset, chunk, 0, len);
            if (!writeSync(chunk, 3000)) {
                return false;
            }
            offset += len;
        }
        return true;
    }

    private byte[] intToBytes(int value) {
        return new byte[]{
                (byte) (value >> 24), (byte) (value >> 16),
                (byte) (value >> 8), (byte) value
        };
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    // ==================== Preview ====================

    private void startPreview(List<byte[]> jpegFrames) {
        stopPreviewAndCleanup();
        currentJpegFrames = jpegFrames;
        final int[] index = {0};

        previewRunnable = new Runnable() {
            @Override
            public void run() {
                if (currentJpegFrames == null || currentJpegFrames.isEmpty()) return;

                if (previousPreviewBmp != null && !previousPreviewBmp.isRecycled())
                    previousPreviewBmp.recycle();

                if (index[0] >= currentJpegFrames.size()) index[0] = 0;

                byte[] jpeg = currentJpegFrames.get(index[0]);
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
                Bitmap bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, opts);

                if (bmp != null) {
                    ivResult.setImageBitmap(bmp);
                    previousPreviewBmp = bmp;
                }

                index[0]++;
                handler.postDelayed(this, 1000 / 18);
            }
        };
        handler.post(previewRunnable);
    }

    private void stopPreviewAndCleanup() {
        if (previewRunnable != null) {
            handler.removeCallbacks(previewRunnable);
            previewRunnable = null;
        }
        if (previousPreviewBmp != null && !previousPreviewBmp.isRecycled()) {
            previousPreviewBmp.recycle();
            previousPreviewBmp = null;
        }
        currentJpegFrames = null;
    }

    // ==================== Video Frame Storage ====================

    private void saveVideoFrames(List<byte[]> frames) {
        try {
            prefs.edit().remove("has_image").apply();
            File dir = new File(getFilesDir(), VIDEO_DIR);
            if (dir.exists()) {
                for (File f : dir.listFiles()) f.delete();
            } else {
                dir.mkdirs();
            }
            for (int i = 0; i < frames.size(); i++) {
                File f = new File(dir, "f_" + i + ".jpg");
                FileOutputStream fos = new FileOutputStream(f);
                fos.write(frames.get(i));
                fos.flush();
                fos.close();
            }
            prefs.edit()
                    .putBoolean("has_video", true)
                    .putInt("video_count", frames.size())
                    .apply();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private List<byte[]> loadVideoFrames() {
        try {
            int count = prefs.getInt("video_count", 0);
            if (count <= 0) return null;
            File dir = new File(getFilesDir(), VIDEO_DIR);
            if (!dir.exists()) return null;
            List<byte[]> frames = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                File f = new File(dir, "f_" + i + ".jpg");
                if (!f.exists()) return null;
                byte[] data = new byte[(int) f.length()];
                FileInputStream fis = new FileInputStream(f);
                fis.read(data);
                fis.close();
                frames.add(data);
            }
            return frames;
        } catch (Exception e) {
            return null;
        }
    }

    private void deleteVideoFrames() {
        File dir = new File(getFilesDir(), VIDEO_DIR);
        if (dir.exists()) {
            for (File f : dir.listFiles()) f.delete();
            dir.delete();
        }
        prefs.edit().remove("has_video").remove("video_count").apply();
    }

    // ==================== Activity Results ====================

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == 100 && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            Intent cropIntent = new Intent(this, CropActivity.class);
            cropIntent.putExtra("IMAGE_URI", uri);
            cropIntent.putExtra("IS_VIDEO", pendingIsVideo);
            startActivityForResult(cropIntent, 101);
        }

        if (requestCode == 101 && resultCode == RESULT_OK && data != null) {
            boolean isVideo = data.getBooleanExtra("IS_VIDEO", false);

            if (isVideo) {
                Uri videoUri = data.getParcelableExtra("VIDEO_URI");
                int cropLeft = data.getIntExtra("CROP_LEFT", 0);
                int cropTop = data.getIntExtra("CROP_TOP", 0);
                int cropRight = data.getIntExtra("CROP_RIGHT", 0);
                int cropBottom = data.getIntExtra("CROP_BOTTOM", 0);
                long startUs = data.getLongExtra("START_TIME_US", 0);
                long endUs = data.getLongExtra("END_TIME_US", 0);
                if (videoUri != null) {
                    processAndSendVideo(videoUri, cropLeft, cropTop, cropRight, cropBottom, startUs, endUs);
                }
            } else {
                Bitmap bitmap = data.getParcelableExtra("BITMAP");
                if (bitmap != null) {
                    deleteVideoFrames();
                    stopPreviewAndCleanup();
                    ivResult.setImageBitmap(bitmap);
                    saveImageToDisk(bitmap);
                    sendImageToMCU(bitmap);
                }
            }
        }
    }

    private void processAndSendVideo(Uri videoUri, int cropLeft, int cropTop,
                                     int cropRight, int cropBottom, long startUs, long endUs) {
        new Thread(() -> {
            runOnUiThread(() -> {
                tvStatus.setText("提取帧中");
                stopPreviewAndCleanup();
            });

            try {
                VideoFrameExtractor.FrameResult result =
                        VideoFrameExtractor.extractFrames(this, videoUri,
                                cropLeft, cropTop, cropRight, cropBottom,
                                SCREEN_W, SCREEN_H, MAX_VIDEO_FRAMES, startUs, endUs);

                if (result.jpegFrames.isEmpty()) {
                    runOnUiThread(() -> tvStatus.setText("未提取到帧"));
                    return;
                }

                List<byte[]> frames = result.jpegFrames;

                // 保存扔后台，不挡路
                new Thread(() -> {
                    saveVideoFrames(frames);
                    runOnUiThread(() -> startPreview(frames));
                }, "save_frames").start();

                // 提取完立刻开始传输
                sendVideoToMCU(frames);

            } catch (Exception e) {
                runOnUiThread(() -> tvStatus.setText("视频处理失败: " + e.getMessage()));
            }
        }).start();
    }

    private void saveImageToDisk(Bitmap bitmap) {
        try {
            File file = new File(getFilesDir(), "saved_image.png");
            FileOutputStream fos = new FileOutputStream(file);
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
            fos.flush();
            fos.close();
            prefs.edit().putBoolean("has_image", true).apply();
        } catch (Exception ignored) {}
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(btStateReceiver);
        stopAudio();
        stopPreviewAndCleanup();
    }
}