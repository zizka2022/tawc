package me.phie.tawc.sigsysprobe;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import java.io.File;
import java.security.MessageDigest;
import java.util.function.BooleanSupplier;

final class PackageMetadata {
    static String read(Context context, BooleanSupplier cancelled) throws Exception {
        if (cancelled.getAsBoolean()) throw new ProbeReport.Failure(ProbeReport.Category.CANCELLED);
        PackageInfo info = context.getPackageManager().getPackageInfo(
            "me.phie.tawc", PackageManager.GET_SIGNING_CERTIFICATES);
        if (info.applicationInfo == null) return "Package metadata unavailable.";
        long deadline = System.nanoTime() + 10000000000L;
        SigningInfo signing = info.signingInfo;
        if (signing == null) return "Signing metadata unavailable.";
        boolean multiple = signing.hasMultipleSigners();
        Signature[] signatures = multiple ? signing.getApkContentsSigners() : signing.getSigningCertificateHistory();
        if (signatures == null || signatures.length == 0 || signatures.length > 16)
            return "Signing metadata unavailable.";
        StringBuilder result = new StringBuilder("Installed TAWC\nversion code: " + info.getLongVersionCode());
        result.append("\nlast update (ms): ").append(info.lastUpdateTime);
        result.append("\ntarget SDK: ").append(info.applicationInfo.targetSdkVersion);
        result.append("\nSeparate app UID: ").append(info.applicationInfo.uid != android.os.Process.myUid());
        boolean expectedCurrent = true;
        for (int i = 0; i < signatures.length; i++) {
            String digest = PrivateFiles.hex(MessageDigest.getInstance("SHA-256").digest(signatures[i].toByteArray()));
            boolean current = multiple || i == signatures.length - 1;
            if (current) expectedCurrent &= digest.equals(ProbeProtocol.CERTIFICATE);
            result.append(current ? "\ncurrent certificate SHA-256: " : "\npast certificate SHA-256: ").append(digest);
        }
        result.append("\nExpected current CI certificate: ").append(expectedCurrent);
        appendFileHash(result, "base APK", new File(info.applicationInfo.sourceDir), deadline, cancelled);
        String[] splits = info.applicationInfo.splitSourceDirs;
        if (splits != null && splits.length > 16) throw new java.io.IOException("split_budget");
        if (splits != null) {
            for (int i = 0; i < splits.length; i++) appendFileHash(result, "split " + i, new File(splits[i]), deadline, cancelled);
        }
        File runtime = new File(info.applicationInfo.nativeLibraryDir, "libtawcroot.so");
        appendFileHash(result, "installed native runtime", runtime, deadline, cancelled);
        if (cancelled.getAsBoolean()) throw new ProbeReport.Failure(ProbeReport.Category.CANCELLED);
        PackageInfo after = context.getPackageManager().getPackageInfo("me.phie.tawc", 0);
        if (after.lastUpdateTime != info.lastUpdateTime || after.getLongVersionCode() != info.getLongVersionCode())
            return "Package changed during query; no consistent provenance snapshot.";
        result.append("\nInstalled-file metadata only; loaded processes may use an earlier runtime.");
        return result.toString();
    }
    private static void appendFileHash(StringBuilder result, String label, File file, long deadline, BooleanSupplier cancelled) throws Exception {
        if (cancelled.getAsBoolean()) throw new ProbeReport.Failure(ProbeReport.Category.CANCELLED);
        try { result.append("\n").append(label).append(" SHA-256: ").append(PrivateFiles.hash(file, 128L*1024*1024, deadline, cancelled)); }
        catch (Exception ignored) { result.append("\n").append(label).append(": unreadable or budget exceeded (no fallback)."); }
    }
}
