package com.learning.springboot.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * User-Agent 解析器(纯正则, 无第三方依赖)
 *
 * <p>解析结果:
 * <ul>
 *   <li>os: Windows 11 / Windows 10 / macOS 14 / macOS / Linux / iOS 17 / iOS / Android 14 / Android</li>
 *   <li>browser: Chrome 130 / Edge 130 / Safari 17 / Firefox 132 / Opera / Samsung Internet / Unknown</li>
 *   <li>deviceType: Desktop / Mobile / Tablet</li>
 * </ul>
 *
 * <p>业界参考: ua-parser-js / Browserslist 思路, 覆盖主流 90% UA 场景。
 */
public final class UserAgentParser {

    private UserAgentParser() {}

    public static class Result {
        public final String os;
        public final String browser;
        public final String deviceType;

        public Result(String os, String browser, String deviceType) {
            this.os = os;
            this.browser = browser;
            this.deviceType = deviceType;
        }
    }

    public static Result parse(String ua) {
        if (ua == null || ua.isEmpty()) {
            return new Result("Unknown", "Unknown", "Desktop");
        }
        String os = parseOs(ua);
        String browser = parseBrowser(ua);
        String deviceType = parseDeviceType(ua);
        return new Result(os, browser, deviceType);
    }

    private static String parseOs(String ua) {
        // iOS 17 / 16 / 15...
        Matcher iosVer = Pattern.compile("iPhone OS (\\d+)_").matcher(ua);
        if (iosVer.find()) return "iOS " + iosVer.group(1);
        if (ua.contains("iPhone") || ua.contains("iPad")) return "iOS";

        // Android 14 / 13 / 12...
        Matcher androidVer = Pattern.compile("Android (\\d+(?:\\.\\d+)?)").matcher(ua);
        if (androidVer.find()) return "Android " + androidVer.group(1);
        if (ua.contains("Android")) return "Android";

        // Windows 11 / 10 (NT 10.0 = Win10 / Win11; Win11 通过 Edge 99+ 推断)
        if (ua.contains("Windows NT 10.0")) {
            if (ua.contains("Edg/") || ua.contains("EdgA/") || ua.contains("EdgiOS/")) {
                return "Windows 11";
            }
            return "Windows 10";
        }
        if (ua.contains("Windows NT 6.3")) return "Windows 8.1";
        if (ua.contains("Windows NT 6.2")) return "Windows 8";
        if (ua.contains("Windows NT 6.1")) return "Windows 7";

        // macOS 14 / 13 / 12...
        // macOS 11+ (Big Sur 起) UA 用 "Mac OS X 14_0" 格式: group(1)=主版本, group(2)=副版本
        // macOS 10.x (Catalina 及更早) 用 "Mac OS X 10_15_7" 格式
        Matcher macosVer = Pattern.compile("Mac OS X (\\d+)_(\\d+)").matcher(ua);
        if (macosVer.find()) {
            int major = Integer.parseInt(macosVer.group(1));
            if (major >= 11) {
                // macOS 11+ 直接是主版本号
                return "macOS " + major;
            }
            // macOS 10.x 系列
            return "macOS " + major + "." + macosVer.group(2);
        }
        if (ua.contains("Mac OS X")) return "macOS";
        if (ua.contains("Macintosh")) return "macOS";

        if (ua.contains("Linux")) return "Linux";
        if (ua.contains("CrOS")) return "ChromeOS";

        return "Unknown";
    }

    private static String parseBrowser(String ua) {
        // Edge (Chromium-based) - 必须先匹配, 因为它也含 Chrome
        Matcher edge = Pattern.compile("Edg(?:e|A|iOS)?/(\\d+(?:\\.\\d+)?)").matcher(ua);
        if (edge.find()) {
            // EdgiOS = Edge iOS, EdgA = Edge Android, Edg = Edge Desktop
            if (ua.contains("EdgiOS")) return "Edge iOS " + edge.group(1);
            if (ua.contains("EdgA/")) return "Edge Android " + edge.group(1);
            return "Edge " + edge.group(1);
        }

        // Opera
        Matcher opera = Pattern.compile("OPR/(\\d+(?:\\.\\d+)?)").matcher(ua);
        if (opera.find()) {
            if (ua.contains("Opera Mini")) return "Opera Mini";
            return "Opera " + opera.group(1);
        }

        // Samsung Internet
        if (ua.contains("SamsungBrowser/")) {
            Matcher sb = Pattern.compile("SamsungBrowser/(\\d+(?:\\.\\d+)?)").matcher(ua);
            if (sb.find()) return "Samsung Internet " + sb.group(1);
            return "Samsung Internet";
        }

        // UC Browser
        if (ua.contains("UCBrowser")) {
            Matcher uc = Pattern.compile("UCBrowser/(\\d+(?:\\.\\d+)?)").matcher(ua);
            if (uc.find()) return "UC " + uc.group(1);
            return "UC";
        }

        // Firefox
        Matcher ff = Pattern.compile("Firefox/(\\d+(?:\\.\\d+)?)").matcher(ua);
        if (ff.find()) return "Firefox " + ff.group(1);

        // Safari (iOS / macOS)
        if (ua.contains("Safari/") && !ua.contains("Chrome") && !ua.contains("Chromium")) {
            Matcher saf = Pattern.compile("Version/(\\d+(?:\\.\\d+)?)").matcher(ua);
            if (saf.find()) return "Safari " + saf.group(1);
            return "Safari";
        }

        // Chrome / Chromium
        Matcher chrome = Pattern.compile("Chrome/(\\d+(?:\\.\\d+)?)").matcher(ua);
        if (chrome.find()) {
            if (ua.contains("CriOS")) return "Chrome iOS " + chrome.group(1);
            if (ua.contains("Chrome")) return "Chrome " + chrome.group(1);
        }

        return "Unknown";
    }

    private static String parseDeviceType(String ua) {
        if (ua.contains("iPad")) return "Tablet";
        if (ua.contains("Android") && !ua.contains("Mobile")) return "Tablet";
        if (ua.contains("Mobile") || ua.contains("iPhone")) return "Mobile";
        return "Desktop";
    }
}