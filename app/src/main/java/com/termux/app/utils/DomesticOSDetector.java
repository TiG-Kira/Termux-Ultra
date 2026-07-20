package com.termux.app.utils;

import android.os.Build;

import java.util.Arrays;
import java.util.List;

public final class DomesticOSDetector {

    private static final List<String> DOMESTIC_OS_MANUFACTURERS = Arrays.asList(
            "xiaomi",
            "meizu",
            "huawei",
            "honor",
            "oppo",
            "vivo",
            "realme",
            "iqoo",
            "samsung",
            "lenovo",
            "zte",
            "nubia",
            "oneplus",
            "motorola"
    );

    private static final List<String> DOMESTIC_OS_BUILD_TAGS = Arrays.asList(
            "miui",
            "flyme",
            "harmony",
            "emui",
            "magicui",
            "coloros",
            "originos",
            "realmeui",
            "iqooui",
            "funtouchos"
    );

    private static Boolean sIsDomesticOS = null;

    private DomesticOSDetector() {
    }

    public static boolean isDomesticOS() {
        if (sIsDomesticOS != null) {
            return sIsDomesticOS;
        }

        boolean result = checkManufacturer() || checkBuildTags() || checkBuildFingerprint();
        sIsDomesticOS = result;
        return result;
    }

    private static boolean checkManufacturer() {
        String manufacturer = Build.MANUFACTURER.toLowerCase();
        for (String domesticManufacturer : DOMESTIC_OS_MANUFACTURERS) {
            if (manufacturer.contains(domesticManufacturer)) {
                return true;
            }
        }
        return false;
    }

    private static boolean checkBuildTags() {
        String buildTags = Build.TAGS != null ? Build.TAGS.toLowerCase() : "";
        String buildDisplay = Build.DISPLAY != null ? Build.DISPLAY.toLowerCase() : "";
        String buildProduct = Build.PRODUCT != null ? Build.PRODUCT.toLowerCase() : "";
        String buildDevice = Build.DEVICE != null ? Build.DEVICE.toLowerCase() : "";

        for (String tag : DOMESTIC_OS_BUILD_TAGS) {
            if (buildTags.contains(tag) ||
                buildDisplay.contains(tag) ||
                buildProduct.contains(tag) ||
                buildDevice.contains(tag)) {
                return true;
            }
        }
        return false;
    }

    private static boolean checkBuildFingerprint() {
        String fingerprint = Build.FINGERPRINT != null ? Build.FINGERPRINT.toLowerCase() : "";
        for (String domesticManufacturer : DOMESTIC_OS_MANUFACTURERS) {
            if (fingerprint.contains(domesticManufacturer)) {
                return true;
            }
        }
        for (String tag : DOMESTIC_OS_BUILD_TAGS) {
            if (fingerprint.contains(tag)) {
                return true;
            }
        }
        return false;
    }
}
