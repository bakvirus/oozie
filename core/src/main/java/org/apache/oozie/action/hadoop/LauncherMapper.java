/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.oozie.action.hadoop;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.security.Permission;
import java.text.MessageFormat;
import java.util.Properties;
import java.util.StringTokenizer;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.mapred.Counters;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.Mapper;
import org.apache.hadoop.mapred.OutputCollector;
import org.apache.hadoop.mapred.Reporter;
import org.apache.hadoop.mapred.RunningJob;
import org.apache.oozie.service.HadoopAccessorException;
import org.apache.oozie.service.HadoopAccessorService;
import org.apache.oozie.service.Services;
import org.apache.oozie.util.XLog;

public class LauncherMapper<K1, V1, K2, V2> implements Mapper<K1, V1, K2, V2>, Runnable {

    public static final String CONF_OOZIE_ACTION_MAIN_CLASS = "oozie.launcher.action.main.class";

    private static final String CONF_OOZIE_ACTION_MAIN_ARG_COUNT = "oozie.action.main.arg.count";
    private static final String CONF_OOZIE_ACTION_MAIN_ARG_PREFIX = "oozie.action.main.arg.";
    private static final String CONF_OOZIE_ACTION_MAX_OUTPUT_DATA = "oozie.action.max.output.data";
    private static final String CONF_OOZIE_EXTERNAL_STATS_MAX_SIZE = "oozie.external.stats.max.size";

    private static final String COUNTER_GROUP = "oozie.launcher";
    private static final String COUNTER_DO_ID_SWAP = "oozie.do.id.swap";
    private static final String COUNTER_OUTPUT_DATA = "oozie.output.data";
    private static final String COUNTER_STATS_DATA = "oozie.stats.data";
    private static final String COUNTER_LAUNCHER_ERROR = "oozie.launcher.error";

    private static final String OOZIE_JOB_ID = "oozie.job.id";
    private static final String OOZIE_ACTION_ID = "oozie.action.id";

    private static final String OOZIE_ACTION_DIR_PATH = "oozie.action.dir.path";
    private static final String OOZIE_ACTION_RECOVERY_ID = "oozie.action.recovery.id";

    public static final String ACTION_PREFIX = "oozie.action.";
    public static final String EXTERNAL_CHILD_IDS = ACTION_PREFIX + "externalChildIDs.properties";
    public static final String EXTERNAL_ACTION_STATS = ACTION_PREFIX + "stats.properties";

    static final String ACTION_CONF_XML = "action.xml";
    private static final String ACTION_OUTPUT_PROPS = "output.properties";
    private static final String ACTION_STATS_PROPS = "stats.properties";
    private static final String ACTION_EXTERNAL_CHILD_IDS_PROPS = "externalChildIds.properties";
    private static final String ACTION_NEW_ID_PROPS = "newId.properties";
    private static final String ACTION_ERROR_PROPS = "error.properties";

    private void setRecoveryId(Configuration launcherConf, Path actionDir, String recoveryId) throws LauncherException {
        try {
            FileSystem fs = FileSystem.get(launcherConf);
            String jobId = launcherConf.get("mapred.job.id");
            Path path = new Path(actionDir, recoveryId);
            if (!fs.exists(path)) {
                try {
                    Writer writer = new OutputStreamWriter(fs.create(path));
                    writer.write(jobId);
                    writer.close();
                }
                catch (IOException ex) {
                    failLauncher(0, "IO error", ex);
                }
            }
            else {
                InputStream is = fs.open(path);
                BufferedReader reader = new BufferedReader(new InputStreamReader(is));
                String id = reader.readLine();
                reader.close();
                if (!jobId.equals(id)) {
                    failLauncher(0, MessageFormat.format(
                            "Hadoop job Id mismatch, action file [{0}] declares Id [{1}] current Id [{2}]", path, id,
                            jobId), null);
                }

            }
        }
        catch (IOException ex) {
            failLauncher(0, "IO error", ex);
        }
    }

