package si.skladisce.app;

import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import androidx.core.content.FileProvider;
import com.getcapacitor.BridgeActivity;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import androidx.core.app.ActivityCompat;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import android.app.Dialog;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraControl;
import androidx.camera.core.CameraInfo;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.FocusMeteringAction;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.MeteringPoint;
import androidx.camera.core.MeteringPointFactory;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScannerOptions;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.text.TextRecognizer;

public class MainActivity extends BridgeActivity {
    public static String pendingIncomingJson = null;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(MLKitDirectPlugin.class);
        registerPlugin(NativeFileSaverPlugin.class);
        registerPlugin(BLESyncPlugin.class);
        registerPlugin(NativeCameraScannerPlugin.class);
        super.onCreate(savedInstanceState);
        handleIncomingIntent(getIntent());
        setupStatusBarInset();
    }

    @Override
    public void onResume() {
        super.onResume();
        applyStatusBarInsets();
    }

    private void setupStatusBarInset() {
        try {
            androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(getWindow().getDecorView(), (v, windowInsets) -> {
                applyStatusBarInsets();
                return windowInsets;
            });
        } catch (Throwable ignored) {}
        applyStatusBarInsets();
    }

    public void applyStatusBarInsets() {
        runOnUiThread(() -> {
            int statusBarPx = 0;
            try {
                androidx.core.view.WindowInsetsCompat rootInsets = androidx.core.view.ViewCompat.getRootWindowInsets(getWindow().getDecorView());
                if (rootInsets != null) {
                    androidx.core.graphics.Insets insets = rootInsets.getInsets(
                        androidx.core.view.WindowInsetsCompat.Type.statusBars() | androidx.core.view.WindowInsetsCompat.Type.displayCutout()
                    );
                    statusBarPx = insets.top;
                }
            } catch (Throwable ignored) {}

            if (statusBarPx <= 0) {
                try {
                    int resourceId = getResources().getIdentifier("status_bar_height", "dimen", "android");
                    if (resourceId > 0) {
                        statusBarPx = getResources().getDimensionPixelSize(resourceId);
                    }
                } catch (Throwable ignored) {}
            }

            float density = getResources().getDisplayMetrics().density;
            int statusBarDp = (int) Math.ceil(statusBarPx / (density > 0 ? density : 1.0f));
            if (statusBarDp <= 0) {
                statusBarDp = 32;
            }

            final int safeTopDp = statusBarDp;
            if (getBridge() != null && getBridge().getWebView() != null) {
                getBridge().getWebView().post(() -> {
                    getBridge().getWebView().evaluateJavascript(
                        "document.documentElement.style.setProperty('--safe-top', '" + safeTopDp + "px');",
                        null
                    );
                });
            }
        });
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIncomingIntent(intent);
    }

    private void handleIncomingIntent(Intent intent) {
        if (intent == null) return;
        Uri uri = intent.getData();
        if (uri == null && intent.hasExtra(Intent.EXTRA_STREAM)) {
            uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        }
        if (uri != null) {
            try {
                java.io.InputStream is = getContentResolver().openInputStream(uri);
                if (is != null) {
                    java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(is));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line);
                    }
                    reader.close();
                    pendingIncomingJson = sb.toString();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent event) {
        int action = event.getAction();
        int keyCode = event.getKeyCode();
        if (action == android.view.KeyEvent.ACTION_DOWN) {
            if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP) {
                if (getBridge() != null && getBridge().getWebView() != null) {
                    getBridge().getWebView().post(() -> {
                        getBridge().getWebView().evaluateJavascript("if (window.handleHardwareVolumeKey) { window.handleHardwareVolumeKey('up'); }", null);
                    });
                    return true;
                }
            } else if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN) {
                if (getBridge() != null && getBridge().getWebView() != null) {
                    getBridge().getWebView().post(() -> {
                        getBridge().getWebView().evaluateJavascript("if (window.handleHardwareVolumeKey) { window.handleHardwareVolumeKey('down'); }", null);
                    });
                    return true;
                }
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @CapacitorPlugin(name = "MLKitDirect")
    public static class MLKitDirectPlugin extends Plugin {
        private com.google.mlkit.vision.text.TextRecognizer recognizerInstance = null;

        private synchronized com.google.mlkit.vision.text.TextRecognizer getRecognizer() {
            if (recognizerInstance == null) {
                recognizerInstance = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
            }
            return recognizerInstance;
        }

        @Override
        protected void handleOnDestroy() {
            if (recognizerInstance != null) {
                try { recognizerInstance.close(); } catch (Exception ignored) {}
                recognizerInstance = null;
            }
            super.handleOnDestroy();
        }

        @PluginMethod
        public void recognizeBase64(PluginCall call) {
            String base64Data = call.getString("base64");
            if (base64Data == null || base64Data.isEmpty()) {
                call.reject("Base64 string missing");
                return;
            }

            if (base64Data.contains(",")) {
                base64Data = base64Data.substring(base64Data.indexOf(",") + 1);
            }

            try {
                byte[] decodedBytes = Base64.decode(base64Data, Base64.DEFAULT);
                Bitmap bitmap = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.length);
                if (bitmap == null) {
                    call.reject("Could not decode bitmap");
                    return;
                }

                InputImage image = InputImage.fromBitmap(bitmap, 0);
                getRecognizer().process(image)
                    .addOnSuccessListener(result -> {
                        JSObject ret = new JSObject();
                        ret.put("text", result.getText());

                        com.getcapacitor.JSArray blocksArray = new com.getcapacitor.JSArray();
                        for (com.google.mlkit.vision.text.Text.TextBlock block : result.getTextBlocks()) {
                            JSObject b = new JSObject();
                            b.put("rawValue", block.getText());
                            android.graphics.Rect box = block.getBoundingBox();
                            if (box != null) {
                                JSObject bb = new JSObject();
                                bb.put("x", box.left);
                                bb.put("y", box.top);
                                bb.put("width", box.width());
                                bb.put("height", box.height());
                                b.put("boundingBox", bb);
                            }
                            blocksArray.put(b);
                        }
                        ret.put("blocks", blocksArray);
                        call.resolve(ret);
                    })
                    .addOnFailureListener(e -> {
                        call.reject(e.getMessage());
                    });
            } catch (Exception e) {
                call.reject(e.getMessage());
            }
        }

        /**
         * Namenska metoda za strojno (ML) detekcijo položaja besedila v grobem (coarse) kadru
         * Izračuna natančen bounding box teksta z varnostnim robom neposredno v nativni kodi (zelo hitro)
         */
        @PluginMethod
        public void detectTextBoundsML(PluginCall call) {
            String base64Data = call.getString("base64");
            int fullW = call.getInt("fullW", 1920);
            int fullH = call.getInt("fullH", 1080);

            if (base64Data == null || base64Data.isEmpty()) {
                call.reject("Base64 string missing");
                return;
            }

            if (base64Data.contains(",")) {
                base64Data = base64Data.substring(base64Data.indexOf(",") + 1);
            }

            try {
                byte[] decodedBytes = Base64.decode(base64Data, Base64.DEFAULT);
                Bitmap bitmap = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.length);
                if (bitmap == null) {
                    call.reject("Could not decode bitmap");
                    return;
                }

                int lowW = bitmap.getWidth();
                int lowH = bitmap.getHeight();
                float scale = (float) lowW / (float) fullW;

                InputImage image = InputImage.fromBitmap(bitmap, 0);
                getRecognizer().process(image)
                    .addOnSuccessListener(result -> {
                        JSObject ret = new JSObject();
                        java.util.List<com.google.mlkit.vision.text.Text.TextBlock> blocks = result.getTextBlocks();

                        if (blocks == null || blocks.isEmpty()) {
                            ret.put("hasText", false);
                            call.resolve(ret);
                            return;
                        }

                        int minX = lowW;
                        int minY = lowH;
                        int maxX = 0;
                        int maxY = 0;
                        int validCount = 0;
                        StringBuilder sb = new StringBuilder();

                        for (com.google.mlkit.vision.text.Text.TextBlock block : blocks) {
                            String txt = block.getText() != null ? block.getText().trim() : "";
                            if (txt.length() >= 2 && txt.matches(".*[a-zA-Z0-9].*")) {
                                android.graphics.Rect box = block.getBoundingBox();
                                if (box != null) {
                                    minX = Math.min(minX, box.left);
                                    minY = Math.min(minY, box.top);
                                    maxX = Math.max(maxX, box.right);
                                    maxY = Math.max(maxY, box.bottom);
                                    validCount++;
                                    if (sb.length() < 300) {
                                        sb.append(txt).append("\n");
                                    }
                                }
                            }
                        }

                        if (validCount == 0 || minX >= maxX || minY >= maxY) {
                            ret.put("hasText", false);
                            call.resolve(ret);
                            return;
                        }

                        // Dodaj 18% varnostni rob
                        int bw = maxX - minX;
                        int bh = maxY - minY;
                        int padX = (int) (bw * 0.18f);
                        int padY = (int) (bh * 0.18f);

                        int paddedMinX = Math.max(0, minX - padX);
                        int paddedMinY = Math.max(0, minY - padY);
                        int paddedMaxX = Math.min(lowW, maxX + padX);
                        int paddedMaxY = Math.min(lowH, maxY + padY);

                        // Preslikava na polno ločljivost
                        int cropX = Math.max(0, Math.round(paddedMinX / scale));
                        int cropY = Math.max(0, Math.round(paddedMinY / scale));
                        int cropW = Math.min(fullW - cropX, Math.round((paddedMaxX - paddedMinX) / scale));
                        int cropH = Math.min(fullH - cropY, Math.round((paddedMaxY - paddedMinY) / scale));

                        ret.put("hasText", true);
                        ret.put("blocksCount", validCount);
                        ret.put("detectedText", sb.toString());

                        JSObject tb = new JSObject();
                        tb.put("cropX", cropX);
                        tb.put("cropY", cropY);
                        tb.put("cropW", cropW);
                        tb.put("cropH", cropH);
                        // Normalizirane koordinate za UI vizualno sledenje (HUD)
                        tb.put("normLeft", (double) paddedMinX / lowW);
                        tb.put("normTop", (double) paddedMinY / lowH);
                        tb.put("normWidth", (double) (paddedMaxX - paddedMinX) / lowW);
                        tb.put("normHeight", (double) (paddedMaxY - paddedMinY) / lowH);

                        ret.put("targetBox", tb);
                        call.resolve(ret);
                    })
                    .addOnFailureListener(e -> {
                        call.reject(e.getMessage());
                    });
            } catch (Exception e) {
                call.reject(e.getMessage());
            }
        }
    }

    @CapacitorPlugin(name = "NativeFileSaver")
    public static class NativeFileSaverPlugin extends Plugin {
        @PluginMethod
        public void saveAndOpenFile(PluginCall call) {
            String base64Data = call.getString("base64");
            String fileName = call.getString("fileName");
            String mimeType = call.getString("mimeType", "application/pdf");

            if (base64Data == null || base64Data.isEmpty()) {
                call.reject("Manjkajo podatki datoteke (base64).");
                return;
            }
            if (fileName == null || fileName.isEmpty()) {
                fileName = "dokument-" + System.currentTimeMillis() + ".pdf";
            }

            if (base64Data.contains(",")) {
                base64Data = base64Data.substring(base64Data.indexOf(",") + 1);
            }

            try {
                byte[] fileBytes = Base64.decode(base64Data, Base64.DEFAULT);

                // 1. Shrani v mapo Prenosi (Downloads) na napravi
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
                    values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);
                    values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);

                    Uri uri = getContext().getContentResolver().insert(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                    );
                    if (uri != null) {
                        try (OutputStream os = getContext().getContentResolver().openOutputStream(uri)) {
                            if (os != null) {
                                os.write(fileBytes);
                                os.flush();
                            }
                        }
                    }
                } else {
                    File downloadsDir = Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS
                    );
                    if (!downloadsDir.exists()) downloadsDir.mkdirs();
                    File targetFile = new File(downloadsDir, fileName);
                    try (FileOutputStream fos = new FileOutputStream(targetFile)) {
                        fos.write(fileBytes);
                        fos.flush();
                    }
                    MediaScannerConnection.scanFile(
                        getContext(),
                        new String[]{targetFile.getAbsolutePath()},
                        new String[]{mimeType},
                        null
                    );
                }

                // 2. Shrani kopijo v predpomnilnik za takojšnje odpiranje / deljenje
                File cacheFile = new File(getContext().getCacheDir(), fileName);
                try (FileOutputStream fos = new FileOutputStream(cacheFile)) {
                    fos.write(fileBytes);
                    fos.flush();
                }

                Uri contentUri = FileProvider.getUriForFile(
                    getContext(),
                    getContext().getPackageName() + ".fileprovider",
                    cacheFile
                );

                Intent viewIntent = new Intent(Intent.ACTION_VIEW);
                viewIntent.setDataAndType(contentUri, mimeType);
                viewIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                viewIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

                try {
                    Intent chooser = Intent.createChooser(viewIntent, "Odpri " + fileName);
                    chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    getContext().startActivity(chooser);
                } catch (Exception ex) {
                    // Ignoriraj, če ni nameščenega pregledovalnika
                }

                JSObject ret = new JSObject();
                ret.put("success", true);
                ret.put("fileName", fileName);
                call.resolve(ret);

            } catch (Exception e) {
                call.reject("Napaka pri shranjevanju: " + e.getMessage());
            }
        }

        @PluginMethod
        public void shareFile(PluginCall call) {
            String base64Data = call.getString("base64");
            String fileName = call.getString("fileName");
            String mimeType = call.getString("mimeType", "application/json");

            if (base64Data == null || base64Data.isEmpty()) {
                call.reject("Manjkajo podatki datoteke (base64).");
                return;
            }
            if (fileName == null || fileName.isEmpty()) {
                fileName = "skladisce-zaloga.skladisce";
            }
            if (base64Data.contains(",")) {
                base64Data = base64Data.substring(base64Data.indexOf(",") + 1);
            }

            try {
                byte[] fileBytes = Base64.decode(base64Data, Base64.DEFAULT);
                File cacheFile = new File(getContext().getCacheDir(), fileName);
                try (FileOutputStream fos = new FileOutputStream(cacheFile)) {
                    fos.write(fileBytes);
                    fos.flush();
                }

                Uri contentUri = FileProvider.getUriForFile(
                    getContext(),
                    getContext().getPackageName() + ".fileprovider",
                    cacheFile
                );

                Intent shareIntent = new Intent(Intent.ACTION_SEND);
                shareIntent.setType(mimeType);
                shareIntent.putExtra(Intent.EXTRA_STREAM, contentUri);
                shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

                Intent chooser = Intent.createChooser(shareIntent, "Deli zalogo (Quick Share / Bluetooth)");
                chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                getContext().startActivity(chooser);

                JSObject ret = new JSObject();
                ret.put("success", true);
                ret.put("fileName", fileName);
                call.resolve(ret);
            } catch (Exception e) {
                call.reject("Napaka pri deljenju: " + e.getMessage());
            }
        }

        @PluginMethod
        public void checkIncomingSync(PluginCall call) {
            JSObject ret = new JSObject();
            if (MainActivity.pendingIncomingJson != null && !MainActivity.pendingIncomingJson.isEmpty()) {
                ret.put("hasData", true);
                ret.put("jsonData", MainActivity.pendingIncomingJson);
                MainActivity.pendingIncomingJson = null; // prebrano
            } else {
                ret.put("hasData", false);
            }
            call.resolve(ret);
        }
    }

    @CapacitorPlugin(name = "NativeCameraScanner")
    public static class NativeCameraScannerPlugin extends Plugin {
        private Dialog scannerDialog = null;
        private ProcessCameraProvider cameraProvider = null;
        private java.util.concurrent.ExecutorService cameraExecutor = null;
        private boolean isTorchOn = false;
        private CameraControl cameraControl = null;
        private CameraInfo cameraInfo = null;
        private PluginCall savedCall = null;
        private long lastScanTime = 0;
        private boolean isContinuousMode = false;
        private BarcodeScanner barcodeScanner = null;
        private TextRecognizer textRecognizer = null;
        private TextView promptTextView = null;
        private Button torchButton = null;
        private float currentZoom = 0.0f;

        @PluginMethod
        public void startScanner(PluginCall call) {
            if (ActivityCompat.checkSelfPermission(getContext(), Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(getActivity(), new String[]{Manifest.permission.CAMERA}, 9901);
                call.reject("Dovoljenje za kamero ni odobreno.");
                return;
            }

            this.savedCall = call;
            String prompt = call.getString("prompt", "Usmeri v črtno kodo ali škatlo");
            this.isContinuousMode = call.getBoolean("continuous", false);
            this.lastScanTime = 0;
            this.isTorchOn = false;
            this.currentZoom = 0.0f;

            getActivity().runOnUiThread(() -> {
                showScannerUI(prompt);
            });
        }

        @PluginMethod
        public void stopScanner(PluginCall call) {
            getActivity().runOnUiThread(this::dismissScanner);
            call.resolve();
        }

        @PluginMethod
        public void toggleTorch(PluginCall call) {
            getActivity().runOnUiThread(() -> {
                if (cameraControl != null) {
                    isTorchOn = !isTorchOn;
                    cameraControl.enableTorch(isTorchOn);
                    if (torchButton != null) {
                        torchButton.setText(isTorchOn ? "🔦 Izklop" : "🔦 Luč");
                    }
                    JSObject ret = new JSObject();
                    ret.put("torch", isTorchOn);
                    call.resolve(ret);
                } else {
                    call.reject("Kamera ni aktivna");
                }
            });
        }

        private void showScannerUI(String promptText) {
            dismissScanner();

            cameraExecutor = java.util.concurrent.Executors.newSingleThreadExecutor();

            BarcodeScannerOptions barcodeOptions = new BarcodeScannerOptions.Builder()
                .setBarcodeFormats(
                    Barcode.FORMAT_EAN_13,
                    Barcode.FORMAT_EAN_8,
                    Barcode.FORMAT_CODE_128,
                    Barcode.FORMAT_CODE_39,
                    Barcode.FORMAT_QR_CODE,
                    Barcode.FORMAT_UPC_A
                )
                .build();
            barcodeScanner = BarcodeScanning.getClient(barcodeOptions);
            textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);

            scannerDialog = new Dialog(getActivity(), android.R.style.Theme_Black_NoTitleBar_Fullscreen);
            scannerDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            scannerDialog.setCancelable(true);
            scannerDialog.setOnCancelListener(d -> {
                if (savedCall != null) {
                    JSObject ret = new JSObject();
                    ret.put("cancelled", true);
                    savedCall.resolve(ret);
                    savedCall = null;
                }
                cleanupCamera();
            });

            FrameLayout rootLayout = new FrameLayout(getActivity());
            rootLayout.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 
                ViewGroup.LayoutParams.MATCH_PARENT));
            rootLayout.setBackgroundColor(Color.BLACK);

            PreviewView previewView = new PreviewView(getActivity());
            previewView.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, 
                FrameLayout.LayoutParams.MATCH_PARENT));
            previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);
            rootLayout.addView(previewView);

            // Viewfinder HUD overlay
            ViewfinderOverlayView overlay = new ViewfinderOverlayView(getActivity());
            overlay.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, 
                FrameLayout.LayoutParams.MATCH_PARENT));
            rootLayout.addView(overlay);

            // Top Bar
            int scannerStatusBarPx = 0;
            try {
                int resId = getActivity().getResources().getIdentifier("status_bar_height", "dimen", "android");
                if (resId > 0) scannerStatusBarPx = getActivity().getResources().getDimensionPixelSize(resId);
            } catch (Throwable ignored) {}
            if (scannerStatusBarPx <= 0) {
                scannerStatusBarPx = (int) (32 * getActivity().getResources().getDisplayMetrics().density);
            }

            LinearLayout topBar = new LinearLayout(getActivity());
            topBar.setOrientation(LinearLayout.HORIZONTAL);
            topBar.setGravity(Gravity.CENTER_VERTICAL);
            topBar.setPadding(24, scannerStatusBarPx + 16, 24, 16);
            topBar.setBackgroundColor(Color.parseColor("#800b0f19"));
            FrameLayout.LayoutParams topBarParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, 
                FrameLayout.LayoutParams.WRAP_CONTENT, 
                Gravity.TOP);
            rootLayout.addView(topBar, topBarParams);

            Button closeBtn = new Button(getActivity());
            closeBtn.setText("✕");
            closeBtn.setTextSize(20);
            closeBtn.setTextColor(Color.WHITE);
            closeBtn.setBackgroundColor(Color.parseColor("#33ffffff"));
            closeBtn.setPadding(20, 10, 20, 10);
            closeBtn.setOnClickListener(v -> {
                if (savedCall != null) {
                    JSObject ret = new JSObject();
                    ret.put("cancelled", true);
                    savedCall.resolve(ret);
                    savedCall = null;
                }
                dismissScanner();
            });
            topBar.addView(closeBtn);

            promptTextView = new TextView(getActivity());
            promptTextView.setText(promptText);
            promptTextView.setTextColor(Color.WHITE);
            promptTextView.setTextSize(14);
            promptTextView.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams promptParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
            promptParams.setMargins(16, 0, 16, 0);
            promptTextView.setLayoutParams(promptParams);
            topBar.addView(promptTextView);

            torchButton = new Button(getActivity());
            torchButton.setText("🔦 Luč");
            torchButton.setTextSize(13);
            torchButton.setTextColor(Color.WHITE);
            torchButton.setBackgroundColor(Color.parseColor("#33ffffff"));
            torchButton.setPadding(20, 10, 20, 10);
            torchButton.setOnClickListener(v -> {
                if (cameraControl != null) {
                    isTorchOn = !isTorchOn;
                    cameraControl.enableTorch(isTorchOn);
                    torchButton.setText(isTorchOn ? "🔦 Izklop" : "🔦 Luč");
                }
            });
            topBar.addView(torchButton);

            // Bottom Bar with Zoom and Status
            LinearLayout bottomBar = new LinearLayout(getActivity());
            bottomBar.setOrientation(LinearLayout.VERTICAL);
            bottomBar.setGravity(Gravity.CENTER_HORIZONTAL);
            bottomBar.setPadding(24, 16, 24, 48);
            bottomBar.setBackgroundColor(Color.parseColor("#800b0f19"));
            FrameLayout.LayoutParams bottomBarParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, 
                FrameLayout.LayoutParams.WRAP_CONTENT, 
                Gravity.BOTTOM);
            rootLayout.addView(bottomBar, bottomBarParams);

            LinearLayout controlsRow = new LinearLayout(getActivity());
            controlsRow.setOrientation(LinearLayout.HORIZONTAL);
            controlsRow.setGravity(Gravity.CENTER);
            bottomBar.addView(controlsRow);

            Button zoomBtn = new Button(getActivity());
            zoomBtn.setText("🔍 1.0x");
            zoomBtn.setTextSize(13);
            zoomBtn.setTextColor(Color.WHITE);
            zoomBtn.setBackgroundColor(Color.parseColor("#3338bdf8"));
            zoomBtn.setPadding(24, 8, 24, 8);
            zoomBtn.setOnClickListener(v -> {
                if (cameraControl != null) {
                    if (currentZoom < 0.1f) {
                        currentZoom = 0.35f;
                        zoomBtn.setText("🔍 2.0x");
                    } else {
                        currentZoom = 0.0f;
                        zoomBtn.setText("🔍 1.0x");
                    }
                    cameraControl.setLinearZoom(currentZoom);
                }
            });
            controlsRow.addView(zoomBtn);

            TextView statusFooter = new TextView(getActivity());
            statusFooter.setText("Camera2 / CameraX strojni čitalnik • Zero-Copy ML Kit");
            statusFooter.setTextSize(11);
            statusFooter.setTextColor(Color.parseColor("#94a3b8"));
            statusFooter.setGravity(Gravity.CENTER);
            statusFooter.setPadding(0, 10, 0, 0);
            bottomBar.addView(statusFooter);

            // Tap to focus on preview
            previewView.setOnTouchListener((v, event) -> {
                if (event.getAction() == MotionEvent.ACTION_DOWN && cameraControl != null) {
                    MeteringPointFactory factory = previewView.getMeteringPointFactory();
                    MeteringPoint point = factory.createPoint(event.getX(), event.getY());
                    FocusMeteringAction action = new FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF)
                        .setAutoCancelDuration(3, java.util.concurrent.TimeUnit.SECONDS)
                        .build();
                    cameraControl.startFocusAndMetering(action);
                    return true;
                }
                return false;
            });

            scannerDialog.setContentView(rootLayout);
            scannerDialog.show();

            startCameraX(previewView);
        }

        private void startCameraX(PreviewView previewView) {
            ListenableFuture<ProcessCameraProvider> cameraProviderFuture = 
                ProcessCameraProvider.getInstance(getActivity());

            cameraProviderFuture.addListener(() -> {
                try {
                    cameraProvider = cameraProviderFuture.get();

                    Preview preview = new Preview.Builder().build();
                    preview.setSurfaceProvider(previewView.getSurfaceProvider());

                    ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setTargetResolution(new android.util.Size(1280, 720))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                    imageAnalysis.setAnalyzer(cameraExecutor, new ImageAnalysis.Analyzer() {
                        @androidx.camera.core.ExperimentalGetImage
                        @Override
                        public void analyze(ImageProxy imageProxy) {
                            android.media.Image mediaImage = imageProxy.getImage();
                            if (mediaImage == null) {
                                imageProxy.close();
                                return;
                            }

                            long now = System.currentTimeMillis();
                            if (now - lastScanTime < 650) {
                                imageProxy.close();
                                return;
                            }

                            InputImage image = InputImage.fromMediaImage(mediaImage, imageProxy.getImageInfo().getRotationDegrees());

                            // 1. Bliskovito skeniranje 1D/2D črtnih kod
                            barcodeScanner.process(image)
                                .addOnSuccessListener(barcodes -> {
                                    if (barcodes != null && !barcodes.isEmpty()) {
                                        Barcode b = barcodes.get(0);
                                        String val = b.getRawValue();
                                        if (val != null && !val.trim().isEmpty()) {
                                            lastScanTime = System.currentTimeMillis();
                                            handleScanSuccess(val.trim(), "barcode", b.getFormat());
                                            imageProxy.close();
                                            return;
                                        }
                                    }

                                    // 2. Če ni črtne kode, poskusi OCR za 7-mestne JYSK šifre
                                    textRecognizer.process(image)
                                        .addOnSuccessListener(visionText -> {
                                            if (visionText != null) {
                                                String fullText = visionText.getText();
                                                String sku = extractJyskSku(fullText);
                                                if (sku != null) {
                                                    lastScanTime = System.currentTimeMillis();
                                                    handleScanSuccess(sku, "ocr", 0);
                                                }
                                            }
                                        })
                                        .addOnCompleteListener(t -> imageProxy.close());
                                })
                                .addOnFailureListener(e -> imageProxy.close());
                        }
                    });

                    CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;
                    cameraProvider.unbindAll();

                    Camera camera = cameraProvider.bindToLifecycle(
                        (androidx.lifecycle.LifecycleOwner) getActivity(), 
                        cameraSelector, 
                        preview, 
                        imageAnalysis);

                    cameraControl = camera.getCameraControl();
                    cameraInfo = camera.getCameraInfo();

                } catch (Exception e) {
                    if (savedCall != null) {
                        savedCall.reject("Napaka pri zagonu CameraX: " + e.getMessage());
                        savedCall = null;
                    }
                    dismissScanner();
                }
            }, androidx.core.content.ContextCompat.getMainExecutor(getActivity()));
        }

        private String extractJyskSku(String text) {
            if (text == null || text.isEmpty()) return null;
            java.util.regex.Pattern p = java.util.regex.Pattern.compile("(?:artikel|art\\.?|šifra|sifra|koda)?\\s*\\b(3[0-9]{6}|4[0-9]{6}|[0-9]{7})\\b", java.util.regex.Pattern.CASE_INSENSITIVE);
            java.util.regex.Matcher m = p.matcher(text);
            if (m.find()) {
                return m.group(1);
            }
            return null;
        }

        private void handleScanSuccess(String code, String type, int format) {
            // Zvočni pisk
            try {
                android.media.ToneGenerator toneG = new android.media.ToneGenerator(android.media.AudioManager.STREAM_MUSIC, 100);
                toneG.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 75);
            } catch (Exception ignored) {}

            // Haptična vibracija
            try {
                android.os.Vibrator v = (android.os.Vibrator) getContext().getSystemService(Context.VIBRATOR_SERVICE);
                if (v != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        v.vibrate(android.os.VibrationEffect.createOneShot(45, android.os.VibrationEffect.DEFAULT_AMPLITUDE));
                    } else {
                        v.vibrate(45);
                    }
                }
            } catch (Exception ignored) {}

            getActivity().runOnUiThread(() -> {
                JSObject ret = new JSObject();
                ret.put("value", code);
                ret.put("type", type);
                ret.put("format", format);

                if (savedCall != null) {
                    savedCall.resolve(ret);
                    savedCall = null;
                }

                notifyListeners("onScanned", ret);

                if (!isContinuousMode) {
                    dismissScanner();
                }
            });
        }

        private void dismissScanner() {
            cleanupCamera();
            if (scannerDialog != null) {
                try {
                    scannerDialog.dismiss();
                } catch (Exception ignored) {}
                scannerDialog = null;
            }
        }

        private void cleanupCamera() {
            if (cameraProvider != null) {
                try {
                    cameraProvider.unbindAll();
                } catch (Exception ignored) {}
                cameraProvider = null;
            }
            if (cameraExecutor != null) {
                cameraExecutor.shutdown();
                cameraExecutor = null;
            }
            cameraControl = null;
            cameraInfo = null;
            if (barcodeScanner != null) {
                try { barcodeScanner.close(); } catch (Exception ignored) {}
                barcodeScanner = null;
            }
            if (textRecognizer != null) {
                try { textRecognizer.close(); } catch (Exception ignored) {}
                textRecognizer = null;
            }
        }

        // Viewfinder vizir overlay z izrezanim oknom
        public static class ViewfinderOverlayView extends View {
            private final Paint scrimPaint = new Paint();
            private final Paint boxPaint = new Paint();
            private final Paint cornerPaint = new Paint();
            private final RectF boxRect = new RectF();

            public ViewfinderOverlayView(Context context) {
                super(context);
                scrimPaint.setColor(Color.parseColor("#99000000"));

                boxPaint.setColor(Color.parseColor("#3338bdf8"));
                boxPaint.setStyle(Paint.Style.STROKE);
                boxPaint.setStrokeWidth(3f);

                cornerPaint.setColor(Color.parseColor("#38bdf8"));
                cornerPaint.setStyle(Paint.Style.STROKE);
                cornerPaint.setStrokeWidth(8f);
                cornerPaint.setStrokeCap(Paint.Cap.ROUND);
            }

            @Override
            protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);
                int width = getWidth();
                int height = getHeight();

                float boxWidth = width * 0.82f;
                float boxHeight = height * 0.32f;
                float left = (width - boxWidth) / 2f;
                float top = (height - boxHeight) / 2f;
                boxRect.set(left, top, left + boxWidth, top + boxHeight);

                // Temno ozadje zgoraj, spodaj, levo, desno
                canvas.drawRect(0, 0, width, top, scrimPaint);
                canvas.drawRect(0, top, left, top + boxHeight, scrimPaint);
                canvas.drawRect(left + boxWidth, top, width, top + boxHeight, scrimPaint);
                canvas.drawRect(0, top + boxHeight, width, height, scrimPaint);

                // Okvir vizirja
                canvas.drawRoundRect(boxRect, 16f, 16f, boxPaint);

                // Kotne oznake
                float cornerLen = 32f;
                // Zgoraj levo
                canvas.drawLine(left, top + cornerLen, left, top, cornerPaint);
                canvas.drawLine(left, top, left + cornerLen, top, cornerPaint);
                // Zgoraj desno
                canvas.drawLine(left + boxWidth - cornerLen, top, left + boxWidth, top, cornerPaint);
                canvas.drawLine(left + boxWidth, top, left + boxWidth, top + cornerLen, cornerPaint);
                // Spodaj levo
                canvas.drawLine(left, top + boxHeight - cornerLen, left, top + boxHeight, cornerPaint);
                canvas.drawLine(left, top + boxHeight, left + cornerLen, top + boxHeight, cornerPaint);
                // Spodaj desno
                canvas.drawLine(left + boxWidth - cornerLen, top + boxHeight, left + boxWidth, top + boxHeight, cornerPaint);
                canvas.drawLine(left + boxWidth, top + boxHeight - cornerLen, left + boxWidth, top + boxHeight, cornerPaint);
            }
        }
    }

    @CapacitorPlugin(name = "BLESync")
    public static class BLESyncPlugin extends Plugin {
        private static final UUID SERVICE_UUID = UUID.fromString("0000FE55-0000-1000-8000-00805F9B34FB");
        private static final UUID CHAR_DATA_UUID = UUID.fromString("0000FE56-0000-1000-8000-00805F9B34FB");
        private static final UUID CHAR_CTRL_UUID = UUID.fromString("0000FE57-0000-1000-8000-00805F9B34FB");
        private static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB");
        private static final byte[] EOF_MARKER = "__EOF__".getBytes(StandardCharsets.UTF_8);
        private static final byte[] CLIENT_EOF = "__C_EOF__".getBytes(StandardCharsets.UTF_8);
        private static final byte[] SERVER_EOF = "__S_EOF__".getBytes(StandardCharsets.UTF_8);

        private BluetoothManager bluetoothManager;
        private BluetoothAdapter bluetoothAdapter;
        private BluetoothLeAdvertiser advertiser;
        private BluetoothGattServer gattServer;
        private BluetoothLeScanner scanner;
        private BluetoothGatt connectedGatt;

        // Ročna sinhronizacija (Legacy / Manual)
        private byte[] payloadToSend = null;
        private ByteArrayOutputStream receiveBuffer = null;
        private PluginCall activeSendCall = null;
        private PluginCall activeReceiveCall = null;
        private Handler mainHandler = new Handler(Looper.getMainLooper());
        private Runnable timeoutRunnable = null;

        // Samodejna MESH sinhronizacija brez klikanja (Auto Peer-to-Peer)
        private String myDeviceId = "0000";
        private int myDbVersion = 1;
        private int myStateHash = 0;
        private byte[] myPayloadCompressed = null;
        private final AtomicBoolean isCurrentlySyncing = new AtomicBoolean(false);
        private final Map<String, Long> lastSyncWithPeer = new ConcurrentHashMap<>();
        private final Map<String, Integer> lastPeerHash = new ConcurrentHashMap<>();
        private final Map<String, Integer> lastMyHash = new ConcurrentHashMap<>();
        private final ByteArrayOutputStream serverIncomingBuffer = new ByteArrayOutputStream();
        private final ByteArrayOutputStream clientIncomingBuffer = new ByteArrayOutputStream();
        private BluetoothGatt activeGattClient = null;
        private String currentConnectedPeerId = "";
        private int currentConnectedPeerHash = 0;
        private boolean isAutoSyncRunning = false;
        private Runnable autoClientTimeoutRunnable = null;
        private AdvertiseCallback autoAdvertiseCallback = null;
        private ScanCallback autoScanCallback = null;

        private boolean hasPermissions() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                return ActivityCompat.checkSelfPermission(getContext(), Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                       ActivityCompat.checkSelfPermission(getContext(), Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED &&
                       ActivityCompat.checkSelfPermission(getContext(), Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
            } else {
                return ActivityCompat.checkSelfPermission(getContext(), Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED &&
                       ActivityCompat.checkSelfPermission(getContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
            }
        }

        private void requestBlePermissions() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ActivityCompat.requestPermissions(getActivity(), new String[]{
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_ADVERTISE,
                    Manifest.permission.BLUETOOTH_CONNECT
                }, 101);
            } else {
                ActivityCompat.requestPermissions(getActivity(), new String[]{
                    Manifest.permission.BLUETOOTH,
                    Manifest.permission.BLUETOOTH_ADMIN,
                    Manifest.permission.ACCESS_FINE_LOCATION
                }, 101);
            }
        }

        private byte[] compress(String str) {
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                GZIPOutputStream gzip = new GZIPOutputStream(out);
                gzip.write(str.getBytes(StandardCharsets.UTF_8));
                gzip.close();
                return out.toByteArray();
            } catch (Exception e) {
                return str.getBytes(StandardCharsets.UTF_8);
            }
        }

        private String decompress(byte[] bytes) {
            try {
                ByteArrayInputStream in = new ByteArrayInputStream(bytes);
                GZIPInputStream gzip = new GZIPInputStream(in);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[1024];
                int len;
                while ((len = gzip.read(buf)) > 0) {
                    out.write(buf, 0, len);
                }
                gzip.close();
                return out.toString("UTF-8");
            } catch (Exception e) {
                return new String(bytes, StandardCharsets.UTF_8);
            }
        }

        private AdvertiseData buildAdvertiseData(String devId, int stateHash) {
            byte[] sData = new byte[8];
            byte[] idBytes = devId.getBytes(StandardCharsets.US_ASCII);
            for (int i = 0; i < 4; i++) {
                sData[i] = (i < idBytes.length) ? idBytes[i] : (byte) '_';
            }
            sData[4] = (byte) ((stateHash >> 24) & 0xFF);
            sData[5] = (byte) ((stateHash >> 16) & 0xFF);
            sData[6] = (byte) ((stateHash >> 8) & 0xFF);
            sData[7] = (byte) (stateHash & 0xFF);

            return new AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .addServiceUuid(new ParcelUuid(SERVICE_UUID))
                .addServiceData(new ParcelUuid(SERVICE_UUID), sData)
                .build();
        }

        /* =========================================================================
         * SAMODEJNA BLE SINHRONIZACIJA V OZADJU (Zero-click Peer-to-Peer Mesh)
         * ========================================================================= */
        @PluginMethod
        public void startAutoSync(PluginCall call) {
            if (!hasPermissions()) {
                requestBlePermissions();
                call.reject("Dovoljenja za Bluetooth še niso potrjena.");
                return;
            }

            myDeviceId = call.getString("deviceId", "0000");
            myDbVersion = call.getInt("dbVersion", 1);
            myStateHash = call.getInt("stateHash", (int) System.currentTimeMillis());
            String initialData = call.getString("data", "");
            if (initialData != null && !initialData.isEmpty()) {
                myPayloadCompressed = compress(initialData);
            }

            bluetoothManager = (BluetoothManager) getContext().getSystemService(Context.BLUETOOTH_SERVICE);
            if (bluetoothManager == null) {
                call.reject("Bluetooth ni na voljo.");
                return;
            }
            bluetoothAdapter = bluetoothManager.getAdapter();
            if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
                call.reject("Bluetooth je izklopljen.");
                return;
            }

            // 1. Nastavi ali posodobi GATT Server
            setupGattServer();

            // 2. Zaženi periodično oddajanje (Advertising)
            startAutoAdvertising();

            // 3. Zaženi skeniranje za sosednjimi napravami (Scanning)
            startAutoScanning();

            isAutoSyncRunning = true;
            JSObject ret = new JSObject();
            ret.put("success", true);
            ret.put("deviceId", myDeviceId);
            ret.put("dbVersion", myDbVersion);
            ret.put("stateHash", myStateHash);
            call.resolve(ret);
        }

        @PluginMethod
        public void updateSyncPayload(PluginCall call) {
            myDbVersion = call.getInt("dbVersion", myDbVersion);
            myStateHash = call.getInt("stateHash", (int) System.currentTimeMillis());
            String newData = call.getString("data", "");
            if (newData != null && !newData.isEmpty()) {
                myPayloadCompressed = compress(newData);
            }

            // Posodobi oddajni paket z novim stateHash (tudi če je verzija enaka, se hash spremeni)
            if (isAutoSyncRunning && advertiser != null) {
                try {
                    advertiser.stopAdvertising(autoAdvertiseCallback);
                } catch (Exception ignored) {}
                startAutoAdvertising();
            }

            JSObject ret = new JSObject();
            ret.put("success", true);
            ret.put("dbVersion", myDbVersion);
            ret.put("stateHash", myStateHash);
            call.resolve(ret);
        }

        @PluginMethod
        public void stopAutoSync(PluginCall call) {
            isAutoSyncRunning = false;
            stopAutoAdvertising();
            stopAutoScanning();
            closeClientGatt();

            JSObject ret = new JSObject();
            ret.put("success", true);
            call.resolve(ret);
        }

        private void setupGattServer() {
            if (gattServer != null) return;
            try {
                gattServer = bluetoothManager.openGattServer(getContext(), new BluetoothGattServerCallback() {
                    @Override
                    public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
                        // Odjemalec se je povezal ali prekinil
                    }

                    @Override
                    public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId, BluetoothGattCharacteristic characteristic, boolean preparedWrite, boolean responseNeeded, int offset, byte[] value) {
                        if (responseNeeded) {
                            try {
                                gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value);
                            } catch (SecurityException ignored) {}
                        }

                        if (value == null || value.length == 0) return;

                        if (Arrays.equals(value, CLIENT_EOF)) {
                            // Client je zaključil pošiljanje svojega stanja
                            byte[] receivedBytes = serverIncomingBuffer.toByteArray();
                            serverIncomingBuffer.reset();
                            String clientJson = decompress(receivedBytes);

                            mainHandler.post(() -> {
                                JSObject event = new JSObject();
                                event.put("peerId", "peer");
                                event.put("json", clientJson);
                                notifyListeners("autoSyncReceived", event);

                                JSObject st = new JSObject();
                                st.put("status", "synced");
                                st.put("role", "server");
                                notifyListeners("autoSyncStatus", st);
                            });

                            lastSyncWithPeer.put(device.getAddress(), System.currentTimeMillis());

                            // Server sedaj pošlje svoje podatke nazaj Clientu (dvosmerna uskladitev)
                            new Thread(() -> {
                                try {
                                    BluetoothGattService svc = gattServer.getService(SERVICE_UUID);
                                    if (svc == null) return;
                                    BluetoothGattCharacteristic dataChar = svc.getCharacteristic(CHAR_DATA_UUID);
                                    if (dataChar == null) return;

                                    byte[] payload = myPayloadCompressed;
                                    if (payload != null && payload.length > 0) {
                                        int chunkSize = 240;
                                        int totalLen = payload.length;
                                        int curOffset = 0;
                                        while (curOffset < totalLen) {
                                            int cur = Math.min(chunkSize, totalLen - curOffset);
                                            byte[] chunk = Arrays.copyOfRange(payload, curOffset, curOffset + cur);
                                            dataChar.setValue(chunk);
                                            try {
                                                gattServer.notifyCharacteristicChanged(device, dataChar, false);
                                            } catch (SecurityException ignored) {}
                                            curOffset += cur;
                                            Thread.sleep(15);
                                        }
                                    }
                                    Thread.sleep(30);
                                    dataChar.setValue(SERVER_EOF);
                                    try {
                                        gattServer.notifyCharacteristicChanged(device, dataChar, false);
                                    } catch (SecurityException ignored) {}
                                } catch (Exception ignored) {}
                            }).start();

                        } else if (Arrays.equals(value, "START".getBytes(StandardCharsets.UTF_8))) {
                            // Ročni način (legacy)
                            streamPayloadToDevice(device);
                        } else {
                            serverIncomingBuffer.write(value, 0, value.length);
                        }
                    }

                    @Override
                    public void onDescriptorWriteRequest(BluetoothDevice device, int requestId, BluetoothGattDescriptor descriptor, boolean preparedWrite, boolean responseNeeded, int offset, byte[] value) {
                        if (responseNeeded) {
                            try {
                                gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value);
                            } catch (SecurityException ignored) {}
                        }
                    }
                });

                BluetoothGattService service = new BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY);
                BluetoothGattCharacteristic dataChar = new BluetoothGattCharacteristic(
                    CHAR_DATA_UUID,
                    BluetoothGattCharacteristic.PROPERTY_READ | BluetoothGattCharacteristic.PROPERTY_WRITE | BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                    BluetoothGattCharacteristic.PERMISSION_READ | BluetoothGattCharacteristic.PERMISSION_WRITE
                );
                BluetoothGattDescriptor cccd = new BluetoothGattDescriptor(CCCD_UUID, BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE);
                dataChar.addDescriptor(cccd);
                service.addCharacteristic(dataChar);

                BluetoothGattCharacteristic ctrlChar = new BluetoothGattCharacteristic(
                    CHAR_CTRL_UUID,
                    BluetoothGattCharacteristic.PROPERTY_READ | BluetoothGattCharacteristic.PROPERTY_WRITE,
                    BluetoothGattCharacteristic.PERMISSION_READ | BluetoothGattCharacteristic.PERMISSION_WRITE
                );
                service.addCharacteristic(ctrlChar);

                gattServer.addService(service);
            } catch (SecurityException ignored) {}
        }

        private void startAutoAdvertising() {
            if (bluetoothAdapter == null) return;
            advertiser = bluetoothAdapter.getBluetoothLeAdvertiser();
            if (advertiser == null) return;

            AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                .setConnectable(true)
                .setTimeout(0) // neomejeno delovanje v ozadju
                .build();

            AdvertiseData data = buildAdvertiseData(myDeviceId, myStateHash);

            autoAdvertiseCallback = new AdvertiseCallback() {
                @Override
                public void onStartSuccess(AdvertiseSettings settingsInEffect) {}

                @Override
                public void onStartFailure(int errorCode) {}
            };

            try {
                advertiser.startAdvertising(settings, data, autoAdvertiseCallback);
            } catch (SecurityException ignored) {}
        }

        private void stopAutoAdvertising() {
            try {
                if (advertiser != null && autoAdvertiseCallback != null) {
                    advertiser.stopAdvertising(autoAdvertiseCallback);
                    autoAdvertiseCallback = null;
                }
            } catch (Exception ignored) {}
        }

        private void startAutoScanning() {
            if (bluetoothAdapter == null) return;
            scanner = bluetoothAdapter.getBluetoothLeScanner();
            if (scanner == null) return;

            ScanFilter filter = new ScanFilter.Builder()
                .setServiceUuid(new ParcelUuid(SERVICE_UUID))
                .build();

            ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                .build();

            autoScanCallback = new ScanCallback() {
                @Override
                public void onScanResult(int callbackType, ScanResult result) {
                    if (result == null || result.getScanRecord() == null) return;

                    byte[] sData = result.getScanRecord().getServiceData(new ParcelUuid(SERVICE_UUID));
                    if (sData == null || sData.length < 8) return;

                    String peerId = new String(sData, 0, 4, StandardCharsets.US_ASCII);
                    int peerHash = ((sData[4] & 0xFF) << 24) |
                                   ((sData[5] & 0xFF) << 16) |
                                   ((sData[6] & 0xFF) << 8) |
                                   (sData[7] & 0xFF);

                    if (peerId.equals(myDeviceId)) return; // lasten signal

                    // Preveri časovni interval (cooldown):
                    // Če sta oba skladiščnika poskenirala en izdelek po zadnjem osveževanju, je številka verzije
                    // sicer lahko enaka, toda njun stateHash bo različen! Zato se bo uskladitev zanesljivo sprožila.
                    Long lastTime = lastSyncWithPeer.get(peerId);
                    Integer lastPH = lastPeerHash.get(peerId);
                    Integer lastMH = lastMyHash.get(peerId);
                    if (lastTime != null && (System.currentTimeMillis() - lastTime < 30000L) &&
                        lastPH != null && lastPH == peerHash &&
                        lastMH != null && lastMH == myStateHash) {
                        return;
                    }

                    if (isCurrentlySyncing.get()) return;

                    // Določitev vloge (Collision-Free Master/Slave Arbitration):
                    // Samo naprava z manjšo alfanumerično kodo sproži povezavo (Client), druga čaka kot Server!
                    if (myDeviceId.compareTo(peerId) < 0) {
                        if (isCurrentlySyncing.compareAndSet(false, true)) {
                            currentConnectedPeerId = peerId;
                            currentConnectedPeerHash = peerHash;
                            connectAsAutoClient(result.getDevice());
                        }
                    }
                }

                @Override
                public void onScanFailed(int errorCode) {}
            };

            try {
                scanner.startScan(Collections.singletonList(filter), settings, autoScanCallback);
            } catch (SecurityException ignored) {}
        }

        private void stopAutoScanning() {
            try {
                if (scanner != null && autoScanCallback != null) {
                    scanner.stopScan(autoScanCallback);
                    autoScanCallback = null;
                }
            } catch (Exception ignored) {}
        }

        private void connectAsAutoClient(BluetoothDevice device) {
            mainHandler.post(() -> {
                JSObject st = new JSObject();
                st.put("status", "syncing");
                st.put("peerId", currentConnectedPeerId);
                st.put("role", "client");
                notifyListeners("autoSyncStatus", st);
            });

            // 12-sekundni varnostni timeout
            if (autoClientTimeoutRunnable != null) {
                mainHandler.removeCallbacks(autoClientTimeoutRunnable);
            }
            autoClientTimeoutRunnable = this::closeClientGatt;
            mainHandler.postDelayed(autoClientTimeoutRunnable, 12000);

            try {
                activeGattClient = device.connectGatt(getContext(), false, new BluetoothGattCallback() {
                    @Override
                    public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
                        if (newState == BluetoothProfile.STATE_CONNECTED) {
                            try {
                                gatt.requestMtu(512);
                            } catch (SecurityException ignored) {}
                        } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                            closeClientGatt();
                        }
                    }

                    @Override
                    public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
                        try {
                            gatt.discoverServices();
                        } catch (SecurityException ignored) {}
                    }

                    @Override
                    public void onServicesDiscovered(BluetoothGatt gatt, int status) {
                        if (status != BluetoothGatt.GATT_SUCCESS) {
                            closeClientGatt();
                            return;
                        }
                        BluetoothGattService svc = gatt.getService(SERVICE_UUID);
                        if (svc == null) {
                            closeClientGatt();
                            return;
                        }
                        BluetoothGattCharacteristic dataChar = svc.getCharacteristic(CHAR_DATA_UUID);
                        if (dataChar == null) {
                            closeClientGatt();
                            return;
                        }

                        try {
                            // Nastavi poslušanje odgovora s strežnika
                            gatt.setCharacteristicNotification(dataChar, true);
                            BluetoothGattDescriptor desc = dataChar.getDescriptor(CCCD_UUID);
                            if (desc != null) {
                                desc.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                                gatt.writeDescriptor(desc);
                            }
                        } catch (SecurityException e) {
                            closeClientGatt();
                        }
                    }

                    @Override
                    public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
                        // Obveščanje je aktivno -> Client odda svoj payload Serverju
                        new Thread(() -> {
                            try {
                                BluetoothGattService svc = gatt.getService(SERVICE_UUID);
                                if (svc == null) return;
                                BluetoothGattCharacteristic dataChar = svc.getCharacteristic(CHAR_DATA_UUID);
                                if (dataChar == null) return;

                                byte[] payload = myPayloadCompressed;
                                if (payload != null && payload.length > 0) {
                                    int chunkSize = 240;
                                    int totalLen = payload.length;
                                    int offset = 0;
                                    while (offset < totalLen) {
                                        int cur = Math.min(chunkSize, totalLen - offset);
                                        byte[] chunk = Arrays.copyOfRange(payload, offset, offset + cur);
                                        dataChar.setValue(chunk);
                                        try {
                                            gatt.writeCharacteristic(dataChar);
                                        } catch (SecurityException ignored) {}
                                        offset += cur;
                                        Thread.sleep(15);
                                    }
                                }
                                Thread.sleep(30);
                                dataChar.setValue(CLIENT_EOF);
                                try {
                                    gatt.writeCharacteristic(dataChar);
                                } catch (SecurityException ignored) {}
                            } catch (Exception e) {
                                closeClientGatt();
                            }
                        }).start();
                    }

                    @Override
                    public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
                        byte[] val = characteristic.getValue();
                        if (val == null || val.length == 0) return;

                        if (Arrays.equals(val, SERVER_EOF)) {
                            byte[] fullBytes = clientIncomingBuffer.toByteArray();
                            clientIncomingBuffer.reset();
                            String serverJson = decompress(fullBytes);

                            mainHandler.post(() -> {
                                JSObject ret = new JSObject();
                                ret.put("peerId", currentConnectedPeerId);
                                ret.put("json", serverJson);
                                notifyListeners("autoSyncReceived", ret);

                                JSObject st = new JSObject();
                                st.put("status", "synced");
                                st.put("peerId", currentConnectedPeerId);
                                st.put("role", "client");
                                notifyListeners("autoSyncStatus", st);
                            });

                            lastSyncWithPeer.put(currentConnectedPeerId, System.currentTimeMillis());
                            lastPeerHash.put(currentConnectedPeerId, currentConnectedPeerHash);
                            lastMyHash.put(currentConnectedPeerId, myStateHash);

                            try {
                                gatt.disconnect();
                            } catch (SecurityException ignored) {}
                        } else {
                            clientIncomingBuffer.write(val, 0, val.length);
                        }
                    }
                }, BluetoothDevice.TRANSPORT_LE);
            } catch (SecurityException e) {
                closeClientGatt();
            }
        }

        private void closeClientGatt() {
            if (autoClientTimeoutRunnable != null) {
                mainHandler.removeCallbacks(autoClientTimeoutRunnable);
                autoClientTimeoutRunnable = null;
            }
            if (activeGattClient != null) {
                try {
                    activeGattClient.disconnect();
                    activeGattClient.close();
                } catch (Exception ignored) {}
                activeGattClient = null;
            }
            clientIncomingBuffer.reset();
            isCurrentlySyncing.set(false);
        }

        /* =========================================================================
         * ROČNA SINHRONIZACIJA (Legacy Modal: Pošlji / Prejmi)
         * ========================================================================= */
        @PluginMethod
        public void startSending(PluginCall call) {
            if (!hasPermissions()) {
                requestBlePermissions();
                call.reject("Dovoljenja za Bluetooth še niso potrjena.");
                return;
            }

            cleanup();
            activeSendCall = call;

            String jsonData = call.getString("data");
            if (jsonData == null || jsonData.isEmpty()) {
                call.reject("Manjkajo podatki za pošiljanje.");
                return;
            }

            payloadToSend = compress(jsonData);

            bluetoothManager = (BluetoothManager) getContext().getSystemService(Context.BLUETOOTH_SERVICE);
            if (bluetoothManager == null) {
                call.reject("Bluetooth na napravi ni na voljo.");
                return;
            }
            bluetoothAdapter = bluetoothManager.getAdapter();
            if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
                call.reject("Bluetooth je izklopljen. Prosimo vklopite Bluetooth.");
                return;
            }

            setupGattServer();

            advertiser = bluetoothAdapter.getBluetoothLeAdvertiser();
            if (advertiser == null) {
                call.reject("Ta naprava ne podpira BLE oddajanja.");
                return;
            }

            AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(true)
                .setTimeout(60000)
                .build();

            AdvertiseData data = new AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .addServiceUuid(new ParcelUuid(SERVICE_UUID))
                .build();

            try {
                advertiser.startAdvertising(settings, data, new AdvertiseCallback() {
                    @Override
                    public void onStartSuccess(AdvertiseSettings settingsInEffect) {}

                    @Override
                    public void onStartFailure(int errorCode) {
                        if (activeSendCall != null) {
                            activeSendCall.reject("Oddajanje BLE ni uspelo (koda: " + errorCode + ")");
                            activeSendCall = null;
                        }
                        cleanup();
                    }
                });
            } catch (SecurityException e) {
                call.reject("Varnostna napaka: " + e.getMessage());
            }

            timeoutRunnable = () -> {
                if (activeSendCall != null) {
                    activeSendCall.reject("Čas za povezavo je potekel.");
                    activeSendCall = null;
                }
                cleanup();
            };
            mainHandler.postDelayed(timeoutRunnable, 60000);
        }

        private void streamPayloadToDevice(BluetoothDevice device) {
            new Thread(() -> {
                try {
                    BluetoothGattService svc = gattServer.getService(SERVICE_UUID);
                    if (svc == null) return;
                    BluetoothGattCharacteristic dataChar = svc.getCharacteristic(CHAR_DATA_UUID);
                    if (dataChar == null) return;

                    byte[] p = payloadToSend != null ? payloadToSend : myPayloadCompressed;
                    if (p == null) return;

                    int chunkSize = 240;
                    int totalLen = p.length;
                    int offset = 0;

                    while (offset < totalLen) {
                        int currentChunk = Math.min(chunkSize, totalLen - offset);
                        byte[] chunk = Arrays.copyOfRange(p, offset, offset + currentChunk);
                        dataChar.setValue(chunk);
                        try {
                            gattServer.notifyCharacteristicChanged(device, dataChar, false);
                        } catch (SecurityException ignored) {}
                        offset += currentChunk;
                        Thread.sleep(15);
                    }

                    Thread.sleep(30);
                    dataChar.setValue(EOF_MARKER);
                    try {
                        gattServer.notifyCharacteristicChanged(device, dataChar, false);
                    } catch (SecurityException ignored) {}

                    mainHandler.post(() -> {
                        if (activeSendCall != null) {
                            JSObject ret = new JSObject();
                            ret.put("success", true);
                            ret.put("bytesSent", totalLen);
                            activeSendCall.resolve(ret);
                            activeSendCall = null;
                        }
                    });
                } catch (Exception e) {
                    mainHandler.post(() -> {
                        if (activeSendCall != null) {
                            activeSendCall.reject("Napaka pri prenosu: " + e.getMessage());
                            activeSendCall = null;
                        }
                    });
                }
            }).start();
        }

        @PluginMethod
        public void startReceiving(PluginCall call) {
            if (!hasPermissions()) {
                requestBlePermissions();
                call.reject("Dovoljenja za Bluetooth še niso potrjena.");
                return;
            }

            cleanup();
            activeReceiveCall = call;
            receiveBuffer = new ByteArrayOutputStream();

            bluetoothManager = (BluetoothManager) getContext().getSystemService(Context.BLUETOOTH_SERVICE);
            if (bluetoothManager == null) {
                call.reject("Bluetooth ni na voljo.");
                return;
            }
            bluetoothAdapter = bluetoothManager.getAdapter();
            if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
                call.reject("Bluetooth je izklopljen.");
                return;
            }

            scanner = bluetoothAdapter.getBluetoothLeScanner();
            if (scanner == null) {
                call.reject("BLE skener ni na voljo.");
                return;
            }

            ScanFilter filter = new ScanFilter.Builder()
                .setServiceUuid(new ParcelUuid(SERVICE_UUID))
                .build();

            ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();

            try {
                scanner.startScan(Collections.singletonList(filter), settings, new ScanCallback() {
                    @Override
                    public void onScanResult(int callbackType, ScanResult result) {
                        BluetoothDevice device = result.getDevice();
                        if (device != null) {
                            try {
                                scanner.stopScan(this);
                            } catch (SecurityException ignored) {}
                            connectToSender(device);
                        }
                    }

                    @Override
                    public void onScanFailed(int errorCode) {
                        if (activeReceiveCall != null) {
                            activeReceiveCall.reject("Skeniranje ni uspelo (koda: " + errorCode + ")");
                            activeReceiveCall = null;
                        }
                        cleanup();
                    }
                });
            } catch (SecurityException e) {
                call.reject("Varnostna napaka: " + e.getMessage());
                return;
            }

            timeoutRunnable = () -> {
                if (activeReceiveCall != null) {
                    activeReceiveCall.reject("Ni bilo mogoče najti naprave v bližini.");
                    activeReceiveCall = null;
                }
                cleanup();
            };
            mainHandler.postDelayed(timeoutRunnable, 40000);
        }

        private void connectToSender(BluetoothDevice device) {
            try {
                connectedGatt = device.connectGatt(getContext(), false, new BluetoothGattCallback() {
                    @Override
                    public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
                        if (newState == BluetoothProfile.STATE_CONNECTED) {
                            try {
                                gatt.requestMtu(512);
                            } catch (SecurityException ignored) {}
                        } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                            if (activeReceiveCall != null) {
                                activeReceiveCall.reject("Povezava je bila prekinjena.");
                                activeReceiveCall = null;
                            }
                            cleanup();
                        }
                    }

                    @Override
                    public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
                        try {
                            gatt.discoverServices();
                        } catch (SecurityException ignored) {}
                    }

                    @Override
                    public void onServicesDiscovered(BluetoothGatt gatt, int status) {
                        if (status != BluetoothGatt.GATT_SUCCESS) return;
                        BluetoothGattService svc = gatt.getService(SERVICE_UUID);
                        if (svc == null) return;
                        BluetoothGattCharacteristic dataChar = svc.getCharacteristic(CHAR_DATA_UUID);
                        BluetoothGattCharacteristic ctrlChar = svc.getCharacteristic(CHAR_CTRL_UUID);
                        if (dataChar == null || ctrlChar == null) return;

                        try {
                            gatt.setCharacteristicNotification(dataChar, true);
                            BluetoothGattDescriptor desc = dataChar.getDescriptor(CCCD_UUID);
                            if (desc != null) {
                                desc.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                                gatt.writeDescriptor(desc);
                            }

                            mainHandler.postDelayed(() -> {
                                try {
                                    ctrlChar.setValue("START".getBytes(StandardCharsets.UTF_8));
                                    gatt.writeCharacteristic(ctrlChar);
                                } catch (SecurityException ignored) {}
                            }, 100);
                        } catch (SecurityException ignored) {}
                    }

                    @Override
                    public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
                        byte[] val = characteristic.getValue();
                        if (val == null || val.length == 0) return;

                        if (Arrays.equals(val, EOF_MARKER)) {
                            mainHandler.removeCallbacks(timeoutRunnable);
                            byte[] fullBytes = receiveBuffer.toByteArray();
                            String json = decompress(fullBytes);

                            mainHandler.post(() -> {
                                if (activeReceiveCall != null) {
                                    JSObject ret = new JSObject();
                                    ret.put("success", true);
                                    ret.put("json", json);
                                    ret.put("bytesReceived", fullBytes.length);
                                    activeReceiveCall.resolve(ret);
                                    activeReceiveCall = null;
                                }
                                cleanup();
                            });
                        } else {
                            receiveBuffer.write(val, 0, val.length);
                        }
                    }
                }, BluetoothDevice.TRANSPORT_LE);
            } catch (SecurityException e) {
                if (activeReceiveCall != null) {
                    activeReceiveCall.reject("Varnostna napaka: " + e.getMessage());
                    activeReceiveCall = null;
                }
                cleanup();
            }
        }

        @PluginMethod
        public void cancel(PluginCall call) {
            cleanup();
            JSObject ret = new JSObject();
            ret.put("success", true);
            call.resolve(ret);
        }

        private void cleanup() {
            if (timeoutRunnable != null) {
                mainHandler.removeCallbacks(timeoutRunnable);
                timeoutRunnable = null;
            }
            try {
                if (scanner != null && autoScanCallback == null) {
                    scanner.stopScan(new ScanCallback() {});
                    scanner = null;
                }
            } catch (Exception ignored) {}

            try {
                if (advertiser != null && autoAdvertiseCallback == null) {
                    advertiser.stopAdvertising(new AdvertiseCallback() {});
                    advertiser = null;
                }
            } catch (Exception ignored) {}

            try {
                if (connectedGatt != null) {
                    connectedGatt.disconnect();
                    connectedGatt.close();
                    connectedGatt = null;
                }
            } catch (Exception ignored) {}

            payloadToSend = null;
            receiveBuffer = null;
        }
    }
}
