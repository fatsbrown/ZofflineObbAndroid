package com.zoffline.zofflineobb;

import androidx.activity.EdgeToEdge;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import android.Manifest;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;


import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.io.Writer;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static android.os.Build.VERSION.SDK_INT;
import static android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Android 15+ (targetSdk 35+) enforces edge-to-edge; handle the window insets explicitly.
        EdgeToEdge.enable(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        View content = findViewById(android.R.id.content);
        ViewCompat.setOnApplyWindowInsetsListener(content, (v, windowInsets) -> {
            Insets bars = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });
    }

    /**
     * Opens a system settings screen without ever crashing the app: first the app-specific screen,
     * then a generic one, and finally the app details page. Some ROMs don't resolve every intent.
     */
    private void openPermissionScreen(String appSpecificAction, String genericAction) {
        Uri packageUri = Uri.parse("package:" + getPackageName());
        try {
            startActivity(new Intent(appSpecificAction, packageUri));
            return;
        } catch (Exception e) {
            addInfo("Could not open the app-specific settings screen: " + e);
        }
        try {
            startActivity(new Intent(genericAction));
            return;
        } catch (Exception e) {
            addInfo("Could not open the generic settings screen: " + e);
        }
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri));
        } catch (Exception e) {
            addInfo("Could not open any settings screen. Please grant the permission manually in the system settings for this app.");
        }
    }

    private void setButtonEnabled(boolean enabled) {
        runOnUiThread(() -> {
            Button b = findViewById(R.id.patch_obb);
            if (b != null) b.setEnabled(enabled);
        });
    }

    @RequiresApi(api = Build.VERSION_CODES.R)
    public void patchObb(View view) throws IOException {
        // Runtime storage permissions only exist/matter on Android 9 and 10 (see the manifest)
        if (SDK_INT < 30) {
            ActivityCompat.requestPermissions(this, new String[]{
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
            }, 1);
        }

        setButtonEnabled(false);

        AssetManager assetManager = this.getAssets();

        if(SDK_INT >= 30) {
            if (!Environment.isExternalStorageManager()) {
                addInfo("Please allow access to all files");
                openPermissionScreen(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                setButtonEnabled(true);
                return;
            }
        }

        if(!getPackageManager().canRequestPackageInstalls()) {
            addInfo("Please allow app to install APK (to access OBB folder)");
            openPermissionScreen(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
            setButtonEnabled(true);
            return;
        }

        Thread t1 = new Thread(() -> {
            addInfo("Started...");

            addInfo("Locating file...");
            File sdCardRoot = Environment.getExternalStorageDirectory();
            File folder = new File(sdCardRoot, "Android/obb/com.zwift.zwiftgame");
            File[] files = folder.listFiles((dir, name) -> name.toLowerCase().endsWith("zwiftgame.obb"));

            if(files != null && files.length > 0) {
                File obb_file = files[0];
                for (File f : files) {
                    addInfo("Found OBB: " + f.getName() + " (" + f.length() + " bytes)");
                }
                if (files.length > 1) {
                    addInfo("WARNING: more than one OBB matched; using " + obb_file.getName());
                }
                if(obb_file == null) {
                    addInfo("File not found.");
                    setButtonEnabled(true);
                    return;
                }

                // The OBB is a regular ZIP file, so it is read directly (no intermediate copy needed)
                long org_size = obb_file.length();
                addInfo("OBB size: " + org_size + " bytes");

                //Unzip
                addInfo("Unzipping, this will take a while...");
                String extract_dir = obb_file.getParent() + "/extracted/";
                if (!unzip(obb_file.getPath(), extract_dir)) {
                    addInfo("Extraction failed. The original OBB was NOT changed.");
                    deleteDirectory(new File(extract_dir));
                    setButtonEnabled(true);
                    return;
                }
                if (!new File(extract_dir + "/dataES").isDirectory()) {
                    addInfo("Folder dataES not found after extraction. The original OBB was NOT changed.");
                    deleteDirectory(new File(extract_dir));
                    setButtonEnabled(true);
                    return;
                }

                //Append the zoffline certificate to the cacert.pem that ships inside the OBB
                addInfo("Adding zoffline certificate to cacert.pem...");
                File cert_dest = new File(extract_dir + "/dataES/cacert.pem");
                if (!cert_dest.isFile()) {
                    addInfo("dataES/cacert.pem not found inside the OBB. The original OBB was NOT changed.");
                    deleteDirectory(new File(extract_dir));
                    setButtonEnabled(true);
                    return;
                }
                byte[] patched_cacert;
                try {
                    byte[] original_cacert = Files.readAllBytes(cert_dest.toPath());
                    ByteArrayOutputStream cert_bytes = new ByteArrayOutputStream();
                    try (InputStream in = assetManager.open("cert-zwift-com.pem")) {
                        copyFile(in, cert_bytes);
                    }
                    patched_cacert = buildPatchedCacert(original_cacert, cert_bytes.toByteArray());
                    if (patched_cacert == null) {
                        // buildPatchedCacert already explained why
                        deleteDirectory(new File(extract_dir));
                        setButtonEnabled(true);
                        return;
                    }
                    Files.write(cert_dest.toPath(), patched_cacert);
                } catch (IOException e) {
                    addInfo("Error while patching cacert.pem: " + e);
                    addInfo("The original OBB was NOT changed.");
                    deleteDirectory(new File(extract_dir));
                    setButtonEnabled(true);
                    return;
                }
                addInfo("New cacert.pem written: " + cert_dest.length() + " bytes, SHA-256: " + sha256Hex(cert_dest));

                //Zip folder again
                addInfo("Zipping modified folder, this will take some time...");
                File new_zip_file = new File(obb_file.getParent(), "zwiftgame_patched.zip");
                if(new_zip_file.exists()) {
                    new_zip_file.delete();
                }
                boolean zipped = zipFolder(extract_dir + "/dataES", new_zip_file.getPath());

                if(!zipped || !new_zip_file.exists() || new_zip_file.length() == 0) {
                    addInfo("Failed to create the modified zip. The original OBB was NOT changed.");
                    cleanupTemp(new File(extract_dir), new_zip_file);
                    setButtonEnabled(true);
                    return;
                }

                //Check file sizes
                addInfo("Checking file sizes...");
                long new_size = new_zip_file.length();
                addInfo("Original OBB: " + org_size + " bytes, new ZIP: " + new_size + " bytes");

                if (new_size < org_size / 2) {
                    addInfo("New ZIP is far smaller than the original OBB, extraction was probably incomplete. The original OBB was NOT changed.");
                    cleanupTemp(new File(extract_dir), new_zip_file);
                    setButtonEnabled(true);
                    return;
                }

                // Make the ZIP exactly as large as the original OBB by inserting zero bytes
                // between the last entry and the central directory.
                long gap = org_size - new_size;
                if (gap < 0) {
                    addInfo("New ZIP is larger than the original OBB by " + (-gap) + " bytes. The original OBB was NOT changed.");
                    cleanupTemp(new File(extract_dir), new_zip_file);
                    setButtonEnabled(true);
                    return;
                }
                if (gap > 0) {
                    addInfo("Padding ZIP with " + gap + " bytes...");
                    try {
                        if (!padZip(new_zip_file, gap)) {
                            addInfo("Could not pad the ZIP (unexpected ZIP layout). The original OBB was NOT changed.");
                            cleanupTemp(new File(extract_dir), new_zip_file);
                            setButtonEnabled(true);
                            return;
                        }
                    } catch (IOException e) {
                        addInfo("Error while padding the ZIP: " + e);
                        addInfo("The original OBB was NOT changed.");
                        cleanupTemp(new File(extract_dir), new_zip_file);
                        setButtonEnabled(true);
                        return;
                    }
                }

                long diff = org_size - new_zip_file.length();
                addInfo("Difference in size: " + diff);
                if (diff != 0) {
                    addInfo("Filesize not equal (difference: " + diff + "). The original OBB was NOT changed.");
                    cleanupTemp(new File(extract_dir), new_zip_file);
                    setButtonEnabled(true);
                    return;
                }

                // Make sure the patched ZIP can be opened and really contains the new cacert.pem
                String zipError = verifyCacertInZip(new_zip_file, patched_cacert);
                if (zipError != null) {
                    addInfo("Patched ZIP check failed: " + zipError);
                    addInfo("The original OBB was NOT changed.");
                    cleanupTemp(new File(extract_dir), new_zip_file);
                    setButtonEnabled(true);
                    return;
                }

                //Replace the OBB with the patched ZIP
                addInfo("Replacing the OBB with the patched ZIP...");
                FileChannel source = null;
                FileChannel destination = null;

                try {
                    source = new FileInputStream(new_zip_file).getChannel();
                    destination = new FileOutputStream(obb_file).getChannel();
                    copyChannel(source, destination);
                } catch (FileNotFoundException e) {
                    addInfo(e.getMessage());
                } catch (IOException e) {
                    addInfo(e.getMessage());
                } finally {
                    if (source != null) {
                        try {
                            source.close();
                        } catch (IOException e) {
                            addInfo(e.getMessage());
                        }
                    }
                    if (destination != null) {
                        try {
                            destination.close();
                        } catch (IOException e) {
                            addInfo(e.getMessage());
                        }
                    }
                }

                if (obb_file.length() != new_zip_file.length()) {
                    addInfo("WARNING: final OBB size (" + obb_file.length() + ") differs from the patched ZIP (" + new_zip_file.length() + ").");
                }

                addInfo("Verifying cacert.pem inside the final OBB...");
                String obbError = verifyCacertInZip(obb_file, patched_cacert);
                if (obbError == null) {
                    addInfo("VERIFY OK: the OBB contains the patched cacert.pem (" + patched_cacert.length + " bytes).");
                } else {
                    addInfo("VERIFY FAILED: " + obbError);
                }

                addInfo("Removing temporary files...");
                cleanupTemp(new File(extract_dir), new_zip_file);

                addInfo("Done!");
            }
            else {
                addInfo("OBB file not found in Android/obb/com.zwift.zwiftgame.");
                addInfo("If you just granted the permissions, close the app completely (remove it from recent apps), open it again and tap 'Patch OBB'. Android may only apply the new access after the app restarts.");
                addInfo("Otherwise, check that the Zwift OBB file is in that folder.");
                setButtonEnabled(true);
            }
        });
        t1.start();
    }

    public void addInfo(String text) {
        runOnUiThread(() -> {
            TextView info = findViewById(R.id.textView);
            info.setText(info.getText() + "\r\n" + text);
            // scroll the log to the last line
            ScrollView sv = findViewById(R.id.scroll_view);
            if (sv != null) {
                sv.post(() -> sv.fullScroll(View.FOCUS_DOWN));
            }
        });
        // Only pause off the main thread (this used to freeze the UI)
        if (Looper.myLooper() != Looper.getMainLooper()) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public void copyFile(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[1024];
        int read;
        while((read = in.read(buffer)) != -1){
            out.write(buffer, 0, read);
        }
    }

    public boolean unzip(String _zipFile, String _targetLocation) {
        //create target location folder if not exist
        dirChecker(_targetLocation);

        int fileCount = 0;
        long byteCount = 0;
        try (ZipInputStream zin = new ZipInputStream(new BufferedInputStream(new FileInputStream(_zipFile)))) {
            String targetCanonical = new File(_targetLocation).getCanonicalPath() + File.separator;
            ZipEntry ze;
            byte[] buffer = new byte[8192];
            while ((ze = zin.getNextEntry()) != null) {
                File out = new File(_targetLocation + ze.getName());
                if (!out.getCanonicalPath().startsWith(targetCanonical)) {
                    throw new IOException("Invalid entry path: " + ze.getName());
                }

                //create dir if required while unzipping
                if (ze.isDirectory()) {
                    dirChecker(out.getPath());
                } else {
                    File parent = out.getParentFile();
                    if (parent != null) {
                        dirChecker(parent.getPath());
                    }
                    try (FileOutputStream fout = new FileOutputStream(out)) {
                        for (int c = zin.read(buffer); c != -1; c = zin.read(buffer)) {
                            fout.write(buffer, 0, c);
                            byteCount += c;
                        }
                    }
                    zin.closeEntry();
                    fileCount++;
                }
            }
        } catch (Exception e) {
            addInfo("Unzip error: " + e);
            return false;
        }
        addInfo("Extracted " + fileCount + " files (" + byteCount + " bytes)");
        return fileCount > 0;
    }

    private String sha256Hex(File f) {
        try (InputStream in = new FileInputStream(f)) {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                md.update(buf, 0, n);
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "unavailable (" + e + ")";
        }
    }

    /**
     * Appends the PEM blocks of the zoffline certificate file to the cacert.pem found in the OBB.
     * Blocks already present in cacert.pem are skipped, and the line endings of cacert.pem are kept.
     * Returns the new cacert.pem content, or null (after logging the reason) if there is nothing to do.
     */
    private byte[] buildPatchedCacert(byte[] existing, byte[] certFile) {
        String existingText = new String(existing, StandardCharsets.ISO_8859_1);
        String certText = new String(certFile, StandardCharsets.ISO_8859_1);
        String eol = existingText.contains("\r\n") ? "\r\n" : "\n";
        String existingCompact = existingText.replaceAll("\\s+", "");

        Matcher m = Pattern.compile("-----BEGIN ([A-Z ]+)-----.*?-----END \\1-----", Pattern.DOTALL).matcher(certText);
        StringBuilder out = new StringBuilder(existingText);
        if (!existingText.endsWith("\n")) {
            out.append(eol);
        }

        int found = 0;
        int added = 0;
        while (m.find()) {
            found++;
            String block = m.group();
            if (existingCompact.contains(block.replaceAll("\\s+", ""))) {
                continue;
            }
            out.append(eol).append(block.replace("\r\n", "\n").replace("\n", eol)).append(eol);
            added++;
        }

        if (found == 0) {
            addInfo("cert-zwift-com.pem (app assets) has no PEM blocks. The original OBB was NOT changed.");
            return null;
        }
        addInfo("zoffline certificate file: " + found + " PEM block(s), " + added + " added, " + (found - added) + " already present");
        if (added == 0) {
            addInfo("The OBB already contains the zoffline certificate. Nothing to do, the original OBB was NOT changed.");
            return null;
        }
        return out.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    /**
     * Grows a ZIP by exactly {@code extra} bytes by inserting zero bytes between the last entry and the
     * central directory, and moving the central directory (and the End Of Central Directory record) forward.
     * Local header offsets stay valid because the padding sits after all entries; only the "offset of start
     * of central directory" field in the EOCD record changes. Returns false if the layout is not the
     * expected one (comment present, ZIP64, gap before the EOCD, ...), in which case nothing is changed.
     */
    private boolean padZip(File zip, long extra) throws IOException {
        if (extra < 0) {
            return false;
        }
        try (RandomAccessFile raf = new RandomAccessFile(zip, "rw")) {
            long len = raf.length();
            if (len < 22) {
                return false;
            }
            byte[] eocd = new byte[22];
            raf.seek(len - 22);
            raf.readFully(eocd);
            // EOCD signature: PK 05 06, and no ZIP comment
            if (eocd[0] != 0x50 || eocd[1] != 0x4b || eocd[2] != 0x05 || eocd[3] != 0x06) {
                return false;
            }
            if (eocd[20] != 0 || eocd[21] != 0) {
                return false;
            }
            int entries = (eocd[10] & 0xFF) | ((eocd[11] & 0xFF) << 8);
            long cdSize = readLe32(eocd, 12);
            long cdOffset = readLe32(eocd, 16);
            // ZIP64 markers: not supported here
            if (entries == 0xFFFF || cdSize == 0xFFFFFFFFL || cdOffset == 0xFFFFFFFFL) {
                return false;
            }
            // the central directory must end exactly where the EOCD starts
            if (cdOffset + cdSize != len - 22) {
                return false;
            }
            long newOffset = cdOffset + extra;
            if (newOffset >= 0xFFFFFFFFL) {
                return false;
            }
            long tailLength = len - cdOffset;   // central directory + EOCD
            if (tailLength > 64L * 1024 * 1024) {
                return false;
            }

            byte[] tail = new byte[(int) tailLength];
            raf.seek(cdOffset);
            raf.readFully(tail);
            writeLe32(tail, tail.length - 22 + 16, newOffset);

            // zero padding, then the (updated) central directory + EOCD
            raf.seek(cdOffset);
            byte[] zeros = new byte[64 * 1024];
            long remaining = extra;
            while (remaining > 0) {
                int n = (int) Math.min(zeros.length, remaining);
                raf.write(zeros, 0, n);
                remaining -= n;
            }
            raf.write(tail);
        }
        return true;
    }

    private static long readLe32(byte[] b, int off) {
        return (b[off] & 0xFFL) | ((b[off + 1] & 0xFFL) << 8) | ((b[off + 2] & 0xFFL) << 16) | ((b[off + 3] & 0xFFL) << 24);
    }

    private static void writeLe32(byte[] b, int off, long value) {
        b[off] = (byte) (value & 0xFF);
        b[off + 1] = (byte) ((value >> 8) & 0xFF);
        b[off + 2] = (byte) ((value >> 16) & 0xFF);
        b[off + 3] = (byte) ((value >> 24) & 0xFF);
    }

    /**
     * Opens the ZIP with the platform's own ZIP reader and compares dataES/cacert.pem with the expected bytes.
     * Returns null if everything is fine, otherwise a description of the problem.
     */
    private String verifyCacertInZip(File zip, byte[] expected) {
        try (ZipFile zf = new ZipFile(zip)) {
            ZipEntry entry = zf.getEntry("dataES/cacert.pem");
            if (entry == null) {
                return "dataES/cacert.pem not found inside " + zip.getName();
            }
            ByteArrayOutputStream actual = new ByteArrayOutputStream();
            try (InputStream in = zf.getInputStream(entry)) {
                copyFile(in, actual);
            }
            if (!java.util.Arrays.equals(expected, actual.toByteArray())) {
                return "cacert.pem inside " + zip.getName() + " (" + actual.size() + " bytes) differs from the expected one (" + expected.length + " bytes)";
            }
            return null;
        } catch (Exception e) {
            return "could not read " + zip.getName() + ": " + e;
        }
    }

    private void copyChannel(FileChannel source, FileChannel destination) throws IOException {
        long size = source.size();
        long position = 0;
        while (position < size) {
            long n = destination.transferFrom(source, position, size - position);
            if (n <= 0) {
                break;
            }
            position += n;
        }
    }

    private void cleanupTemp(File extractDir, File... files) {
        for (File f : files) {
            if (f != null && f.exists()) {
                f.delete();
            }
        }
        deleteDirectory(extractDir);
    }

    private void dirChecker(String path) {
        File dir = new File(path);
        if (!dir.isDirectory() && !dir.mkdirs())
            addInfo("Failed to create folder: " + dir.getAbsolutePath());
    }

    public boolean zipFolder(String sourcePath, String toLocation) {
        final int BUFFER = 2048;

        File sourceFile = new File(sourcePath);
        try {
            BufferedInputStream origin = null;
            FileOutputStream dest = new FileOutputStream(toLocation);
            ZipOutputStream out = new ZipOutputStream(new BufferedOutputStream(
                    dest));
            if (sourceFile.isDirectory()) {
                zipSubFolder(out, sourceFile, sourceFile.getParent().length()+1);
            } else {
                byte data[] = new byte[BUFFER];
                FileInputStream fi = new FileInputStream(sourcePath);
                origin = new BufferedInputStream(fi, BUFFER);
                ZipEntry entry = new ZipEntry(getLastPathComponent(sourcePath));
                entry.setTime(sourceFile.lastModified()); // to keep modification time after unzipping
                out.putNextEntry(entry);
                int count;
                while ((count = origin.read(data, 0, BUFFER)) != -1) {
                    out.write(data, 0, count);
                }
            }
            out.close();
        } catch (Exception e) {
            addInfo(e.getMessage());
            return false;
        }
        return true;
    }

    private void zipSubFolder(ZipOutputStream out, File folder, int basePathLength) throws IOException {
        final int BUFFER = 2048;

        File[] fileList = folder.listFiles();
        BufferedInputStream origin = null;
        for (File file : fileList) {
            if (file.isDirectory()) {
                zipSubFolder(out, file, basePathLength);
            } else {
                byte data[] = new byte[BUFFER];
                String unmodifiedFilePath = file.getPath();
                String relativePath = unmodifiedFilePath
                        .substring(basePathLength);
                FileInputStream fi = new FileInputStream(unmodifiedFilePath);
                origin = new BufferedInputStream(fi, BUFFER);
                ZipEntry entry = new ZipEntry(relativePath);
                entry.setTime(file.lastModified()); // to keep modification time after unzipping
                out.putNextEntry(entry);
                int count;
                while ((count = origin.read(data, 0, BUFFER)) != -1) {
                    out.write(data, 0, count);
                }
                origin.close();
            }
        }
    }

    public String getLastPathComponent(String filePath) {
        String[] segments = filePath.split("/");
        if (segments.length == 0)
            return "";
        String lastPathComponent = segments[segments.length - 1];
        return lastPathComponent;
    }

    public boolean deleteDirectory(File directoryToBeDeleted) {
        File[] allContents = directoryToBeDeleted.listFiles();
        if (allContents != null) {
            for (File file : allContents) {
                deleteDirectory(file);
            }
        }
        return directoryToBeDeleted.delete();
    }
}