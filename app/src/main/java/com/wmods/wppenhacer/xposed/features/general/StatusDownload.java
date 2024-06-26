package com.wmods.wppenhacer.xposed.features.general;

import android.content.SharedPreferences;
import android.media.MediaScannerConnection;
import android.os.Environment;
import android.view.Menu;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.wmods.wppenhacer.xposed.core.ResId;
import com.wmods.wppenhacer.xposed.core.Unobfuscator;
import com.wmods.wppenhacer.xposed.core.Utils;
import com.wmods.wppenhacer.xposed.core.Feature;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class StatusDownload extends Feature {
    private Object messageObj;

    public StatusDownload(ClassLoader loader, XSharedPreferences preferences) {
        super(loader, preferences);
    }


    public void doHook() throws Exception {
        if (!prefs.getBoolean("downloadstatus", false)) return;
        var setPageActiveMethod = Unobfuscator.loadStatusActivePage(loader);
        var fieldList = Unobfuscator.getFieldByType(setPageActiveMethod.getDeclaringClass(), List.class);
        logDebug("List field: " + fieldList.getName());
        logDebug(Unobfuscator.getMethodDescriptor(setPageActiveMethod));
        var mediaClass = Unobfuscator.loadStatusDownloadMediaClass(loader);
        logDebug("Media class: " + mediaClass.getName());
        var menuStatusClass = Unobfuscator.loadMenuStatusClass(loader);
        logDebug("MenuStatus class: " + menuStatusClass.getName());
        var fieldFile = Unobfuscator.loadStatusDownloadFileField(loader);
        logDebug("File field: " + fieldFile.getName());
        var clazzSubMenu = Unobfuscator.loadStatusDownloadSubMenuClass(loader);
        logDebug("SubMenu class: " + clazzSubMenu.getName());
        var clazzMenu = Unobfuscator.loadStatusDownloadMenuClass(loader);
        logDebug("Menu class: " + clazzMenu.getName());
        var menuField = Unobfuscator.getFieldByType(clazzSubMenu, clazzMenu);
        logDebug("Menu field: " + menuField.getName());

        XposedHelpers.findAndHookMethod(menuStatusClass, "onClick", View.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                if (messageObj == null) return;
                var fileData = XposedHelpers.getObjectField(messageObj, "A01");
                var file = (File) XposedHelpers.getObjectField(fileData, fieldFile.getName());
                Field subMenuField = Arrays.stream(param.thisObject.getClass().getDeclaredFields()).filter(f -> f.getType() == Object.class && clazzSubMenu.isInstance(XposedHelpers.getObjectField(param.thisObject, f.getName()))).findFirst().orElse(null);
                Object submenu = XposedHelpers.getObjectField(param.thisObject, subMenuField.getName());
                var menu = (Menu) XposedHelpers.getObjectField(submenu, menuField.getName());
                if (menu.findItem(ResId.string.download) != null) return;
                menu.add(0, ResId.string.download, 0, ResId.string.download).setOnMenuItemClickListener(item -> {
                    if (copyFile(prefs, file)) {
                        Toast.makeText(Utils.getApplication(), Utils.getApplication().getString(ResId.string.saved_to) + getPathDestination(prefs, file), Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(Utils.getApplication(), Utils.getApplication().getString(ResId.string.error_when_saving_try_again), Toast.LENGTH_SHORT).show();
                    }
                    return true;
                });
            }
        });

        XposedBridge.hookMethod(setPageActiveMethod, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                var position = (int) param.args[1];
                var list = (List<?>) XposedHelpers.getObjectField(param.args[0], fieldList.getName());
                var message = list.get(position);
                if (message != null && mediaClass.isInstance(message)) {
                    messageObj = message;
                }
            }
        });
    }

    @NonNull
    @Override
    public String getPluginName() {
        return "Download Status";
    }

    private static boolean copyFile(SharedPreferences prefs, File file) {
        if (file == null || !file.exists()) return false;

        var destination = getPathDestination(prefs, file);

        try (FileInputStream in = new FileInputStream(file);
             FileOutputStream out = new FileOutputStream(destination)) {
            byte[] bArr = new byte[1024];
            while (true) {
                int read = in.read(bArr);
                if (read <= 0) {
                    in.close();
                    out.close();

                    String[] parts = destination.split("\\.");
                    String ext = parts[parts.length - 1].toLowerCase();

                    MediaScannerConnection.scanFile(Utils.getApplication(),
                            new String[]{destination},
                            new String[]{getMimeTypeFromExtension(ext)},
                            (path, uri) -> {
                            });

                    return true;
                }
                out.write(bArr, 0, read);
            }
        } catch (IOException e) {
            XposedBridge.log(e.getMessage());
            return false;
        }
    }

    @NonNull
    private static String getPathDestination(SharedPreferences sharedPreferences, @NonNull File f) {
        var filePath = f.getAbsolutePath();
        var isVideo = false;
        var isImage = false;
        var isAudio = false;

        Set<String> videoFormats = Set.of("3gp", "mp4", "mkv", "avi", "wmv", "flv", "mov", "webm", "ts", "m4v", "divx", "xvid", "mpg", "mpeg", "mpg2", "ogv", "vob", "f4v", "asf");
        Set<String> imageFormats = Set.of("jpeg", "jpg", "png", "gif", "bmp", "webp", "heif", "tiff", "raw", "svg", "eps", "ai");
        Set<String> audioFormats = Set.of("mp3", "wav", "ogg", "m4a", "aac", "flac", "amr", "wma", "opus", "mid", "xmf", "rtttl", "rtx", "ota", "imy", "mpga", "ac3", "ec3", "eac3");

        String extension = filePath.substring(filePath.lastIndexOf(".") + 1).toLowerCase();

        if (videoFormats.contains(extension)) {
            isVideo = true;
        } else if (imageFormats.contains(extension)) {
            isImage = true;
        } else if (audioFormats.contains(extension)) {
            isAudio = true;
        }

        String folderPath = getStatusFolderPath(sharedPreferences, isVideo, isImage, isAudio);

        var mediaPath = new File(folderPath);

        if (!mediaPath.exists())
            mediaPath.mkdirs();

        return mediaPath.getAbsolutePath() + "/" + f.getName();
    }

    @NonNull
    private static String getStatusFolderPath(SharedPreferences sharedPreferences, boolean isVideo, boolean isImage, boolean isAudio) {
        String folderPath = sharedPreferences.getString("localdownload", Environment.getExternalStorageDirectory().getAbsolutePath()+"/Download");
        if (isVideo) {
            folderPath += "/WhatsApp/Wa Enhancer/Status Videos/";
        } else if (isImage) {
            folderPath += "/WhatsApp/Wa Enhancer/Status Images/";
        } else if (isAudio) {
            folderPath += "/WhatsApp/Wa Enhancer/Status Sounds/";
        } else {
            folderPath += "/WhatsApp/Wa Enhancer/Status Media/";
        }
        return folderPath;
    }

    public static String getMimeTypeFromExtension(String extension) {
        return switch (extension) {
            case "3gp", "mp4", "mkv", "avi", "wmv", "flv", "mov", "webm", "ts", "m4v", "divx", "xvid", "mpg", "mpeg", "mpg2", "ogv", "vob", "f4v", "asf" ->
                    "video/*";
            case "jpeg", "jpg", "png", "gif", "bmp", "webp", "heif", "tiff", "raw", "svg", "eps", "ai" ->
                    "image/*";
            case "mp3", "wav", "ogg", "m4a", "aac", "flac", "amr", "wma", "opus", "mid", "xmf", "rtttl", "rtx", "ota", "imy", "mpga", "ac3", "ec3", "eac3" ->
                    "audio/*";
            default -> "*/*";
        };
    }
}