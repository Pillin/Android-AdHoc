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

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.ToggleButton;


public class AdHocActivity extends Activity {
	
	final static int DLG_ROOT = 1;
    final static int DLG_ERROR = 2;
    final static int DLG_SUPPLICANT = 3;
    final static int DLG_ASSETS = 4;
    final static int DLG_STARTING = 5;
    final static int DLG_STOPPING = 6;
	
	private AdHocApp adHocApp;
    private ToggleButton onoff;
    

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        this.adHocApp = (AdHocApp)getApplication();
        setContentView(R.layout.main);

        this.onoff = (ToggleButton) findViewById(R.id.onoff);
        this.onoff.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
            	onoff.setPressed(true);
                if (onoff.isChecked()) {
                	adHocApp.startAdHoc();
                }
                else {
                	adHocApp.stopAdHoc();
                }
            }
        });

        this.adHocApp.setAdHocActivity(this);
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        adHocApp.setAdHocActivity(null);
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        this.updateActivityContent();
        adHocApp.cleanUpNotifications();
    }

    
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu, menu);
        return true;
    }
    
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
	        case R.id.menu_prefs:
	            startActivity(new Intent(this, SettingsActivity.class));
	            return true;
        }
        return(super.onOptionsItemSelected(item));
    }

    
    // TODO: FVALVERD This method is deprecated. Use the new DialogFragment class with FragmentManager instead;
    @Override
    protected Dialog onCreateDialog(int id) {
        // TODO: these should not create and remove dialogs, but restore and dismiss
        if (id == DLG_ROOT) {
            return (new AlertDialog.Builder(this))
                .setIcon(android.R.drawable.ic_dialog_alert)
                .setTitle("AdHoc Root Access")
                .setMessage("AdHoc requires 'su' to access the hardware! Please, make sure you have root access.")
                .setPositiveButton("Help", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Uri uri = Uri.parse(getString(R.string.rootUrl));
                        startActivity(new Intent(Intent.ACTION_VIEW, uri));
                    }
                 })
                .setNegativeButton("Close", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                    	removeDialog(DLG_ROOT);
                    }
                 })
                .create();
        }
        else if (id == DLG_ERROR) {
        	// TODO: FVALVERD pasar este texto a string.xml
            return (new AlertDialog.Builder(this))
                .setIcon(android.R.drawable.ic_dialog_alert)
                .setTitle("AdHoc Error")
                .setMessage("Unexpected error occured! Check the troubleshooting guide for the error printed in the log tab.")
                .setPositiveButton("Help", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Uri uri = Uri.parse(getString(R.string.fixUrl));
                        startActivity(new Intent(Intent.ACTION_VIEW, uri));
                    }
                })
                .setNegativeButton("Close", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                    	removeDialog(DLG_ERROR);
                    }
                })
                .create();
        }
        else if (id == DLG_SUPPLICANT) {
        	// TODO: FVALVERD pasar este texto a string.xml
            return (new AlertDialog.Builder(this))
                .setIcon(android.R.drawable.ic_dialog_alert)
                .setTitle("AdHoc Supplicant not available")
                .setMessage("AdHoc had trouble starting wpa_supplicant. Try again but set 'Skip wpa_supplicant' in settings.")
                .setPositiveButton("Do it now!", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        adHocApp.prefs.edit().putBoolean(getString(R.string.lan_wext), true).commit();
                        adHocApp.updateToast("Settings updated, try again...", true);
                    }
                })
                .setNegativeButton("Close", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                    	removeDialog(DLG_ROOT);
                    }
                })
                .create();
        }
        else if (id == DLG_ASSETS) {
        	// TODO: FVALVERD pasar este texto a string.xml
            return (new AlertDialog.Builder(this))
                .setIcon(android.R.drawable.ic_dialog_alert)
                .setTitle("AdHoc Assets does not exist!")
                .setMessage("Check if AdHoc App have assets folder.")
                .setNegativeButton("Close", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                    	removeDialog(DLG_ROOT);
                    }
                })
                .create();
        }
        else if (id == DLG_STARTING) {
        	ProgressDialog progressDialog = new ProgressDialog(this);
	    	progressDialog.setTitle(getString(R.string.adhocStarting));
	    	progressDialog.setMessage(getString(R.string.adhocStartingMessage));
	    	progressDialog.setIndeterminate(false);
	    	progressDialog.setCancelable(true);
	        return progressDialog;
        }
        else if (id == DLG_STOPPING) {
        	ProgressDialog progressDialog = new ProgressDialog(this);
	    	progressDialog.setTitle(getString(R.string.adhocStopping));
	    	progressDialog.setMessage(getString(R.string.adhocStoppingMessage));
	    	progressDialog.setIndeterminate(false);
	    	progressDialog.setCancelable(true);
	        return progressDialog;
        }
        return null;
    }

    
    void updateActivityContent() {
        if (this.adHocApp.adHocService != null) {
        	switch (this.adHocApp.getState()) {
				case AdHocService.STATE_STOPPED: {
					this.onoff.setChecked(false);
					break;
				}
				case AdHocService.STATE_STARTING: {
					this.onoff.setPressed(true);
					this.onoff.setChecked(true);
					break;
				}
				case AdHocService.STATE_RUNNING: {
					this.onoff.setPressed(false);
					this.onoff.setChecked(true);
					break;
				}
			}
        }
    }
}