    /**
     * @param launcherConf
     * @param actionDir
     * @param recoveryId
     * @return
     * @throws HadoopAccessorException
     * @throws IOException
     */
    public static String getRecoveryId(Configuration launcherConf, Path actionDir, String recoveryId)
            throws HadoopAccessorException, IOException {
        String jobId = null;
        Path recoveryFile = new Path(actionDir, recoveryId);
        //FileSystem fs = FileSystem.get(launcherConf);
        FileSystem fs = Services.get().get(HadoopAccessorService.class)
                .createFileSystem(launcherConf.get("user.name"),
                                  launcherConf.get("group.name"), launcherConf);

        if (fs.exists(recoveryFile)) {
            InputStream is = fs.open(recoveryFile);
            BufferedReader reader = new BufferedReader(new InputStreamReader(is));
            jobId = reader.readLine();
            reader.close();
        }
        return jobId;

    }

    public static void setupMainClass(Configuration launcherConf, String javaMainClass) {
        launcherConf.set(CONF_OOZIE_ACTION_MAIN_CLASS, javaMainClass);
    }

    public static void setupMainArguments(Configuration launcherConf, String[] args) {
        launcherConf.setInt(CONF_OOZIE_ACTION_MAIN_ARG_COUNT, args.length);
        for (int i = 0; i < args.length; i++) {
            launcherConf.set(CONF_OOZIE_ACTION_MAIN_ARG_PREFIX + i, args[i]);
        }
    }

    public static void setupMaxOutputData(Configuration launcherConf, int maxOutputData) {
        launcherConf.setInt(CONF_OOZIE_ACTION_MAX_OUTPUT_DATA, maxOutputData);
    }

    /**
     * Set the maximum value of stats data
     *
     * @param launcherConf the oozie launcher configuration
     * @param maxStatsData the maximum allowed size of stats data
     */
    public static void setupMaxExternalStatsSize(Configuration launcherConf, int maxStatsData){
        launcherConf.setInt(CONF_OOZIE_EXTERNAL_STATS_MAX_SIZE, maxStatsData);
    }

    /**
     * @param launcherConf
     * @param jobId
     * @param actionId
     * @param actionDir
     * @param recoveryId
     * @param actionConf
     * @throws IOException
     * @throws HadoopAccessorException
     */
    public static void setupLauncherInfo(JobConf launcherConf, String jobId, String actionId, Path actionDir,
            String recoveryId, Configuration actionConf) throws IOException, HadoopAccessorException {

        launcherConf.setMapperClass(LauncherMapper.class);
        launcherConf.setSpeculativeExecution(false);
        launcherConf.setNumMapTasks(1);
        launcherConf.setNumReduceTasks(0);

        launcherConf.set(OOZIE_JOB_ID, jobId);
        launcherConf.set(OOZIE_ACTION_ID, actionId);
        launcherConf.set(OOZIE_ACTION_DIR_PATH, actionDir.toString());
        launcherConf.set(OOZIE_ACTION_RECOVERY_ID, recoveryId);

        actionConf.set(OOZIE_JOB_ID, jobId);
        actionConf.set(OOZIE_ACTION_ID, actionId);

        FileSystem fs = Services.get().get(HadoopAccessorService.class).createFileSystem(launcherConf.get("user.name"),
                launcherConf.get("group.name"), launcherConf);
        fs.mkdirs(actionDir);

        OutputStream os = fs.create(new Path(actionDir, ACTION_CONF_XML));
        actionConf.writeXml(os);
        os.close();

        Path inputDir = new Path(actionDir, "input");
        fs.mkdirs(inputDir);
        Writer writer = new OutputStreamWriter(fs.create(new Path(inputDir, "dummy.txt")));
        writer.write("dummy");
        writer.close();

        launcherConf.set("mapred.input.dir", inputDir.toString());
        launcherConf.set("mapred.output.dir", new Path(actionDir, "output").toString());
    }

