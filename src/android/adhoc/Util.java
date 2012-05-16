/*
*  This file is part of Barnacle Wifi Tether
*  Copyright (C) 2010 by Szymon Jakubczak
*
*  This program is free software: you can redistribute it and/or modify
*  it under the terms of the GNU General Public License as published by
*  the Free Software Foundation, either version 3 of the License, or
*  (at your option) any later version.
*
*  This program is distributed in the hope that it will be useful,
*  but WITHOUT ANY WARRANTY; without even the implied warranty of
*  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
*  GNU General Public License for more details.
*
*  You should have received a copy of the GNU General Public License
*  along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

package android.adhoc;

import java.io.UnsupportedEncodingException;
import java.util.ArrayList;

import android.util.Log;


public class Util {
    static class StyledStringBuilder extends android.text.SpannableStringBuilder {
        public StyledStringBuilder() { super(); }
        private StyledStringBuilder append(Object obj, String s) {
            append(s).setSpan(obj, length()-s.length(), length(), 0);
            return this;
        }
        public StyledStringBuilder append(android.text.style.TextAppearanceSpan obj, String s) {
            return append((Object)obj, s);
        }
        public StyledStringBuilder append(int color, String s) {
            return append(new android.text.style.ForegroundColorSpan(color), s);
        }
    }

    /** returns the first wireless interface -- the kernel module must be loaded */
    public static String findWifiIface() { // OBSOLETE!
        try {
            String line = readLinesFromFile("/proc/net/wireless").get(2);
            return line.substring(0,line.indexOf(":"));
        } catch (Exception e) {
            return null;
        }
    }

    /** parses /proc/cpuinfo for hardware name */
    public static String getHardwareName() { // NOT USED
        String hw = "";
        for (String line: readLinesFromFile("/proc/cpuinfo")) {
            if (line.startsWith("Hardware")) {
                hw = line.split(": ")[1];
                break;
            }
        }
        return hw;
    }

    public static ArrayList<String> readLinesFromFile(String filename) {
        ArrayList<String> lines = new ArrayList<String>();
        try {
            java.io.BufferedReader br = toReader(new java.io.FileInputStream(filename));
            String line;
            while((line = br.readLine()) != null) {
                lines.add(line.trim());
            }
        } catch (Exception e) {
            return null;
        }
        return lines;
    }

    public static java.io.BufferedReader toReader(java.io.InputStream is) {
        return new java.io.BufferedReader(new java.io.InputStreamReader(is), 8192);
    }

    public static int exec(String cmd) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(cmd);
            return p.waitFor();
        } catch (Exception e) {
            Log.e(AdHocApp.TAG, "exec: " + cmd, e);
            if (p != null) p.destroy();
            return -1;
        }
    }

    // in absence of proper api
    public static String getprop(String key) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec("getprop " + key);
            return toReader(p.getInputStream()).readLine();
        } catch (Exception e) {
            Log.e(AdHocApp.TAG, "getprop: " + key, e);
            if (p != null) p.destroy();
            return null;
        }
    }

    public static String asc2hex(String asc) {
        try {
            byte[] bytes = asc.getBytes("US-ASCII");
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                builder.append(Integer.toHexString(b));
            }
            return builder.toString();
        } catch (UnsupportedEncodingException e) {
            return null;
        }
    }

    public static String hex2asc(String hex) {
        byte[] bytes = new byte[hex.length() / 2];
        for (int i = 0; i < bytes.length; ++i) {
            bytes[i] = Byte.parseByte(hex.substring(i * 2, (i + 1) * 2), 16);
        }
        try {
            return new String(bytes, "US-ASCII");
        } catch (UnsupportedEncodingException e) {
            return null;
        }
    }

    public static String toCommaList(String s) {
        String[] parts = s.split(":|,|;|\\||\\s+");
        StringBuilder sb = new StringBuilder(s.length());
        String d = "";
        for (String p : parts) {
            sb.append(d);
            sb.append(p);
            d = ",";
        }
        return sb.toString();
    }

}
