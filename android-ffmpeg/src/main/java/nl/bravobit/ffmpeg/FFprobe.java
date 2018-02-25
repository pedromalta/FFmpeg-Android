package nl.bravobit.ffmpeg;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Array;
import java.util.Map;

import nl.bravobit.ffmpeg.exceptions.FFprobeCommandAlreadyRunningException;

public class FFprobe implements FFbinaryInterface {
    private static final int VERSION = 12; // up this version when you add a new ffprobe build
    private static final String KEY_PREF_VERSION = "ffprobe_version";

    private final FFbinaryContextProvider context;
    private FFcommandExecuteAsyncTask ffprobeExecuteAsyncTask;

    private static final long MINIMUM_TIMEOUT = 10 * 1000;
    private long timeout = Long.MAX_VALUE;

    private static FFprobe instance = null;

    private FFprobe(FFbinaryContextProvider context) {
        this.context = context;
        Log.setDebug(Util.isDebug(this.context.provide()));
    }

    public static FFprobe getInstance(final Context context) {
        if (instance == null) {
            instance = new FFprobe(new FFbinaryContextProvider() {
                @Override
                public Context provide() {
                    return context;
                }
            });
        }
        return instance;
    }

    @Override
    public boolean isSupported() {
        // check if arch is supported
        CpuArch cpuArch = CpuArchHelper.getCpuArch();
        if (cpuArch == CpuArch.NONE) {
            Log.e("arch not supported");
            return false;
        }

        // get ffprobe file
        File ffprobe = FileUtils.getFFprobe(context.provide());

        SharedPreferences settings = context.provide().getSharedPreferences("ffmpeg_prefs", Context.MODE_PRIVATE);
        int version = settings.getInt(KEY_PREF_VERSION, 0);

        // check if ffprobe file exists
        if (!ffprobe.exists() || version < VERSION) {
            String prefix = "arm/";
            if (cpuArch == CpuArch.x86) {
                prefix = "x86/";
            }
            Log.d("file does not exist, creating it...");

            try {
                InputStream inputStream = context.provide().getAssets().open(prefix + "ffprobe");
                if (!FileUtils.inputStreamToFile(inputStream, ffprobe)) {
                    return false;
                }

                Log.d("successfully wrote ffprobe file!");

                settings.edit().putInt(KEY_PREF_VERSION, VERSION).apply();
            } catch (IOException e) {
                Log.e("error while opening assets", e);
                return false;
            }
        }

        // check if ffprobe can be executed
        if (!ffprobe.canExecute()) {
            // try to make executable
            try {
                try {
                    Runtime.getRuntime().exec("chmod -R 777 " + ffprobe.getAbsolutePath()).waitFor();
                } catch (InterruptedException e) {
                    Log.e("interrupted exception", e);
                    return false;
                } catch (IOException e) {
                    Log.e("io exception", e);
                    return false;
                }

                if (!ffprobe.canExecute()) {
                    // our last hope!
                    if (!ffprobe.setExecutable(true)) {
                        Log.e("unable to make executable");
                        return false;
                    }
                }
            } catch (SecurityException e) {
                Log.e("security exception", e);
                return false;
            }
        }

        Log.d("ffprobe is ready!");

        return true;
    }

    @Override
    public FFtask execute(Map<String, String> environvenmentVars, String[] cmd, FFcommandExecuteResponseHandler ffcommandExecuteResponseHandler) throws FFprobeCommandAlreadyRunningException {
        if (ffprobeExecuteAsyncTask != null && !ffprobeExecuteAsyncTask.isProcessCompleted()) {
            throw new FFprobeCommandAlreadyRunningException("FFprobe command is already running, you are only allowed to run single command at a time");
        }
        if (cmd.length != 0) {
            String[] ffprobeBinary = new String[]{FileUtils.getFFprobeCommand(context.provide(), environvenmentVars)};
            String[] command = concatenate(ffprobeBinary, cmd);
            ffprobeExecuteAsyncTask = new FFcommandExecuteAsyncTask(command, timeout, ffcommandExecuteResponseHandler);
            ffprobeExecuteAsyncTask.execute();
            return ffprobeExecuteAsyncTask;
        } else {
            throw new IllegalArgumentException("shell command cannot be empty");
        }
    }

    private static <T> T[] concatenate(T[] a, T[] b) {
        int aLen = a.length;
        int bLen = b.length;

        @SuppressWarnings("unchecked")
        T[] c = (T[]) Array.newInstance(a.getClass().getComponentType(), aLen + bLen);
        System.arraycopy(a, 0, c, 0, aLen);
        System.arraycopy(b, 0, c, aLen, bLen);

        return c;
    }

    @Override
    public FFtask execute(String[] cmd, FFcommandExecuteResponseHandler ffcommandExecuteResponseHandler) throws FFprobeCommandAlreadyRunningException {
        return execute(null, cmd, ffcommandExecuteResponseHandler);
    }

    @Override
    public boolean isCommandRunning() {
        return ffprobeExecuteAsyncTask != null && !ffprobeExecuteAsyncTask.isProcessCompleted();
    }

    @Override
    public boolean killRunningProcesses() {
        boolean status = Util.killAsync(ffprobeExecuteAsyncTask);
        ffprobeExecuteAsyncTask = null;
        return status;
    }

    @Override
    public void setTimeout(long timeout) {
        if (timeout >= MINIMUM_TIMEOUT) {
            this.timeout = timeout;
        }
    }

    @Override
    public void sendQuitSignal() {
        ffprobeExecuteAsyncTask.sendQuitSignal();
    }


    @Override
    public FFbinaryObserver whenFFbinaryIsReady(Runnable onReady, int timeout) {
        return Util.observeOnce(new Util.ObservePredicate() {
            @Override
            public Boolean isReadyToProceed() {
                return !isCommandRunning();
            }
        }, onReady, timeout);
    }
}
