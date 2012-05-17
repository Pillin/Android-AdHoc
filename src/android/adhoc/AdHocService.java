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


public class AdHocService extends android.app.Service {
    final static String TAG = "AdHocService";
    
    private static final int THREAD_OUTPUT	= 0;
	private static final int THREAD_ERROR	= 1;
    final static int MSG_OUTPUT     = 1;
    final static int MSG_ERROR      = 2;
    final static int MSG_EXCEPTION  = 3;
    final static int MSG_NETSCHANGE = 4;
    final static int MSG_START      = 5;
    final static int MSG_STOP       = 6;
    public final static int STATE_STOPPED  = 0;
    public final static int STATE_STARTING = 1;
    public final static int STATE_RUNNING  = 2;
    
    private AdHocApp adHocApp;
    private int state = STATE_STOPPED;
    private Process process = null;
    private Thread[] threads = new Thread[2];
    private PowerManager.WakeLock wakeLock;
    private WifiManager wifiManager;
    private Method mStartForeground = null;
    public static AdHocService singleton = null;  // WARNING: this is not entirely safe
   
    
    private class OutputMonitor implements Runnable {
    	final private int bufferedSize = 8192;
        private final java.io.BufferedReader br;
        private final int msg;
        public OutputMonitor(int msgType, java.io.InputStream is) {
            br = new java.io.BufferedReader(new java.io.InputStreamReader(is), bufferedSize);
            msg = msgType;
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
    
    private BroadcastReceiver connectivityReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            mHandler.sendEmptyMessage(MSG_NETSCHANGE);
        }
    };


    @Override
    public void onCreate() {
    	super.onCreate();
    	Log.d(TAG, String.format(getString(R.string.creating), this.getClass().getSimpleName()));
    	
        singleton = this;
        wifiManager = (WifiManager) getSystemService(Context.WIFI_SERVICE);
        try {
            mStartForeground = getClass().getMethod("startForeground", new Class[] {
                    int.class, Notification.class});
        } catch (NoSuchMethodException e) {
            mStartForeground = null;
        }

        state = STATE_STOPPED;
        adHocApp = (AdHocApp)getApplication();
        adHocApp.setAdHocService(this);
        this.start();
        
        // Unlock recive UDP ports
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AdHocService");
        wakeLock.acquire();

        IntentFilter filter = new IntentFilter();
        filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        registerReceiver(connectivityReceiver, filter);
        Log.d(TAG, String.format(getString(R.string.created), this.getClass().getSimpleName()));
    }

