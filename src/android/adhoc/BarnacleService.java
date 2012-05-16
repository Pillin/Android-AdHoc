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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Map;
import android.app.Notification;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.PowerManager;
import android.preference.PreferenceManager;
import android.util.Log;

/**
* Manages the running process, client list, and log
*/
public class BarnacleService extends android.app.Service {
    final static String TAG = "BarnacleService";
    // messages from the process
    final static int MSG_OUTPUT     = 1;
    final static int MSG_ERROR      = 2;
    // messages from self
    final static int MSG_EXCEPTION  = 3;
    final static int MSG_NETSCHANGE = 4;
    // requests from activities
    final static int MSG_START      = 5;
    final static int MSG_STOP       = 6;
    // app states
    public final static int STATE_STOPPED  = 0;
    public final static int STATE_STARTING = 1;
    public final static int STATE_RUNNING  = 2;

    // private state
    private int state = STATE_STOPPED;
    private Process process = null;
    // output monitoring threads
    private Thread[] threads = new Thread[2];
    private PowerManager.WakeLock wakeLock;
    private BroadcastReceiver connectivityReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            mHandler.sendEmptyMessage(MSG_NETSCHANGE);
        }
    };

    // WARNING: this is not entirely safe
    public static BarnacleService singleton = null;

    private BarnacleApp app;
    private WifiManager wifiManager;
    
    private boolean filteringEnabled = false;
    private Method mStartForeground = null;

    
    /** 
     * Worker Threads
     **/
    private class OutputMonitor implements Runnable {
        private final java.io.BufferedReader br;
        private final int msg;
        public OutputMonitor(int t, java.io.InputStream is) {
            br = Util.toReader(is);
            msg = t;
        }
        public void run() {
            try{
                String line;
                do {
                    line = br.readLine();
                    mHandler.obtainMessage(msg, line).sendToTarget(); // NOTE: the last null is also sent!
                } while(line != null);
            } catch (Exception e) {
                mHandler.obtainMessage(MSG_EXCEPTION, e).sendToTarget();
            }
        }
    }
  
    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
        	handle(msg);
        }
    };


    @Override
    public void onCreate() {
        super.onCreate();
        singleton = this;

        wifiManager = (WifiManager) getSystemService(Context.WIFI_SERVICE);
        
        try {
            mStartForeground = getClass().getMethod("startForeground", new Class[] {
                    int.class, Notification.class});
        } catch (NoSuchMethodException e) {
            mStartForeground = null;
        }

        state = STATE_STOPPED;
        filteringEnabled = false;

        app = (BarnacleApp)getApplication();
        app.serviceStarted(this);

        // Unlock recive UDP ports
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BarnacleService");
        wakeLock.acquire();

        IntentFilter filter = new IntentFilter();
        filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        registerReceiver(connectivityReceiver, filter);
        
//      TODO: FVALVERD pasar el texto a string.xml  
        Log.d(TAG, this.getClass().getSimpleName() + " Created!");
    }

    @Override
    public void onDestroy() {
        if (state != STATE_STOPPED) {
//        	TODO: FVALVERD pasar el texto a string.xml
        	Log.e(TAG, "service destroyed while running!");
        }
        // ensure we clean up
        stopProcess();
        state = STATE_STOPPED;
        app.processStopped();
        wakeLock.release();

        try {
            unregisterReceiver(connectivityReceiver);
        } catch (Exception e) {
            // ignore
        }

        singleton = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
    
    /**
     * Public service interface for Start
     **/
    public void startRequest() {
        mHandler.sendEmptyMessage(MSG_START);
    }

    public void stopRequest() {
        mHandler.sendEmptyMessage(MSG_STOP);
    }

    public int getState() {
        return state;
    }

    public boolean hasFiltering() {
        return filteringEnabled;
    }

    private void handle(Message msg) {
        switch (msg.what) {
        case MSG_EXCEPTION:
            if (state == STATE_STOPPED) return;
            Throwable thr = (Throwable)msg.obj;
            thr.printStackTrace();
            Log.e(TAG, "EXCEPTION: " + thr.getMessage() + " " + Log.getStackTraceString(thr));
            stopProcess();
            state = STATE_STOPPED;
            break;
        case MSG_ERROR:
            if (state == STATE_STOPPED) {
            	return;
            }
            if (process == null) {
            	return;
            }
            if (msg.obj != null) {
                String line = (String)msg.obj;
                Log.e(TAG, "ERROR: " + line);
                if ((state == STATE_STARTING)) {
                    if (isRootError(line)) {
                        app.failed(BarnacleApp.ERROR_ROOT);
                    }
                    else if (isSupplicantError(line)) {
                        app.failed(BarnacleApp.ERROR_SUPPLICANT);
                    }
                    else {
                        app.failed(BarnacleApp.ERROR_OTHER);
                    }
                }
                else {
                    app.failed(BarnacleApp.ERROR_OTHER);
                }
            }
            else {
            	stopProcess();
	            state = STATE_STOPPED;
            }
            break;
        case MSG_OUTPUT:
            if (state == STATE_STOPPED || process == null){
            	return;
            }
            String line = (String)msg.obj;
            if (line == null) {
                break; // ignore it, wait for MSG_ERROR(null)
            }
            else if (isWifiOK(line)) {
                if (state == STATE_STARTING) {
                    state = STATE_RUNNING;
                    String startedFormat = getString(R.string.started);
                    Log.d(TAG, String.format(startedFormat, this.getClass().getSimpleName()));
                    app.processStarted();
                }
            }
            else {
            	Log.i(TAG, line);
            }
            break;
        case MSG_START:
        	if (state != STATE_STOPPED) {
        		return;
        	}
        	String startingFormat = getString(R.string.starting);
            Log.d(TAG, String.format(startingFormat, this.getClass().getSimpleName()));

            // TODO: FVALVERD hacer esto solo para nuevas versiones de los archivos
            if (!NativeHelper.unzipAssets(this)) {
                Log.e(TAG, getString(R.string.unpackerr));
                state = STATE_STOPPED;
                break;
            }
            state = STATE_STARTING;
        case MSG_NETSCHANGE:
            int wifiState = wifiManager.getWifiState();
            Log.w(TAG, String.format("NETSCHANGE: AndroidWifiState=%d AppState=%d process=%s", wifiState, state, process == null ? "null" : "notNull"));
            if (wifiState == WifiManager.WIFI_STATE_DISABLED) {
                // wifi is good (or lost), we can start now...
            	if ((state == STATE_STARTING) && (process == null)) {
            		if (app.findIfWan()) {
            			// TODO: FVALVERD colocar el texto en string.xml
            			Log.d(TAG, "Found active WAN interface");
            		} else {
            			// TODO: FVALVERD colocar el texto en string.xml
                        Log.w(TAG, "No active WAN interface found");
                        //state = STATE_STOPPED;
                        //break;
                    }
                    if (!startProcess()) {
                        Log.e(TAG, getString(R.string.starterr));
                        state = STATE_STOPPED;
                        break;
                    }
                }
            } else {
                if (state == STATE_RUNNING) {
                    // this is super bad, will have to restart!
                    app.updateToast(getString(R.string.conflictwifi), true);
                    Log.e(TAG, getString(R.string.conflictwifi));
                    stopProcess();
                    Log.d(TAG, getString(R.string.restarting));
                    wifiManager.setWifiEnabled(false); // this will send MSG_NETSCHANGE
                    // TODO: FVALVERD we should wait until wifi is disabled...
                    state = STATE_STARTING;
                }
                else if (state == STATE_STARTING) {
                    if ((wifiState == WifiManager.WIFI_STATE_ENABLED) || (wifiState == WifiManager.WIFI_STATE_ENABLING)) {
                        app.updateToast(getString(R.string.disablewifi), false);
                        wifiManager.setWifiEnabled(false);
                        Log.d(TAG, getString(R.string.waitwifi));
                    }
                }
            }
            break;
        case MSG_STOP:
            if (state == STATE_STOPPED) return;
            stopProcess();
            state = STATE_STOPPED;
            String stoppedFormat = getString(R.string.stopped);
            Log.d(TAG, String.format(stoppedFormat, this.getClass().getSimpleName()));
            break;
        }
        app.updateStatus();
        if (state == STATE_STOPPED) {
            app.processStopped();
        }
    }

	/**
     * Prepare env vars for wifi script from app preferences
     **/
    protected String[] getEnvironmentFromPrefs() {
    	ArrayList<String> envlist = new ArrayList<String>();

    	Map<String, String> env = System.getenv();
    	for (String envName : env.keySet()) {
    		envlist.add(envName + "=" + env.get(envName));
    	}

    	PreferenceManager.setDefaultValues(this, R.xml.preferences, false);
    	SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);

    	final int[] ids = SettingsActivity.prefids;
    	for (int i = 0; i < ids.length; ++i) {
    		String k = getString(ids[i]);
    		String v = prefs.getString(k, null);
    		if (v != null && v.length() != 0) {
    			// TODO some chars need to be escaped, but this seems to add "" to the ESSID name
    			envlist.add("brncl_" + k + "=" + v);
    		}
    	}
    	// not included in prefids are checkboxes
    	final int[] checks = SettingsActivity.checks;
    	for (int i = 0; i < checks.length; ++i) {
    		String k = getString(checks[i]);
    		if (prefs.getBoolean(k, false))
    			envlist.add("brncl_" + k + "=1");
    	}
    	envlist.add("brncl_path=" + NativeHelper.app_bin.getAbsolutePath());

    	String[] ret = (String[]) envlist.toArray(new String[0]);
    	for (String s : ret) {
    		Log.i(TAG, "set env: " + s);
    	}
    	return ret;
    }

    private boolean startProcess() {
    	String cmd = NativeHelper.SU_C;
        try {
        	Runtime runtime = Runtime.getRuntime();
            process = runtime.exec(cmd, getEnvironmentFromPrefs(), NativeHelper.app_bin);
            threads[0] = new Thread(new OutputMonitor(MSG_OUTPUT, process.getInputStream()));
            threads[1] = new Thread(new OutputMonitor(MSG_ERROR, process.getErrorStream()));
            threads[0].start();
            threads[1].start();
        } catch (Exception e) {
            Log.e(TAG, String.format(getString(R.string.execerr), cmd));
            Log.e(TAG, "start failed " + e.toString());
            return false;
        }
        return true;
    }

    private void stopProcess() {
        if (process != null) {
            // first, just close the stream
            if (state != STATE_STOPPED) {
                try {
                    process.getOutputStream().close();
                } catch (Exception e) {
//                  TODO: FVALVERD pasar este texto a string.xml
                    Log.w(TAG, "Exception while closing process");
                }
            }
            try {
                process.waitFor(); // blocking!
            } catch (InterruptedException e) {
                Log.e(TAG, "");
            }

            try {
                int exit_status = process.exitValue();
//                TODO: FVALVERD pasar este texto a string.xml
                Log.i(TAG, "Command line Java Process exited with status: " + exit_status);
            } catch (IllegalThreadStateException e) {
            	e.printStackTrace();
                Log.e(TAG, getString(R.string.dirtystop));
            }
            process.destroy();
            process = null;
            threads[0].interrupt();
            threads[1].interrupt();
        }
    }

    /**
    * This is a wrapper around the new startForeground method, using the older
    * APIs if it is not available.
    */
    public void startForegroundCompat(int id, Notification notification) {
        // If we have the new startForeground API, then use it.
        if (mStartForeground != null) {
            try {
                mStartForeground.invoke(this, new Object[] {Integer.valueOf(id), notification});
            } catch (InvocationTargetException e) {
                Log.w(TAG, "Unable to invoke startForeground", e);
            } catch (IllegalAccessException e) {
                Log.w(TAG, "Unable to invoke startForeground", e);
            }
            return;
        }
        // Fall back on the old API.
        setForeground(true);
    }

    public static boolean isSupplicantError(String msg) {
        return msg.contains("supplicant");
    }

    public static boolean isRootError(String msg) {
        return msg.contains("ermission") || msg.contains("su: not found");
    }

    public static boolean isWifiOK(String line) {
		return line.startsWith("WIFI: OK");
	}
}