    public static boolean isMainDone(RunningJob runningJob) throws IOException {
        return runningJob.isComplete();
    }

    public static boolean isMainSuccessful(RunningJob runningJob) throws IOException {
        boolean succeeded = runningJob.isSuccessful();
        if (succeeded) {
            Counters counters = runningJob.getCounters();
            if (counters != null) {
                Counters.Group group = counters.getGroup(COUNTER_GROUP);
                if (group != null) {
                    succeeded = group.getCounter(COUNTER_LAUNCHER_ERROR) == 0;
                }
            }
        }
        return succeeded;
    }

    public static boolean hasOutputData(RunningJob runningJob) throws IOException {
        boolean output = false;
        Counters counters = runningJob.getCounters();
        if (counters != null) {
            Counters.Group group = counters.getGroup(COUNTER_GROUP);
            if (group != null) {
                output = group.getCounter(COUNTER_OUTPUT_DATA) == 1;
            }
        }
        return output;
    }

    /**
     * Check whether runningJob has stats data or not
     *
     * @param runningJob the runningJob
     * @return returns whether the running Job has stats data or not
     * @throws IOException
     */
    public static boolean hasStatsData(RunningJob runningJob) throws IOException{
        boolean output = false;
        Counters counters = runningJob.getCounters();
        if (counters != null) {
            Counters.Group group = counters.getGroup(COUNTER_GROUP);
            if (group != null) {
                output = group.getCounter(COUNTER_STATS_DATA) == 1;
            }
        }
        return output;
    }

    /**
     * @param runningJob
     * @return
     * @throws IOException
     */
    public static boolean hasIdSwap(RunningJob runningJob) throws IOException {
        boolean swap = false;
        Counters counters = runningJob.getCounters();
        if (counters != null) {
            Counters.Group group = counters.getGroup(COUNTER_GROUP);
            if (group != null) {
                swap = group.getCounter(COUNTER_DO_ID_SWAP) == 1;
            }
        }
        return swap;
    }

    /**
     * @param runningJob
     * @param user
     * @param group
     * @param actionDir
     * @return
     * @throws IOException
     * @throws HadoopAccessorException
     */
    public static boolean hasIdSwap(RunningJob runningJob, String user, String group, Path actionDir)
            throws IOException, HadoopAccessorException {
        boolean swap = false;

        XLog log = XLog.getLog("org.apache.oozie.action.hadoop.LauncherMapper");

        Counters counters = runningJob.getCounters();
        if (counters != null) {
            Counters.Group counterGroup = counters.getGroup(COUNTER_GROUP);
            if (counterGroup != null) {
                swap = counterGroup.getCounter(COUNTER_DO_ID_SWAP) == 1;
            }
        }
        // additional check for swapped hadoop ID
        // Can't rely on hadoop counters existing
        // we'll check for the newID file in hdfs if the hadoop counters is null
        else {

            Path p = getIdSwapPath(actionDir);
            // log.debug("Checking for newId file in: [{0}]", p);

            FileSystem fs = Services.get().get(HadoopAccessorService.class).createFileSystem(user, group, p.toUri(),
                                                                                             new Configuration());
            if (fs.exists(p)) {
                log.debug("Hadoop Counters is null, but found newID file.");

                swap = true;
            }
            else {
                log.debug("Hadoop Counters is null, and newID file doesn't exist at: [{0}]", p);
            }
        }
        return swap;
    }

    public static Path getOutputDataPath(Path actionDir) {
        return new Path(actionDir, ACTION_OUTPUT_PROPS);
    }

    /**
     * Get the location of stats file
     *
     * @param actionDir the action directory
     * @return the hdfs location of the file
     */
    public static Path getActionStatsDataPath(Path actionDir){
        return new Path(actionDir, ACTION_STATS_PROPS);
    }

