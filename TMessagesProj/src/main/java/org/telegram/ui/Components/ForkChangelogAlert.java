package org.telegram.ui.Components;

import android.content.Context;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * The fork's changelog, generated at build time into assets/fork-changelog.txt from the git log
 * (see .github/workflows/build.yml), shown from the debug menu.
 */
public class ForkChangelogAlert {

    public static String read() {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(ApplicationLoader.applicationContext.getAssets().open("fork-changelog.txt")))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
        } catch (Exception e) {
            FileLog.e(e);
            sb.append("No changelog in this build.");
        }
        return sb.toString().trim();
    }

    public static void show(BaseFragment fragment) {
        Context context = fragment.getParentActivity();
        if (context == null) {
            return;
        }
        String header = "Telegram fork " + BuildVars.BUILD_VERSION_STRING + " (" + buildNumber() + ")\n\n";
        AlertDialog.Builder builder = new AlertDialog.Builder(context, fragment.getResourceProvider());
        builder.setTitle("Changelog");
        builder.setMessage(header + read());
        builder.setPositiveButton("OK", null);
        builder.setNeutralButton("Copy", (dialog, which) -> {
            AndroidUtilities.addToClipboard(header + read());
            BulletinFactory.of(fragment).createCopyBulletin("Changelog copied").show();
        });
        fragment.showDialog(builder.create());
    }

    private static String buildNumber() {
        try {
            return String.valueOf(ApplicationLoader.applicationContext.getPackageManager().getPackageInfo(ApplicationLoader.applicationContext.getPackageName(), 0).versionCode);
        } catch (Exception e) {
            return "?";
        }
    }
}
