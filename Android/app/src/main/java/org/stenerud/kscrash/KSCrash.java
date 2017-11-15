package org.stenerud.kscrash;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;


/**
 * CrashSDK的操作工具类
 */
public enum KSCrash {

    INSTANCE;


    private IDealWithCrash mIDealWithCrash;

    public KSCrash setIDealWithCrash(IDealWithCrash iDealWithCrash) {
        this.mIDealWithCrash = iDealWithCrash;
        return this;
    }


    public enum MonitorType {
        // Note: This must be kept in sync with KSCrashMonitorType.h
        None(0),

        Signal(0x02),
        CPPException(0x04),
        UserReported(0x20),
        System(0x40),
        ApplicationState(0x80),

        All(Signal.value | CPPException.value | UserReported.value | System.value | ApplicationState.value),
        Experimental(None.value),
        Optional(None.value),
        Required(System.value | ApplicationState.value),

        DebuggerUnsafe(Signal.value | CPPException.value),
        DebuggerSafe(All.value & ~DebuggerUnsafe.value),
        RequiresAsyncSafety(Signal.value),
        RequiresNoAsyncSafety(All.value & ~RequiresAsyncSafety.value),
        ProductionSafe(All.value & ~Experimental.value),
        ProductionSafeMinimal(ProductionSafe.value & ~Optional.value),
        Manual(Required.value | UserReported.value);

        public final int value;

        MonitorType(int value) {
            this.value = value;
        }
    }

    static {
        Log.e("KSCrash---------", "loadlibrary");
        System.loadLibrary("kscrash-lib");
        initJNI();
    }

    public static KSCrash getInstance() {
        return INSTANCE;
    }

    private static Thread.UncaughtExceptionHandler oldUncaughtExceptionHandler;