    /**
     * Get the location of external Child IDs file
     *
     * @param actionDir the action directory
     * @return the hdfs location of the file
     */
    public static Path getExternalChildIDsDataPath(Path actionDir){
        return new Path(actionDir, ACTION_EXTERNAL_CHILD_IDS_PROPS);
    }

    public static Path getErrorPath(Path actionDir) {
        return new Path(actionDir, ACTION_ERROR_PROPS);
    }

    public static Path getIdSwapPath(Path actionDir) {
        return new Path(actionDir, ACTION_NEW_ID_PROPS);
    }

    private JobConf jobConf;
    private Path actionDir;
    private ScheduledThreadPoolExecutor timer;

    private boolean configFailure = false;

    public LauncherMapper() {
    }

    @Override
    public void configure(JobConf jobConf) {
        System.out.println();
        System.out.println("Oozie Launcher starts");
        System.out.println();
        this.jobConf = jobConf;
        actionDir = new Path(getJobConf().get(OOZIE_ACTION_DIR_PATH));
        String recoveryId = jobConf.get(OOZIE_ACTION_RECOVERY_ID, null);
        try {
            setRecoveryId(jobConf, actionDir, recoveryId);
        }
        catch (LauncherException ex) {
            configFailure = true;
        }
    }

    @Override
    public void map(K1 key, V1 value, OutputCollector<K2, V2> collector, Reporter reporter) throws IOException {
        try {
            if (configFailure) {
                throw new LauncherException();
            }
            else {
                String mainClass = getJobConf().get(CONF_OOZIE_ACTION_MAIN_CLASS);
                String msgPrefix = "Main class [" + mainClass + "], ";
                int errorCode = 0;
                Throwable errorCause = null;
                String errorMessage = null;

                try {
                    new LauncherSecurityManager();
                }
                catch (SecurityException ex) {
                    errorMessage = "Could not set LauncherSecurityManager";
                    errorCause = ex;
                }

                try {
                    setupHeartBeater(reporter);

                    setupMainConfiguration();

                    String[] args = getMainArguments(getJobConf());

                    printContentsOfCurrentDir();

                    System.out.println();
                    System.out.println("Oozie Java/Map-Reduce/Pig action launcher-job configuration");
                    System.out.println("=================================================================");
                    System.out.println("Workflow job id   : " + System.getProperty("oozie.job.id"));
                    System.out.println("Workflow action id: " + System.getProperty("oozie.action.id"));
                    System.out.println();
                    System.out.println("Classpath         :");
                    System.out.println("------------------------");
                    StringTokenizer st = new StringTokenizer(System.getProperty("java.class.path"), ":");
                    while (st.hasMoreTokens()) {
                        System.out.println("  " + st.nextToken());
                    }
                    System.out.println("------------------------");
                    System.out.println();
                    System.out.println("Main class        : " + mainClass);
                    System.out.println();
                    System.out.println("Maximum output    : "
                            + getJobConf().getInt(CONF_OOZIE_ACTION_MAX_OUTPUT_DATA, 2 * 1024));
                    System.out.println();
                    System.out.println("Arguments         :");
                    for (String arg : args) {
                        System.out.println("                    " + arg);
                    }

                    System.out.println();
                    System.out.println("Java System Properties:");
                    System.out.println("------------------------");
                    System.getProperties().store(System.out, "");
                    System.out.flush();
                    System.out.println("------------------------");
                    System.out.println();

                    System.out.println("=================================================================");
                    System.out.println();
                    System.out.println(">>> Invoking Main class now >>>");
                    System.out.println();
                    System.out.flush();

                    try {
                        Class klass = getJobConf().getClass(CONF_OOZIE_ACTION_MAIN_CLASS, Object.class);
                        Method mainMethod = klass.getMethod("main", String[].class);
                        mainMethod.invoke(null, (Object) args);
                    }
                    catch (InvocationTargetException ex) {
                        if (LauncherMainException.class.isInstance(ex.getCause())) {
                            errorMessage = msgPrefix + "exit code [" +((LauncherMainException)ex.getCause()).getErrorCode()
                                + "]";
                            errorCause = null;
                        }
                        else if (SecurityException.class.isInstance(ex.getCause())) {
                            if (LauncherSecurityManager.getExitInvoked()) {
                                System.out.println("Intercepting System.exit(" + LauncherSecurityManager.getExitCode()
                                        + ")");
                                System.err.println("Intercepting System.exit(" + LauncherSecurityManager.getExitCode()
                                        + ")");
                                // if 0 main() method finished successfully
                                // ignoring
                                errorCode = LauncherSecurityManager.getExitCode();
                                if (errorCode != 0) {
                                    errorMessage = msgPrefix + "exit code [" + errorCode + "]";
                                    errorCause = null;
                                }
                            }
                        }
                        else {
                            throw ex;
                        }
                    }
                    finally {
                        System.out.println();
                        System.out.println("<<< Invocation of Main class completed <<<");
                        System.out.println();
                    }
                    if (errorMessage == null) {
                        File outputData = new File(System.getProperty("oozie.action.output.properties"));
                        FileSystem fs = FileSystem.get(getJobConf());
                        if (outputData.exists()) {

                            fs.copyFromLocalFile(new Path(outputData.toString()), new Path(actionDir,
                                                                                           ACTION_OUTPUT_PROPS));
                            reporter.incrCounter(COUNTER_GROUP, COUNTER_OUTPUT_DATA, 1);

                            int maxOutputData = getJobConf().getInt(CONF_OOZIE_ACTION_MAX_OUTPUT_DATA, 2 * 1024);
                            if (outputData.length() > maxOutputData) {
                                String msg = MessageFormat.format("Output data size [{0}] exceeds maximum [{1}]",
                                                                  outputData.length(), maxOutputData);
                                failLauncher(0, msg, null);
                            }
                            System.out.println();
                            System.out.println("Oozie Launcher, capturing output data:");
                            System.out.println("=======================");
                            Properties props = new Properties();
                            props.load(new FileReader(outputData));
                            props.store(System.out, "");
                            System.out.println();
                            System.out.println("=======================");
                            System.out.println();
                        }
                        handleActionStatsData(fs, reporter);
                        handleExternalChildIDs(fs, reporter);
                        File newId = new File(System.getProperty("oozie.action.newId.properties"));
                        if (newId.exists()) {
                            Properties props = new Properties();
                            props.load(new FileReader(newId));
                            if (props.getProperty("id") == null) {
                                throw new IllegalStateException("ID swap file does not have [id] property");
                            }
                            fs = FileSystem.get(getJobConf());
                            fs.copyFromLocalFile(new Path(newId.toString()), new Path(actionDir, ACTION_NEW_ID_PROPS));
                            reporter.incrCounter(COUNTER_GROUP, COUNTER_DO_ID_SWAP, 1);

                            System.out.println("Oozie Launcher, copying new Hadoop job id to file: "
                                    + new Path(actionDir, ACTION_NEW_ID_PROPS).toUri());

                            System.out.println();
                            System.out.println("Oozie Launcher, propagating new Hadoop job id to Oozie");
                            System.out.println("=======================");
                            System.out.println("id: " + props.getProperty("id"));
                            System.out.println("=======================");
                            System.out.println();
                        }
                    }
                }
                catch (NoSuchMethodException ex) {
                    errorMessage = msgPrefix + "main() method not found";
                    errorCause = ex;
                }
                catch (InvocationTargetException ex) {
                    errorMessage = msgPrefix + "main() threw exception";
                    errorCause = ex.getTargetException();
                }
                catch (Throwable ex) {
                    errorMessage = msgPrefix + "exception invoking main()";
                    errorCause = ex;
                }
                finally {
                    destroyHeartBeater();
                    if (errorMessage != null) {
                        failLauncher(errorCode, errorMessage, errorCause);
                    }
                }
            }
        }
        catch (LauncherException ex) {
            reporter.incrCounter(COUNTER_GROUP, COUNTER_LAUNCHER_ERROR, 1);
            System.out.println();
            System.out.println("Oozie Launcher failed, finishing Hadoop job gracefully");
            System.out.println();
        }
    }