    @Override
    public void onDestroy() {
        if (state != STATE_STOPPED) {
        	Log.e(TAG, getString(R.string.destroyWhileRunning));
        }
        
        // ensure we clean up
        stopNativeProcess();
        state = STATE_STOPPED;
        adHocApp.processStopped();
        wakeLock.release();

        try {
            unregisterReceiver(connectivityReceiver);
        } catch (Exception e) {
        }

        singleton = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
    
    
    public void start() {
        mHandler.sendEmptyMessage(MSG_START);
    }

    public void stopAdHocService() {
        mHandler.sendEmptyMessage(MSG_STOP);
    }

    private void handle(Message msg) {
        switch (msg.what) {
        case MSG_EXCEPTION:
            if (state == STATE_STOPPED) {
            	return;
            }
            Throwable thr = (Throwable)msg.obj;
            thr.printStackTrace();
            stopNativeProcess();
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
                    if (NativeHelper.isRootError(line)) {
                        adHocApp.failed(AdHocApp.ERROR_ROOT);
                    }
                    else if (NativeHelper.isSupplicantError(line)) {
                        adHocApp.failed(AdHocApp.ERROR_SUPPLICANT);
                    }
                    else {
                        adHocApp.failed(AdHocApp.ERROR_OTHER);
                    }
                }
                else {
                    adHocApp.failed(AdHocApp.ERROR_OTHER);
                }
            }
            else {
            	stopNativeProcess();
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
            else if (NativeHelper.isWifiOK(line)) {
                if (state == STATE_STARTING) {
                    state = STATE_RUNNING;
                    String startedFormat = getString(R.string.started);
                    Log.d(TAG, String.format(startedFormat, this.getClass().getSimpleName()));
                    adHocApp.processStarted();
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

            if (!NativeHelper.existAssets(this)) {
            	String format = getString(R.string.assetsProblem);
                Log.e(TAG, String.format(format, this.getClass().getSimpleName()));
                state = STATE_STOPPED;
                break;
            }
            state = STATE_STARTING;
        case MSG_NETSCHANGE:
            int wifiState = wifiManager.getWifiState();
            String proccesID = process == null ? "null" : "notNull";
            String formatString = getString(R.string.netschange);
            String formatedString = String.format(formatString, wifiState, state, proccesID); 
            Log.w(TAG, formatedString);
            if (wifiState == WifiManager.WIFI_STATE_DISABLED) {
                // wifi is good (or lost)
            	if ((state == STATE_STARTING) && (process == null)) {
            		if (!startNativeProcess()) {
                        Log.e(TAG, getString(R.string.starterr));
                        state = STATE_STOPPED;
                        break;
                    }
                }
            } else {
                if (state == STATE_RUNNING) {
                    // this is super bad, will have to restart!
                    adHocApp.updateToast(getString(R.string.conflictwifi), true);
                    Log.e(TAG, getString(R.string.conflictwifi));
                    stopNativeProcess();
                    Log.d(TAG, getString(R.string.restarting));
                    wifiManager.setWifiEnabled(false); // this will send MSG_NETSCHANGE
                    // TODO we should wait until wifi is disabled...
                    state = STATE_STARTING;
                }
                else if (state == STATE_STARTING) {
                    if ((wifiState == WifiManager.WIFI_STATE_ENABLED) || (wifiState == WifiManager.WIFI_STATE_ENABLING)) {
                        adHocApp.updateToast(getString(R.string.disablewifi), false);
                        wifiManager.setWifiEnabled(false);
                        Log.d(TAG, getString(R.string.waitwifi));
                    }
                }
            }
            break;
        case MSG_STOP:
            if (state == STATE_STOPPED) return;
            stopNativeProcess();
            state = STATE_STOPPED;
            String stoppedFormat = getString(R.string.stopped);
            Log.d(TAG, String.format(stoppedFormat, this.getClass().getSimpleName()));
            break;
        }
        adHocApp.updateStatus();
        if (state == STATE_STOPPED) {
            adHocApp.processStopped();
        }
    }
    

    public int getState() {
        return state;
    }
    
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

    private boolean startNativeProcess() {
    	String cmd = NativeHelper.SU_C;
        try {
        	Runtime runtime = Runtime.getRuntime();
            process = runtime.exec(cmd, getEnvironmentFromPrefs(), NativeHelper.app_bin);
            threads[THREAD_OUTPUT] = new Thread(new OutputMonitor(MSG_OUTPUT, process.getInputStream()));
            threads[THREAD_ERROR] = new Thread(new OutputMonitor(MSG_ERROR, process.getErrorStream()));
            threads[THREAD_OUTPUT].start();
            threads[THREAD_ERROR].start();
        } catch (Exception e) {
            Log.e(TAG, String.format(getString(R.string.execerr), cmd));
            Log.e(TAG, "start failed " + e.toString());
            return false;
        }
        return true;
    }

    private void stopNativeProcess() {
        if (process != null) {
            if (state != STATE_STOPPED) {
                try {
                    process.getOutputStream().close();
                } catch (Exception e) {
                	e.printStackTrace();
                }
            }
            try {
                process.waitFor();
            } catch (InterruptedException e) {
            }

            try {
                int exit_status = process.exitValue();
                String formatString = getString(R.string.nativeProcess); 
                Log.i(TAG, String.format(formatString, exit_status));
            } catch (IllegalThreadStateException e) {
            	e.printStackTrace();
                Log.e(TAG, getString(R.string.dirtystop));
            }
            process.destroy();
            process = null;
            threads[THREAD_OUTPUT].interrupt();
            threads[THREAD_ERROR].interrupt();
        }
    }

    
    public void startForegroundCompat(int id, Notification notification) {
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
}