    /**
     * Install the crash reporter.
     *
     * @param context The application context.
     */
    public void install(Context context) throws IOException {
        //todo 初始化jni异常的监听
        //String appName = context.getApplicationInfo().processName;
        //File installDir = new File(context.getCacheDir().getAbsolutePath(), "KSCrash");
        // installJNI(appName, installDir.getCanonicalPath());

        // TODO: Put this elsewhere 若应用接入其它的crash第三方sdk，在CrashSDK处理完后抛出异常
        oldUncaughtExceptionHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {   //若有其它的第三方处理则抛出（如OneSDK）
                KSCrash.this.reportJavaException(e);
                KSCrash.oldUncaughtExceptionHandler.uncaughtException(t, e);
            }
        });
    }

    /**
     * Add a custom report to the store.
     * @param report The report's contents (must be JSON encodable).
     */
    public void addUserReport(Map report) {
        JSONObject object = new JSONObject(report);
        internalAddUserReportJSON(object.toString());
    }

    /**
     * Report a custom, user defined exception.
     * This can be useful when dealing with scripting languages.
     * <p>
     * If terminateProgram is true, all sentries will be uninstalled and the application will
     * terminate with an abort().
     *
     * @param name                   The exception name (for namespacing exception types).
     * @param reason                 A description of why the exception occurred.
     * @param language               A unique language identifier.
     * @param lineOfCode             A copy of the offending line of code (NULL = ignore).
     * @param stackTrace             JSON encoded array containing stack trace information (one frame per array entry).
     *                               The frame structure can be anything you want, including bare strings.
     * @param shouldLogAllThreads    If true, suspend all threads and log their state. Note that this incurs a
     *                               performance penalty, so it's best to use only on fatal errors.
     * @param shouldTerminateProgram If true, do not return from this function call. Terminate the program instead.
     */
    public void reportUserException(String name,
                                    String reason,
                                    String language,
                                    String module,
                                    int lineOfCode,
                                    JSONArray stackTrace,
                                    boolean shouldLogAllThreads,
                                    boolean shouldTerminateProgram) {
        String line = module + " line " + lineOfCode;
        internalReportUserException(name, reason, language, line, stackTrace.toString(),
                shouldLogAllThreads, shouldTerminateProgram);
    }

    /**
     * todo 上报java异常到服务器
     * @param throwable The exception.
     */
    public void reportJavaException(Throwable throwable) {
        if(mIDealWithCrash != null){   //有自定义处理异常
            mIDealWithCrash.dealWithCrash(throwable, getExceptionTrace(throwable));
        }else{  //CrashSDK默认处理异常
            try {
                JSONArray array = new JSONArray();
                for (StackTraceElement element : throwable.getStackTrace()) {
                    JSONObject object = new JSONObject();
                    object.put("file", element.getFileName());
                    object.put("line", element.getLineNumber());
                    object.put("class", element.getClassName());
                    object.put("method", element.getMethodName());
                    object.put("native", element.isNativeMethod());
                    array.put(object);
                }
                reportUserException(throwable.getClass().getName(),
                        throwable.getMessage(),
                        "java",
                        throwable.getStackTrace()[0].getFileName(),
                        throwable.getStackTrace()[0].getLineNumber(),
                        array,
                        false,
                        false);
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * log输出日志
     * @param throwable
     * @return
     */
    public static String getExceptionTrace(Throwable throwable) {
        if (throwable != null) {
            StringWriter stringWriter = new StringWriter();
            PrintWriter printWriter = new PrintWriter(stringWriter);
            throwable.printStackTrace(printWriter);
            return stringWriter.toString();
        }
        return "No Exception";
    }

    /**
     * Set the user-supplied data.
     *
     * @param userInfo JSON-encodable user-supplied information.
     */
    public void setUserInfo(Map userInfo) {
        JSONObject object = new JSONObject(userInfo);
        internalSetUserInfoJSON(object.toString());
    }

    /**
     * Set which monitors will be active..
     * Some crash monitors may not be enabled depending on circumstances (e.g. running
     * in a debugger).
     *
     * @param monitors The monitors to install.
     */
    public void setActiveMonitors(MonitorType[] monitors) {
        int activeMonitors = 0;
        for (MonitorType monitor : monitors) {
            activeMonitors |= monitor.value;
        }
        internalSetActiveMonitors(activeMonitors);
    }

    /**
     * Get all the crash reports.
     *
     * @return The crash reports.
     */
    public List<JSONObject> getAllReports() {
        List<String> rawReports = internalGetAllReports();
        List<JSONObject> reports = new ArrayList<JSONObject>(rawReports.size());
        for (String rawReport : rawReports) {
            JSONObject report;
            try {
                report = new JSONObject(rawReport);
                reports.add(report);
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }
        return reports;
    }

    /**
     * Delete all reports on disk.
     */
    public native void deleteAllReports();

    /**
     * 设置本地能够存储的最大日志数目
     * Set the maximum number of reports allowed on disk before old ones get deleted.
     *
     * @param maxReportCount The maximum number of reports.
     */
    public native void setMaxReportCount(int maxReportCount);

    /**
     * 是否记录内存信息
     * If true, introspect memory contents during a crash.
     * C strings near the stack pointer or referenced by cpu registers or exceptions will be
     * recorded in the crash report, along with their contents.
     * <p>
     * Default: false
     */
    public native void setIntrospectMemory(boolean shouldIntrospectMemory);

    /**
     * 设置KSLOG是否显示在日志中
     * Set if KSLOG console messages should be appended to the report.
     *
     * @param shouldAddConsoleLogToReport If true, add the log to the report.
     */
    public native void setAddConsoleLogToReport(boolean shouldAddConsoleLogToReport);

    /**
     * 判断接入CrashSDK的应用是否存活
     * Notify the crash reporter of the application active state.
     *
     * @param isActive true if the application is active, otherwise false.
     */
    public native void notifyAppActive(boolean isActive);

    /**
     * 判断接入CrashSDK的应用是否在前台
     * Notify the crash reporter of the application foreground/background state.
     *
     * @param isInForeground true if the application is in the foreground, false if
     *                       it is in the background.
     */
    public native void notifyAppInForeground(boolean isInForeground);

    /**
     * 判断接入CrashSDK的应用是否终止
     * Notify the crash reporter that the application is terminating.
     */
    public native void notifyAppTerminate();

    /**
     * 通知CrashSDK应用崩溃
     * Notify the crash reporter that the application has crashed.
     */
    public native void notifyAppCrash();

    /**
     *
     * @param activeMonitors
     */
    private native void internalSetActiveMonitors(int activeMonitors);

    /**
     * 初始化jni
     */
    private static native void initJNI();

    /**
     * 初始化CrashSDK，底层设置crash的监听器
     * @param appName
     * @param installDir
     */
    private native void installJNI(String appName, String installDir);

    /**
     *
     * @return
     */
    private native List<String> internalGetAllReports();

    /**
     *
     * @param userReportJSON
     */
    private native void internalAddUserReportJSON(String userReportJSON);

    /**
     *
     * @param userInfoJSON
     */
    private native void internalSetUserInfoJSON(String userInfoJSON);

    /**
     *
     * @param name
     * @param reason
     * @param language
     * @param lineOfCode
     * @param stackTraceJSON
     * @param shouldLogAllThreads
     * @param shouldTerminateProgram
     */
    private native void internalReportUserException(String name,
                                                    String reason,
                                                    String language,
                                                    String lineOfCode,
                                                    String stackTraceJSON,
                                                    boolean shouldLogAllThreads,
                                                    boolean shouldTerminateProgram);
}