    @Override
    public void close() throws IOException {
        System.out.println();
        System.out.println("Oozie Launcher ends");
        System.out.println();
    }

    protected JobConf getJobConf() {
        return jobConf;
    }

    private void handleActionStatsData(FileSystem fs, Reporter reporter) throws IOException, LauncherException{
        File actionStatsData = new File(System.getProperty(EXTERNAL_ACTION_STATS));
        // If stats are stored by the action, then stats file should exist
        if (actionStatsData.exists()) {
            int statsMaxOutputData = getJobConf().getInt(CONF_OOZIE_EXTERNAL_STATS_MAX_SIZE,
                    Integer.MAX_VALUE);
            reporter.incrCounter(COUNTER_GROUP, COUNTER_STATS_DATA, 1);
            // fail the launcher if size of stats is greater than the maximum allowed size
            if (actionStatsData.length() > statsMaxOutputData) {
                String msg = MessageFormat.format("Output stats size [{0}] exceeds maximum [{1}]",
                        actionStatsData.length(), statsMaxOutputData);
                failLauncher(0, msg, null);
            }
            // copy the stats file to hdfs path which can be accessed by Oozie server
            fs.copyFromLocalFile(new Path(actionStatsData.toString()), new Path(actionDir,
                    ACTION_STATS_PROPS));
        }
    }

