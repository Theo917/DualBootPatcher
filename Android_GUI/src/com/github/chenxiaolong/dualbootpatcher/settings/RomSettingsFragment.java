/*
 * Copyright (C) 2014-2016  Andrew Gunnerson <andrewgunnerson@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.github.chenxiaolong.dualbootpatcher.settings;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.preference.Preference;
import android.preference.Preference.OnPreferenceChangeListener;
import android.preference.Preference.OnPreferenceClickListener;
import android.preference.PreferenceFragment;
import android.widget.Toast;

import com.github.chenxiaolong.dualbootpatcher.BuildConfig;
import com.github.chenxiaolong.dualbootpatcher.MainApplication;
import com.github.chenxiaolong.dualbootpatcher.R;
import com.github.chenxiaolong.dualbootpatcher.ThreadPoolService.ThreadPoolServiceBinder;
import com.github.chenxiaolong.dualbootpatcher.Version;
import com.github.chenxiaolong.dualbootpatcher.dialogs.GenericConfirmDialog;
import com.github.chenxiaolong.dualbootpatcher.dialogs.GenericProgressDialog;
import com.github.chenxiaolong.dualbootpatcher.nativelib.LibMbp.Device;
import com.github.chenxiaolong.dualbootpatcher.nativelib.LibMbp.PatcherConfig;
import com.github.chenxiaolong.dualbootpatcher.patcher.PatcherService;
import com.github.chenxiaolong.dualbootpatcher.patcher.PatcherUtils;
import com.github.chenxiaolong.dualbootpatcher.socket.MbtoolErrorActivity;
import com.github.chenxiaolong.dualbootpatcher.socket.exceptions.MbtoolException.Reason;
import com.github.chenxiaolong.dualbootpatcher.switcher.SwitcherService;
import com.github.chenxiaolong.dualbootpatcher.switcher.service.BootUIActionTask.BootUIAction;
import com.github.chenxiaolong.dualbootpatcher.switcher.service.BootUIActionTask
        .BootUIActionTaskListener;

import java.util.ArrayList;

public class RomSettingsFragment extends PreferenceFragment implements OnPreferenceChangeListener, ServiceConnection, OnPreferenceClickListener {
    public static final String TAG = RomSettingsFragment.class.getSimpleName();

    private static final String PROGRESS_DIALOG_BOOT_UI = "boot_ui_progres_dialog";
    private static final String CONFIRM_DIALOG_BOOT_UI = "boot_ui_confirm_dialog";

    private static final String EXTRA_TASK_ID_GET_VERSION = "task_id_get_version";
    private static final String EXTRA_TASK_ID_INSTALL = "task_id_install";
    private static final String EXTRA_TASK_ID_UNINSTALL = "task_id_uninstall";

    private static final String KEY_BOOT_UI_INSTALL = "boot_ui_install";
    private static final String KEY_BOOT_UI_UNINSTALL = "boot_ui_uninstall";
    private static final String KEY_PARALLEL_PATCHING = "parallel_patching_threads";
    private static final String KEY_USE_DARK_THEME = "use_dark_theme";

    private Preference mBootUIInstallPref;
    private Preference mBootUIUninstallPref;
    private Preference mParallelPatchingPref;
    private Preference mUseDarkThemePref;

    private int mTaskIdGetVersion = -1;
    private int mTaskIdInstall = -1;
    private int mTaskIdUninstall = -1;

    /** Task IDs to remove */
    private ArrayList<Integer> mTaskIdsToRemove = new ArrayList<>();

    /** Switcher service */
    private SwitcherService mService;
    /** Callback for events from the service */
    private final ServiceEventCallback mCallback = new ServiceEventCallback();

    /** Handler for processing events from the service on the UI thread */
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getPreferenceManager().setSharedPreferencesName("settings");

        addPreferencesFromResource(R.xml.rom_settings);

        int threads = getPreferenceManager().getSharedPreferences().getInt(
                KEY_PARALLEL_PATCHING, PatcherService.DEFAULT_PATCHING_THREADS);

        mBootUIInstallPref = findPreference(KEY_BOOT_UI_INSTALL);
        mBootUIInstallPref.setEnabled(false);
        mBootUIInstallPref.setSummary(R.string.please_wait);
        mBootUIInstallPref.setOnPreferenceClickListener(this);

        mBootUIUninstallPref = findPreference(KEY_BOOT_UI_UNINSTALL);
        mBootUIUninstallPref.setEnabled(false);
        mBootUIUninstallPref.setSummary(R.string.please_wait);
        mBootUIUninstallPref.setOnPreferenceClickListener(this);

        mParallelPatchingPref = findPreference(KEY_PARALLEL_PATCHING);
        mParallelPatchingPref.setDefaultValue(Integer.toString(threads));
        mParallelPatchingPref.setOnPreferenceChangeListener(this);
        updateParallelPatchingSummary(threads);

        mUseDarkThemePref = findPreference(KEY_USE_DARK_THEME);
        mUseDarkThemePref.setOnPreferenceChangeListener(this);

        if (savedInstanceState != null) {
            mTaskIdGetVersion = savedInstanceState.getInt(EXTRA_TASK_ID_GET_VERSION);
            mTaskIdInstall = savedInstanceState.getInt(EXTRA_TASK_ID_INSTALL);
            mTaskIdUninstall = savedInstanceState.getInt(EXTRA_TASK_ID_UNINSTALL);
        }
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(EXTRA_TASK_ID_GET_VERSION, mTaskIdGetVersion);
        outState.putInt(EXTRA_TASK_ID_INSTALL, mTaskIdInstall);
        outState.putInt(EXTRA_TASK_ID_UNINSTALL, mTaskIdUninstall);
    }

    @Override
    public void onStart() {
        super.onStart();

        // Start and bind to the service
        Intent intent = new Intent(getActivity(), SwitcherService.class);
        getActivity().bindService(intent, this, Context.BIND_AUTO_CREATE);
        getActivity().startService(intent);
    }

    @Override
    public void onStop() {
        super.onStop();

        if (getActivity().isFinishing()) {
            if (mTaskIdGetVersion >= 0) {
                removeCachedTaskId(mTaskIdGetVersion);
                mTaskIdGetVersion = -1;
            }
        }

        // If we connected to the service and registered the callback, now we unregister it
        if (mService != null) {
            if (mTaskIdGetVersion >= 0) {
                mService.removeCallback(mTaskIdGetVersion, mCallback);
            }
        }

        // Unbind from our service
        getActivity().unbindService(this);
        mService = null;

        // At this point, the mCallback will not get called anymore by the service. Now we just need
        // to remove all pending Runnables that were posted to mHandler.
        mHandler.removeCallbacksAndMessages(null);
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
        // Save a reference to the service so we can interact with it
        ThreadPoolServiceBinder binder = (ThreadPoolServiceBinder) service;
        mService = (SwitcherService) binder.getService();

        // Remove old task IDs
        for (int taskId : mTaskIdsToRemove) {
            mService.removeCachedTask(taskId);
        }
        mTaskIdsToRemove.clear();

        if (mTaskIdGetVersion < 0) {
            getVersionIfSupported();
        } else {
            mService.addCallback(mTaskIdGetVersion, mCallback);
        }

        if (mTaskIdInstall >= 0) {
            mService.addCallback(mTaskIdInstall, mCallback);
        }

        if (mTaskIdUninstall >= 0) {
            mService.addCallback(mTaskIdUninstall, mCallback);
        }
    }

    @Override
    public void onServiceDisconnected(ComponentName componentName) {
        mService = null;
    }

    private void removeCachedTaskId(int taskId) {
        if (mService != null) {
            mService.removeCachedTask(taskId);
        } else {
            mTaskIdsToRemove.add(taskId);
        }
    }

    private void getVersionIfSupported() {
        boolean supported = false;

        PatcherConfig pc = new PatcherConfig();
        Device device = PatcherUtils.getCurrentDevice(getActivity(), pc);
        if (device != null && device.isBootUISupported()) {
            supported = true;
        }
        pc.destroy();

        if (supported) {
            if (mTaskIdGetVersion < 0) {
                mTaskIdGetVersion = mService.bootUIAction(BootUIAction.GET_VERSION);
                mService.addCallback(mTaskIdGetVersion, mCallback);
                mService.enqueueTaskId(mTaskIdGetVersion);
            }
        } else {
            mBootUIInstallPref.setEnabled(false);
            mBootUIInstallPref.setSummary(R.string.rom_settings_boot_ui_not_supported);
            mBootUIUninstallPref.setEnabled(false);
            mBootUIUninstallPref.setSummary(R.string.rom_settings_boot_ui_not_supported);
        }
    }

    private void onHaveVersion(Version version) {
        removeCachedTaskId(mTaskIdGetVersion);
        mTaskIdGetVersion = -1;

        boolean installEnabled;
        String installTitle;
        String installSummary = null;
        boolean uninstallEnabled;
        String uninstallSummary = null;

        mBootUIInstallPref.setSummary(null);
        mBootUIUninstallPref.setSummary(null);

        if (version == null) {
            installEnabled = true;
            installTitle = getString(R.string.rom_settings_boot_ui_install_title);
            uninstallEnabled = false;
            uninstallSummary = getString(R.string.rom_settings_boot_ui_not_installed);
        } else {
            Version newest = Version.from(BuildConfig.VERSION_NAME);
            if (newest == null) {
                throw new IllegalStateException("App has invalid version number: " +
                        BuildConfig.VERSION_NAME);
            }

            if (version.compareTo(newest) < 0) {
                installSummary = getString(
                        R.string.rom_settings_boot_ui_update_available, newest.toString());
                installEnabled = true;
            } else {
                installSummary = getString(R.string.rom_settings_boot_ui_up_to_date);
                installEnabled = false;
            }

            installTitle = getString(R.string.rom_settings_boot_ui_update_title);

            uninstallEnabled = true;
        }

        mBootUIInstallPref.setEnabled(installEnabled);
        mBootUIInstallPref.setTitle(installTitle);
        mBootUIInstallPref.setSummary(installSummary);
        mBootUIUninstallPref.setEnabled(uninstallEnabled);
        mBootUIUninstallPref.setSummary(uninstallSummary);
    }

    private void onInstalled(boolean success) {
        removeCachedTaskId(mTaskIdInstall);
        mTaskIdInstall = -1;

        GenericProgressDialog d = (GenericProgressDialog)
                getFragmentManager().findFragmentByTag(PROGRESS_DIALOG_BOOT_UI);
        if (d != null) {
            d.dismiss();
        }

        getVersionIfSupported();

        Toast.makeText(getActivity(),
                success ? R.string.rom_settings_boot_ui_install_success :
                        R.string.rom_settings_boot_ui_install_failure, Toast.LENGTH_LONG).show();

        if (success) {
            GenericConfirmDialog d2 = GenericConfirmDialog.newInstanceFromFragment(
                    null, 0, null, getString(R.string.rom_settings_boot_ui_update_ramdisk_msg),
                    getString(R.string.ok));
            d2.show(getFragmentManager(), CONFIRM_DIALOG_BOOT_UI);
        }
    }

    private void onUninstalled(boolean success) {
        removeCachedTaskId(mTaskIdUninstall);
        mTaskIdUninstall = -1;

        GenericProgressDialog d = (GenericProgressDialog)
                getFragmentManager().findFragmentByTag(PROGRESS_DIALOG_BOOT_UI);
        if (d != null) {
            d.dismiss();
        }

        getVersionIfSupported();

        Toast.makeText(getActivity(),
                success ? R.string.rom_settings_boot_ui_uninstall_success :
                        R.string.rom_settings_boot_ui_uninstall_failure, Toast.LENGTH_LONG).show();
    }

    @Override
    public boolean onPreferenceClick(Preference preference) {
        if (preference == mBootUIInstallPref) {
            mTaskIdInstall = mService.bootUIAction(BootUIAction.INSTALL);
            mService.addCallback(mTaskIdInstall, mCallback);
            mService.enqueueTaskId(mTaskIdInstall);

            GenericProgressDialog d = GenericProgressDialog.newInstance(0, R.string.please_wait);
            d.show(getFragmentManager(), PROGRESS_DIALOG_BOOT_UI);
            return true;
        } else if (preference == mBootUIUninstallPref) {
            mTaskIdUninstall = mService.bootUIAction(BootUIAction.UNINSTALL);
            mService.addCallback(mTaskIdUninstall, mCallback);
            mService.enqueueTaskId(mTaskIdUninstall);

            GenericProgressDialog d = GenericProgressDialog.newInstance(0, R.string.please_wait);
            d.show(getFragmentManager(), PROGRESS_DIALOG_BOOT_UI);
            return true;
        }

        return false;
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        if (preference == mParallelPatchingPref) {
            try {
                int threads = Integer.parseInt(newValue.toString());
                if (threads >= 1) {
                    // Do this instead of using EditTextPreference's built-in persisting since we
                    // want it saved as an integer
                    SharedPreferences.Editor editor =
                            getPreferenceManager().getSharedPreferences().edit();
                    editor.putInt(KEY_PARALLEL_PATCHING, threads);
                    editor.apply();

                    updateParallelPatchingSummary(threads);
                    return true;
                }
            } catch (NumberFormatException e) {
            }
        } else if (preference == mUseDarkThemePref) {
            // Apply dark theme and recreate activity
            MainApplication.setUseDarkTheme((Boolean) newValue);
            getActivity().recreate();
            return true;
        }
        return false;
    }

    private void updateParallelPatchingSummary(int threads) {
        String summary = getString(R.string.rom_settings_parallel_patching_desc, threads);
        mParallelPatchingPref.setSummary(summary);
    }

    private class ServiceEventCallback implements BootUIActionTaskListener {
        @Override
        public void onBootUIHaveVersion(int taskId, final Version version) {
            if (taskId == mTaskIdGetVersion) {
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        onHaveVersion(version);
                    }
                });
            }
        }

        @Override
        public void onBootUIInstalled(int taskId, final boolean success) {
            if (taskId == mTaskIdInstall) {
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        onInstalled(success);
                    }
                });
            }
        }

        @Override
        public void onBootUIUninstalled(int taskId, final boolean success) {
            if (taskId == mTaskIdUninstall) {
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        onUninstalled(success);
                    }
                });
            }
        }

        @Override
        public void onMbtoolConnectionFailed(int taskId, final Reason reason) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    Intent intent = new Intent(getActivity(), MbtoolErrorActivity.class);
                    intent.putExtra(MbtoolErrorActivity.EXTRA_REASON, reason);
                    startActivity(intent);
                }
            });
        }
    }
}
