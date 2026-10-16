package com.camera.app;

import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The glue of the Semo ecosystem (Gallery, Music, Files, Keyboard): the apps stay separate, but each one finds
 * the others, shows which are installed, and hands work over (a photo to the Gallery, a song to Music, a
 * folder to Files). Nothing is hard-coded to a package name: every Semo app declares
 *   - an activity filter for {@link #ACTION_ECO}                       (so the others can discover it)
 *   - &lt;meta-data app.semo.ROLE = gallery | music | files | keyboard | notes | camera&gt; (what it is good at)
 * and a new app joins the ecosystem just by adding those two lines to its manifest.
 */
final class Semo {
    private Semo() {
    }

    static final String ACTION_ECO = "app.semo.ECOSYSTEM";
    static final String ACTION_SHOW_IN_FILES = "app.semo.SHOW_IN_FILES";
    static final String META_ROLE = "app.semo.ROLE";

    static final String ROLE_GALLERY = "gallery";
    static final String ROLE_MUSIC = "music";
    static final String ROLE_FILES = "files";
    static final String ROLE_KEYBOARD = "keyboard";
    static final String ROLE_NOTES = "notes";
    static final String ROLE_CAMERA = "camera";

    static final class Sibling {
        String pkg = "";
        String role = "";
        CharSequence label = "";
        String version = "";
        long code;
    }

    private static List<Sibling> cache;
    private static long cacheAt;

    /** Other Semo apps installed on this phone (own app excluded). Cached for a few seconds: cheap to call. */
    @SuppressWarnings("deprecation")
    static synchronized List<Sibling> siblings(Context c) {
        long now = System.currentTimeMillis();
        if (cache != null && now - cacheAt < 5000) return cache;
        List<Sibling> out = new ArrayList<>();
        try {
            PackageManager pm = c.getPackageManager();
            Intent probe = new Intent(ACTION_ECO).addCategory(Intent.CATEGORY_DEFAULT);
            Set<String> seen = new HashSet<>();
            for (ResolveInfo ri : pm.queryIntentActivities(probe, 0)) {
                if (ri.activityInfo == null) continue;
                String p = ri.activityInfo.packageName;
                if (p == null || p.equals(c.getPackageName()) || !seen.add(p)) continue;
                Sibling s = new Sibling();
                s.pkg = p;
                try {
                    ApplicationInfo ai = pm.getApplicationInfo(p, PackageManager.GET_META_DATA);
                    s.label = pm.getApplicationLabel(ai);
                    Bundle md = ai.metaData;
                    if (md != null && md.getString(META_ROLE) != null) s.role = md.getString(META_ROLE);
                    PackageInfo pi = pm.getPackageInfo(p, 0);
                    s.version = pi.versionName == null ? "" : pi.versionName;
                    s.code = Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
                } catch (Exception ignored) {
                }
                out.add(s);
            }
        } catch (Exception ignored) {
        }
        cache = out;
        cacheAt = now;
        return out;
    }

    static Sibling byRole(Context c, String role) {
        for (Sibling s : siblings(c)) if (role.equals(s.role)) return s;
        return null;
    }

    /** Opens another Semo app's launcher screen. */
    static boolean open(Context c, String pkg) {
        try {
            Intent i = c.getPackageManager().getLaunchIntentForPackage(pkg);
            if (i == null) return false;
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Hands a content Uri (readable by the receiver through a grant) to the sibling with the given role. */
    static boolean view(Context c, Uri uri, String mime, String role) {
        Sibling s = byRole(c, role);
        if (s == null || uri == null) return false;
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, mime == null || mime.isEmpty() ? "*/*" : mime);
            i.setClipData(ClipData.newRawUri("", uri));
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            i.setPackage(s.pkg);
            if (c.getPackageManager().resolveActivity(i, 0) == null) return false;
            if (!(c instanceof android.app.Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Asks the Files app (if installed) to open the folder that holds {@code path}. */
    static boolean showInFiles(Context c, String path) {
        if (path == null || path.isEmpty()) return false;
        Sibling s = byRole(c, ROLE_FILES);
        if (s == null) return false;
        try {
            Intent i = new Intent(ACTION_SHOW_IN_FILES).setPackage(s.pkg);
            i.putExtra("path", path);
            if (!(c instanceof android.app.Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Sends plain text to the Notes app (its public "share" doorway opens a new note with it). */
    static boolean sendToNotes(Context c, String title, String text) {
        Sibling s = byRole(c, ROLE_NOTES);
        if (s == null || text == null || text.isEmpty()) return false;
        try {
            Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain").setPackage(s.pkg);
            i.putExtra(Intent.EXTRA_TEXT, text);
            if (title != null && !title.isEmpty()) i.putExtra(Intent.EXTRA_SUBJECT, title);
            if (!(c instanceof android.app.Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static boolean hasNotes(Context c) {
        return byRole(c, ROLE_NOTES) != null;
    }

    static boolean hasFiles(Context c) {
        return byRole(c, ROLE_FILES) != null;
    }
}