    private void handleExternalChildIDs(FileSystem fs, Reporter reporter) throws IOException {
        File externalChildIDs = new File(System.getProperty(EXTERNAL_CHILD_IDS));
        // if external ChildIDs are stored by the action, then the file should exist
        if (externalChildIDs.exists()) {
            // copy the externalChildIDs file to hdfs path which can be accessed by Oozie server
            fs.copyFromLocalFile(new Path(externalChildIDs.toString()), new Path(actionDir,
                    ACTION_EXTERNAL_CHILD_IDS_PROPS));
        }
    }

    private void setupMainConfiguration() throws IOException {
        FileSystem fs = FileSystem.get(getJobConf());
        fs.copyToLocalFile(new Path(getJobConf().get(OOZIE_ACTION_DIR_PATH), ACTION_CONF_XML), new Path(new File(
                ACTION_CONF_XML).getAbsolutePath()));

        System.setProperty("oozie.launcher.job.id", getJobConf().get("mapred.job.id"));
        System.setProperty("oozie.job.id", getJobConf().get(OOZIE_JOB_ID));
        System.setProperty("oozie.action.id", getJobConf().get(OOZIE_ACTION_ID));
        System.setProperty("oozie.action.conf.xml", new File(ACTION_CONF_XML).getAbsolutePath());
        System.setProperty("oozie.action.output.properties", new File(ACTION_OUTPUT_PROPS).getAbsolutePath());
        System.setProperty(EXTERNAL_ACTION_STATS, new File(ACTION_STATS_PROPS).getAbsolutePath());
        System.setProperty(EXTERNAL_CHILD_IDS, new File(ACTION_EXTERNAL_CHILD_IDS_PROPS).getAbsolutePath());
        System.setProperty("oozie.action.newId.properties", new File(ACTION_NEW_ID_PROPS).getAbsolutePath());
    }

    public static String[] getMainArguments(Configuration conf) {
        String[] args = new String[conf.getInt(CONF_OOZIE_ACTION_MAIN_ARG_COUNT, 0)];
        for (int i = 0; i < args.length; i++) {
            args[i] = conf.get(CONF_OOZIE_ACTION_MAIN_ARG_PREFIX + i);
        }
        return args;
    }

    private void setupHeartBeater(Reporter reporter) {
        timer = new ScheduledThreadPoolExecutor(1);
        timer.scheduleAtFixedRate(new LauncherMapper(reporter), 0, 30, TimeUnit.SECONDS);
    }

    private void destroyHeartBeater() {
        timer.shutdownNow();
    }

    private Reporter reporter;

    private LauncherMapper(Reporter reporter) {
        this.reporter = reporter;
    }

    @Override
    public void run() {
        System.out.println("Heart beat");
        reporter.progress();
    }

    private void failLauncher(int errorCode, String reason, Throwable ex) throws LauncherException {
        try {
            if (ex != null) {
                reason += ", " + ex.getMessage();
            }
            Properties errorProps = new Properties();
            errorProps.setProperty("error.code", Integer.toString(errorCode));
            errorProps.setProperty("error.reason", reason);
            if (ex != null) {
                if (ex.getMessage() != null) {
                    errorProps.setProperty("exception.message", ex.getMessage());
                }
                StringWriter sw = new StringWriter();
                PrintWriter pw = new PrintWriter(sw);
                ex.printStackTrace(pw);
                pw.close();
                errorProps.setProperty("exception.stacktrace", sw.toString());
            }
            FileSystem fs = FileSystem.get(getJobConf());
            OutputStream os = fs.create(new Path(actionDir, ACTION_ERROR_PROPS));
            errorProps.store(os, "");
            os.close();

            System.out.print("Failing Oozie Launcher, " + reason + "\n");
            System.err.print("Failing Oozie Launcher, " + reason + "\n");
            if (ex != null) {
                ex.printStackTrace(System.out);
                ex.printStackTrace(System.err);
            }
            throw new LauncherException();
        }
        catch (IOException rex) {
            throw new RuntimeException("Error while failing launcher, " + rex.getMessage(), rex);
        }
    }

    /**
     * Print files and directories in current directory. Will list files in the sub-directory (only 1 level deep)
     */
    protected void printContentsOfCurrentDir() {
        File folder = new File(".");
        System.out.println();
        System.out.println("Files in current dir:" + folder.getAbsolutePath());
        System.out.println("======================");

        File[] listOfFiles = folder.listFiles();
        for (File fileName : listOfFiles) {
            if (fileName.isFile()) {
                System.out.println("File: " + fileName.getName());
            }
            else if (fileName.isDirectory()) {
                System.out.println("Dir: " + fileName.getName());
                File subDir = new File(fileName.getName());
                File[] moreFiles = subDir.listFiles();
                for (File subFileName : moreFiles) {
                    if (subFileName.isFile()) {
                        System.out.println("  File: " + subFileName.getName());
                    }
                    else if (subFileName.isDirectory()) {
                        System.out.println("  Dir: " + subFileName.getName());
                    }
                }
            }
        }
    }

}

class LauncherSecurityManager extends SecurityManager {
    private static boolean exitInvoked;
    private static int exitCode;
    private SecurityManager securityManager;

    public LauncherSecurityManager() {
        reset();
        securityManager = System.getSecurityManager();
        System.setSecurityManager(this);
    }

    @Override
    public void checkPermission(Permission perm, Object context) {
        if (securityManager != null) {
            // check everything with the original SecurityManager
            securityManager.checkPermission(perm, context);
        }
    }

    @Override
    public void checkPermission(Permission perm) {
        if (securityManager != null) {
            // check everything with the original SecurityManager
            securityManager.checkPermission(perm);
        }
    }

    @Override
    public void checkExit(int status) throws SecurityException {
        exitInvoked = true;
        exitCode = status;
        throw new SecurityException("Intercepted System.exit(" + status + ")");
    }

    public static boolean getExitInvoked() {
        return exitInvoked;
    }

    public static int getExitCode() {
        return exitCode;
    }

    public static void reset() {
        exitInvoked = false;
        exitCode = 0;
    }
}

class LauncherException extends Exception {
}
