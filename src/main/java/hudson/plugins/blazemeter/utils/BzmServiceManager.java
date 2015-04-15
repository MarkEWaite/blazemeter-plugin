package hudson.plugins.blazemeter.utils;

import hudson.FilePath;
import hudson.model.AbstractBuild;
import hudson.model.Result;
import hudson.plugins.blazemeter.*;
import hudson.plugins.blazemeter.api.APIFactory;
import hudson.plugins.blazemeter.api.BlazemeterApi;
import hudson.plugins.blazemeter.entities.TestInfo;
import hudson.plugins.blazemeter.entities.TestStatus;
import hudson.plugins.blazemeter.testresult.TestResult;
import hudson.util.FormValidation;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.eclipse.jetty.util.log.AbstractLogger;
import org.eclipse.jetty.util.log.StdErrLog;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.*;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Created by dzmitrykashlach on 18/11/14.
 */
public class BzmServiceManager {
    private final static int BUFFER_SIZE = 2048;
    private final static String ZIP_EXTENSION = ".zip";
    private BzmServiceManager() {
    }

    public static String autoDetectApiVersion(String apiKey) {
        BlazemeterApi api = null;
        APIFactory apiFactory = APIFactory.getApiFactory();
        String detectedApiVersion = null;
            api = apiFactory.getApiFactory().getAPI(apiKey,ApiVersion.v3);
            boolean isV3 = false;
            try {
                isV3 = api.getUser().getJSONObject("features").getBoolean("v3");
                if (isV3) {
                    detectedApiVersion="v3";
                } else {
                    detectedApiVersion="v2";
                }
            } catch (JSONException je) {
                detectedApiVersion="v3";
            } catch (NullPointerException npe) {
                detectedApiVersion="v3";
            }finally {
                return detectedApiVersion;
            }
    }

    public static JSONObject updateLocation(BlazemeterApi api,String testId,JSONObject configNode,StdErrLog bzmBuildLog){
        JSONObject updateResult=null;
        try {
            if (configNode != null) {
                updateResult=api.postJsonConfig(testId, configNode);
            }
        }catch (Exception e) {
            bzmBuildLog.warn("Received JSONException while saving testDuration: ", e);
        }
        return updateResult;
    }

    public static JSONObject updateTestConfiguration(BlazemeterApi api,
                                                     String testId,
                                                     String testDuration,
                                                     String location,
                                                     JSONObject configNode,
                                                     StdErrLog bzmBuildLog) {
        JSONObject updateResult=null;
        try {
            JSONObject result = null;
            if (configNode != null) {
                result=configNode;
                updateResult = api.postJsonConfig(testId, result);
            } else {
                if (testDuration != null && !testDuration.isEmpty()) {
                    updateResult = updateTestDuration(api, testId, testDuration, bzmBuildLog);
                }
            }
            if (location != null && !location.isEmpty() && !location.equals(Constants.USE_TEST_LOCATION)) {
                updateResult = updateLocation(api, testId, location, bzmBuildLog);
            }
        }catch (Exception e) {
            bzmBuildLog.warn("Received JSONException while updating test: ", e);
        }
        return updateResult;
    }


    public static JSONObject updateTestDuration(BlazemeterApi api,
                                          String testId,
                                          String testDuration,
                                          StdErrLog bzmBuildLog){
        JSONObject result;
        JSONObject updateResult=null;
        try {
            JSONObject jo = api.getTestConfig(testId);
            result = jo.getJSONObject(JsonConstants.RESULT);
            JSONObject configuration = result.getJSONObject(JsonConstants.CONFIGURATION);
            JSONObject plugins = configuration.getJSONObject(JsonConstants.PLUGINS);
            String type = configuration.getString(JsonConstants.TYPE);
            JSONObject options = plugins.getJSONObject(type);
            JSONObject override = options.getJSONObject(JsonConstants.OVERRIDE);
            override.put(JsonConstants.DURATION, testDuration);
            override.put("threads", JSONObject.NULL);
            configuration.put("serversCount", JSONObject.NULL);
            updateResult = api.putTestInfo(testId, result);
        } catch (JSONException je) {
            bzmBuildLog.warn("Received JSONException while updating test duration: ", je);
        } catch (Exception e) {
            bzmBuildLog.warn("Received JSONException while updating test duration: ", e);
        }
        return updateResult;
    }



    public static JSONObject updateLocation(BlazemeterApi api,
                                                String testId,
                                                String location,
                                                StdErrLog bzmBuildLog){
        JSONObject result;
        JSONObject updateResult=null;
        try {
            JSONObject jo = api.getTestConfig(testId);
            result = jo.getJSONObject(JsonConstants.RESULT);
            JSONObject configuration = result.getJSONObject(JsonConstants.CONFIGURATION);
            configuration.put(JsonConstants.LOCATION, location);
            updateResult = api.putTestInfo(testId, result);
        } catch (JSONException je) {
            bzmBuildLog.warn("Received JSONException while updating test duration: ", je);
        } catch (Exception e) {
            bzmBuildLog.warn("Received JSONException while updating test duration: ", e);
        }
        return updateResult;
    }

    public static void uploadDataFolderFiles(String dataFolder, String mainJMX, String testId,
                                             BlazemeterApi bmAPI, StdErrLog bzmBuildLog) {

        if (dataFolder == null || dataFolder.isEmpty())
            return;

        File folder = new File(dataFolder);
        if (!folder.exists() || !folder.isDirectory()) {
            bzmBuildLog.info("dataFolder " + dataFolder + " could not be found on local file system, please check that the folder exists.");
            return;
        }

        File[] listOfFiles = folder.listFiles();

        if (listOfFiles != null) {
            for (File file : listOfFiles) {
                String fileName;
                if (file.isFile()) {
                    fileName = file.getName();

                    if (fileName.endsWith(mainJMX))
                        bmAPI.uploadJmx(testId, file);
                    else
                        uploadFile(testId, bmAPI, file, bzmBuildLog);
                }
            }
        }
    }

    public static void waitForFinish(BlazemeterApi api, String apiVersion, String testId, AbstractLogger bzmBuildLog,
                                     String session) throws InterruptedException {
        Date start = null;
        long lastPrint = 0;
        while (true) {
            Thread.sleep(15000);
            TestInfo info = api.getTestInfo(apiVersion.equals("v2") ? testId : session);

            if (!info.getStatus().equals(TestStatus.Running)) {
                bzmBuildLog.info("TestStatus for session " + (apiVersion.equals("v2") ? testId : session)
                        + info.getStatus());
                bzmBuildLog.info("BlazeMeter TestStatus for session" +
                        (apiVersion.equals("v2") ? testId : session)
                        + " is not 'Running': finishing build.... ");
                bzmBuildLog.info("Timestamp: " + Calendar.getInstance().getTime());
                break;
            }

            if (start == null)
                start = Calendar.getInstance().getTime();
            long now = Calendar.getInstance().getTime().getTime();
            long diffInSec = (now - start.getTime()) / 1000;
            if (now - lastPrint > 10000) { //print every 10 sec.
                bzmBuildLog.info("BlazeMeter test# " + testId + ", session # " + session + " running from " + start + " - for " + diffInSec + " seconds");
                lastPrint = now;
            }

            if (Thread.interrupted()) {
                bzmBuildLog.info("Test was interrupted: throwing Interrupted Exception");
                throw new InterruptedException();
            }
        }
    }

    private static String createTest(BlazemeterApi api, JSONObject configNode,
                                     String testId,StdErrLog jenBuildLog) throws JSONException {
        try{

        if(testId.equals(Constants.CREATE_BZM_TEST_NOTE)){
            JSONObject jo = api.createTest(configNode);
            if(jo.has(JsonConstants.ERROR)&&!jo.getString(JsonConstants.ERROR).equals("null")){
                jenBuildLog.warn("Failed to create test: "+jo.getString(JsonConstants.ERROR));
                testId="";
            }else{
                testId = jo.getJSONObject(JsonConstants.RESULT).getString(JsonConstants.ID);
            }
        }
        }catch (Exception e){
            jenBuildLog.warn("Unable to create test: check user-key and server-url");
        }

        return testId;
    }

    public static String getReportUrl(BlazemeterApi api, String sessionId,
                                      StdErrLog jenBuildLog, StdErrLog bzmBuildLog) {
        JSONObject jo=null;
        String publicToken="";
        String reportUrl=null;
        try {
            jo = api.generatePublicToken(sessionId);
            if(jo.get(JsonConstants.ERROR).equals(JSONObject.NULL)){
                JSONObject result=jo.getJSONObject(JsonConstants.RESULT);
                publicToken=result.getString("publicToken");
                reportUrl=APIFactory.getApiFactory().getBlazeMeterUrl()+"/app/?public-token="+publicToken+"#reports/"+sessionId+"/summary";
            }else{
                jenBuildLog.warn("Problems with generating public-token for report URL: "+jo.get(JsonConstants.ERROR).toString());
                bzmBuildLog.warn("Problems with generating public-token for report URL: "+jo.get(JsonConstants.ERROR).toString());
                reportUrl=APIFactory.getApiFactory().getBlazeMeterUrl()+"/app/#reports/"+sessionId+"/summary";
            }
        } catch (Exception e){
          jenBuildLog.warn("Problems with generating public-token for report URL");
          bzmBuildLog.warn("Problems with generating public-token for report URL",e);
        }finally {
                return reportUrl;
        }
    }

    public static String prepareTestRun(PerformanceBuilder builder) {
        BlazemeterApi api = builder.getApi();
        FilePath jsonConfigPath = null;
        StdErrLog bzmBuildLog = PerformanceBuilder.getBzmBuildLog();
        StdErrLog jenBuildLog = builder.getJenBuildLog();
        String testId = builder.getTestId();
        try {
            JSONObject configNode=null;
            if(!StringUtils.isBlank(builder.getJsonConfig())){
                jsonConfigPath=new FilePath(builder.getBuild().getWorkspace(), builder.getJsonConfig());
                configNode = new JSONObject(jsonConfigPath.readToString());
            }

            if (testId.contains("create")) {
                if (configNode != null) {
                    testId = createTest(api, configNode, testId, jenBuildLog);
                    builder.setTestId(testId);
                } else {
                    testId="";
                    return testId;
                }
            }


                JSONObject updateResult= updateTestConfiguration(api, testId, builder.getTestDuration(),
                        builder.getLocation(),
                        configNode, bzmBuildLog);
                if(updateResult!=null&&updateResult.has(JsonConstants.ERROR)&&!updateResult.get(JsonConstants.ERROR).equals(null)){
                    jenBuildLog.warn("Failed to update test with JSON configuration");
                    jenBuildLog.warn("Error:"+updateResult.getString(JsonConstants.ERROR));
                    testId="";
                }else{
                    jenBuildLog.info("Test " + testId + " was started on server");
                    }
        } catch (IOException e) {
            jenBuildLog.info("Failed to read JSON configuration from file " + builder.getJsonConfig() + ": " + e.getMessage());
            bzmBuildLog.info("Failed to read JSON configuration from file " + builder.getJsonConfig() + ": " + e.getMessage());
            testId="";
        } catch (JSONException je) {
            jenBuildLog.info("Failed to read JSON configuration from file " + builder.getJsonConfig() + ": " + je.getMessage());
            bzmBuildLog.info("Failed to read JSON configuration from file " + builder.getJsonConfig() + ": " + je.getMessage());
            testId="";
        } catch (Exception e){
            jenBuildLog.info("Unknown error while preparing test for execution: " +e.getMessage());
            bzmBuildLog.info("Unknown error while preparing test for execution: " + e.getMessage());
            testId="";
        }

        finally {
/*          TODO
            These calls are not implemented for APIv3
            Should be fixed in v.2.1

            uploadDataFolderFiles(builder.getDataFolder(),builder.getMainJMX(),testId, api,bzmBuildLog);
*/
            return testId;
        }
    }

    public static void uploadFile(String testId, BlazemeterApi bmAPI, File file, StdErrLog bzmBuildLog) {
        String fileName = file.getName();
        org.json.JSONObject json = bmAPI.uploadBinaryFile(testId, file);
        try {
            if (!json.get(JsonConstants.RESPONSE_CODE).equals(200)) {
                bzmBuildLog.info("Could not upload file " + fileName + " " + json.get(JsonConstants.ERROR).toString());
            }
        } catch (JSONException e) {
            bzmBuildLog.info("Could not upload file " + fileName + " " + e.getMessage());
        }
    }

    /**
     * TODO
     * 1. Split this method into two parts depending on
     * API version and place code to appropriate BlazeMeterAPIImpl
     * 2. Remove this method, it's legacy
     *
     * @param json
     * @param build
     * @return
     * @throws JSONException
     */
    public static String getSessionId(JSONObject json, AbstractBuild<?, ?> build,
                                ApiVersion apiVersion,BlazemeterApi api,StdErrLog bzmBuildLog,StdErrLog jenBuildLog) throws JSONException{
        String session="";
        try {
            if (apiVersion.equals(ApiVersion.v2.name()) && !json.get(JsonConstants.RESPONSE_CODE).equals(200)) {
                if (json.get(JsonConstants.RESPONSE_CODE).equals(500) && json.get(JsonConstants.ERROR).toString()
                        .startsWith("Test already running")) {
                    bzmBuildLog.warn("Test already running, please stop it first");
                    build.setResult(Result.FAILURE);
                    return session;
                }

            }
            // get sessionId add to interface
            if (apiVersion.equals(ApiVersion.v2)) {
                session = json.get("session_id").toString();
            } else {
                JSONObject startJO = (JSONObject) json.get(JsonConstants.RESULT);
                session = ((JSONArray) startJO.get("sessionsId")).get(0).toString();
            }
            String reportUrl= getReportUrl(api, session, jenBuildLog, bzmBuildLog);
            jenBuildLog.info("Blazemeter test report will be available at " + reportUrl);
            jenBuildLog.info("Blazemeter test log will be available at " + build.getLogFile().getParent() + "/" + Constants.BZM_JEN_LOG);

            PerformanceBuildAction a = new PerformanceBuildAction(build);
            a.setReportUrl(reportUrl);
            build.addAction(a);


        }catch (Exception e) {
            jenBuildLog.info("Failed to get session_id: "+e.getMessage());
            bzmBuildLog.info("Failed to get session_id. ",e);
        }
        return session;
    }


    public static void saveReport(String filename,
                                  String report,
                                  FilePath filePath,
                                  StdErrLog jenBuildLog) {
        File reportFile = new File(filePath.getParent()
                + "/" + filePath.getName() + "/" + filename);
        try {
            if (!reportFile.exists()) {
                reportFile.createNewFile();
            }
            BufferedWriter out = new BufferedWriter(new FileWriter(reportFile));
            out.write(report);
            out.close();

        } catch (FileNotFoundException fnfe) {
            jenBuildLog.info("ERROR: Failed to save XML report to workspace " + fnfe.getMessage());
        } catch (IOException e) {
            jenBuildLog.info("ERROR: Failed to save XML report to workspace " + e.getMessage());
        }
    }

    public static Result validateServerTresholds(BlazemeterApi api,String session,StdErrLog jenBuildLog){
        Result result;
        JSONObject jo;
        boolean success;
        try {
            jo=api.getTresholds(session);
            jenBuildLog.info("Treshold object = " + jo.toString());
            success=jo.getJSONObject(JsonConstants.RESULT).getJSONObject(JsonConstants.DATA).getBoolean("success");
        } catch (JSONException je) {
            jenBuildLog.warn("No tresholds on server: setting SUCCESS for build ");
            success=true;
        } catch (Exception e) {
            jenBuildLog.warn("No tresholds on server: setting SUCCESS for build ");
            success=true;
        }
        jenBuildLog.info("Validating server tresholds: " + (success ? "PASSED" : "FAILED") + "\n");

        result = success?Result.SUCCESS:Result.FAILURE;
        if(result.equals(Result.FAILURE)){
            return result;
        }
        return result;
    }

    public static String selectUserKeyOnId(BlazeMeterPerformanceBuilderDescriptor descriptor,
                                           String id){
        String userKey=null;
        List<BlazemeterCredential> credentialList=descriptor.getCredentials("Global");
        if(credentialList.size()==1){
            userKey=credentialList.get(0).getApiKey();
        }else{
            for(BlazemeterCredential c:credentialList){
                if(c.getId().equals(id)){
                    userKey=c.getApiKey();
                    break;
                }
            }
        }
        return userKey;
    }

    public static String selectUserKeyId(BlazeMeterPerformanceBuilderDescriptor descriptor,
                                           String userKey){
        String userKeyId=null;
        List<BlazemeterCredential> credentialList=descriptor.getCredentials("Global");
        if(credentialList.size()==1){
            userKeyId=credentialList.get(0).getId();
        }else{
            for(BlazemeterCredential c:credentialList){
                if(c.getApiKey().equals(userKey)){
                    userKeyId=c.getId();
                    break;
                }
            }
        }
        return userKeyId;
    }

    public static void getJTL(BlazemeterApi api,String session,FilePath filePath,
                              StdErrLog jenBuildLog,
                              StdErrLog bzmBuildLog){
       JSONObject jo=api.retrieveJTLZIP(session);
       String dataUrl=null;
        URL url=null;
        try {
            JSONArray data=jo.getJSONObject(JsonConstants.RESULT).getJSONArray(JsonConstants.DATA);
            for(int i=0;i<data.length();i++){
                String title=data.getJSONObject(i).getString("title");
                if(title.equals("Zip")){
                  dataUrl=data.getJSONObject(i).getString(JsonConstants.DATA_URL);
                    break;
                }
            }
            File jtlZip=new File(filePath.getParent()
                    + "/" + filePath.getName() + "/" + Constants.BM_ARTEFACTS);

            if(!dataUrl.contains("amazonaws")){
                url=new URL(dataUrl+"?api_key="+api.getApiKey());
            }else{
                url=new URL(dataUrl);
            }

            FileUtils.copyURLToFile(url, jtlZip);
            jenBuildLog.info("Downloading JTLZIP from " + url);
            jenBuildLog.info("Saving ZIP to " + jtlZip.getCanonicalPath());
            unzip(jtlZip.getAbsolutePath(), jtlZip.getParent(), jenBuildLog);
            FilePath sample_jtl=new FilePath(filePath,"sample.jtl");
            FilePath bm_kpis_jtl=new FilePath(filePath,Constants.BM_KPIS);
            if(sample_jtl.exists()){
                sample_jtl.renameTo(bm_kpis_jtl);
            }
        } catch (JSONException e) {
            bzmBuildLog.warn("Unable to get  JTLZIP from "+url, e);
            jenBuildLog.warn("Unable to get  JTLZIP from "+url+" "+e.getMessage());
        } catch (MalformedURLException e) {
            bzmBuildLog.warn("Unable to get  JTLZIP from "+url, e);
            jenBuildLog.warn("Unable to get  JTLZIP from "+url+" "+e.getMessage());
        } catch (IOException e) {
            bzmBuildLog.warn("Unable to get JTLZIP from "+url, e);
            jenBuildLog.warn("Unable to get JTLZIP from "+url+" "+e.getMessage());
        } catch (InterruptedException e) {
            bzmBuildLog.warn("Unable to get JTLZIP from "+url, e);
            jenBuildLog.warn("Unable to get JTLZIP from "+url+" "+e.getMessage());
        }
    }

    public static void unzip(String srcZipFileName,
                             String destDirectoryName, StdErrLog jenBuildLog) {
        try {
            BufferedInputStream bufIS = null;
            // create the destination directory structure (if needed)
            File destDirectory = new File(destDirectoryName);
            destDirectory.mkdirs();

            // open archive for reading
            File file = new File(srcZipFileName);
            ZipFile zipFile = new ZipFile(file, ZipFile.OPEN_READ);

            //for every zip archive entry do
            Enumeration<? extends ZipEntry> zipFileEntries = zipFile.entries();
            while (zipFileEntries.hasMoreElements()) {
                ZipEntry entry = (ZipEntry) zipFileEntries.nextElement();
                if(entry.getName().substring((entry.getName().length()-4)).equals(".jtl")&!entry.isDirectory()){
                    jenBuildLog.info("\tExtracting jtl report: " + entry);

                    //create destination file
                    File destFile = new File(destDirectory, entry.getName());

                    //create parent directories if needed
                    File parentDestFile = destFile.getParentFile();
                    parentDestFile.mkdirs();

                        bufIS = new BufferedInputStream(
                                zipFile.getInputStream(entry));
                        int currentByte;

                        // buffer for writing file
                        byte data[] = new byte[BUFFER_SIZE];

                        // write the current file to disk
                        FileOutputStream fOS = new FileOutputStream(destFile);
                        BufferedOutputStream bufOS = new BufferedOutputStream(fOS, BUFFER_SIZE);

                        while ((currentByte = bufIS.read(data, 0, BUFFER_SIZE)) != -1) {
                            bufOS.write(data, 0, currentByte);
                        }

                        // close BufferedOutputStream
                        bufOS.flush();
                        bufOS.close();

                }

            }
            bufIS.close();
        } catch (Exception e) {
            jenBuildLog.warn("Failed to unzip report: check that it is downloaded");
        }
    }

    public static Result postProcess(PerformanceBuilder builder,String session) throws InterruptedException {
        Thread.sleep(10000); // Wait for the report to generate.
        //get tresholds from server and check if test is success
        BlazemeterApi api=builder.getApi();
        StdErrLog jenBuildLog=builder.getJenBuildLog();
        String junitReport="";
        Result result = Result.SUCCESS;
        ApiVersion apiVersion=ApiVersion.valueOf(builder.getApiVersion());

        if(apiVersion.equals(ApiVersion.v3)){
            try{
                junitReport = api.retrieveJUNITXML(session);
            }catch (Exception e){
                jenBuildLog.warn("Problems with receiving JUNIT report from server: "+e.getMessage());
            }
            jenBuildLog.info("Received Junit report from server.... Saving it to the disc...");
            BzmServiceManager.saveReport(Constants.BM_TRESHOLDS, junitReport, builder.getBuild().getWorkspace(), jenBuildLog);
            Thread.sleep(30000);
            BzmServiceManager.getJTL(api, session, builder.getBuild().getWorkspace(), jenBuildLog, jenBuildLog);
            if(builder.isUseServerTresholds()){
                jenBuildLog.info("UseServerTresholds flag is set to TRUE, Server tresholds will be validated.");
                result= BzmServiceManager.validateServerTresholds(api,session,jenBuildLog);
            }else{
                jenBuildLog.info("UseServerTresholds flag is set to FALSE, Server tresholds will not be validated.");
            }
        }

        //get testGetArchive information
        JSONObject testReport=null;
        try{
            testReport = api.testReport(session);
        }catch (Exception e){
            jenBuildLog.info("Failed to get test report from server.");
        }


        if (testReport == null || testReport.equals("null")) {
            jenBuildLog.warn("Requesting aggregate is not available. " +
                    "Build won't be validated against local tresholds");
            return result;
        }
        TestResult testResult = null;
        Result localTresholdsResult=null;
        try {
            testResult = new TestResult(testReport);
            jenBuildLog.info(testResult.toString());
            if (apiVersion.equals(ApiVersion.v3)
                    &&(!builder.isUseServerTresholds()|(builder.isUseServerTresholds()&result.equals(Result.SUCCESS)))) {
                jenBuildLog.info("UseServerTresholds flag was set to FALSE or server tresholds validation was SUCCESS.");
                jenBuildLog.info("Validating local tresholds...\n");
                localTresholdsResult = validateLocalTresholds(testResult, builder, jenBuildLog);
            }
            if(apiVersion.equals(ApiVersion.v2)){
                localTresholdsResult = validateLocalTresholds(testResult, builder, jenBuildLog);
            }
        } catch (IOException ioe) {
            jenBuildLog.info("Failed to get test result. Try to check server for it");
            jenBuildLog.info("ERROR: Failed to generate TestResult: " + ioe);
        } catch (JSONException je) {
            jenBuildLog.info("Failed to get test result. Try to check server for it");
            jenBuildLog.info("ERROR: Failed to generate TestResult: " + je);
        }finally{
            return localTresholdsResult!=null?localTresholdsResult:result;
        }

    }


    public static boolean stopTestSession(BlazemeterApi api, String testId, String sessionId, StdErrLog jenBuildLog) {
        boolean terminate=false;
        try {

            int statusCode = api.getTestSessionStatusCode(sessionId);
            if (statusCode < 100) {
                api.terminateTest(testId);
                terminate=true;
            }
            if (statusCode >= 100|statusCode ==-1) {
                api.stopTest(testId);
                terminate=false;
            }
        } catch (Exception e) {
            jenBuildLog.warn("Error while trying to stop test with testId=" + testId + ", " + e.getMessage());
        }finally {
            return terminate;
        }
    }

    public static Result validateLocalTresholds(TestResult testResult,
                                                PerformanceBuilder builder,
                                                StdErrLog jenBuildLog) {
        Result result=null;
        try {
            int responseTimeUnstable = Integer.valueOf(builder.getResponseTimeUnstableThreshold().isEmpty()
                    ? Constants.MINUS_ONE : builder.getResponseTimeUnstableThreshold());
            int responseTimeFailed = Integer.valueOf(builder.getResponseTimeFailedThreshold().isEmpty()
                    ? Constants.MINUS_ONE : builder.getResponseTimeFailedThreshold());
            int errorUnstable = Integer.valueOf(builder.getErrorUnstableThreshold().isEmpty()
                    ? Constants.MINUS_ONE : builder.getErrorUnstableThreshold());
            int errorFailed = Integer.valueOf(builder.getErrorFailedThreshold().isEmpty()
                    ? Constants.MINUS_ONE : builder.getErrorFailedThreshold());

            if (errorUnstable < 0) {
                jenBuildLog.info("ErrorUnstable percentage validation will be skipped: value was not set in configuration");
            }else{
                jenBuildLog.info("ErrorUnstable percentage value="+errorUnstable+". It will be compared with errorPercentage="+testResult.getErrorPercentage());
            }

            if (errorFailed < 0) {
                jenBuildLog.info("ErrorFailed percentage validation will be skipped: value was not set in configuration");
            }else{
                jenBuildLog.info("ErrorFailed percentage value="+errorFailed+". It will be compared with errorPercentage="+testResult.getErrorPercentage());
            }

            if (responseTimeUnstable < 0) {
                jenBuildLog.info("ResponseTimeUnstable validation will be skipped: value was not set in configuration");
            }else{
                jenBuildLog.info("ResponseTimeUnstable value="+responseTimeUnstable+". It will be compared with averageResponseTime="+testResult.getAverage());
            }


            if (responseTimeFailed < 0) {
                jenBuildLog.info("ResponseTimeFailed validation will be skipped: value was not set in configuration");
            }else{
                jenBuildLog.info("ResponseTimeFailed value="+responseTimeFailed+". It will be compared with averageResponseTime="+testResult.getAverage());
            }

            if (responseTimeUnstable >= 0 & testResult.getAverage() > responseTimeUnstable) {
                jenBuildLog.info("Validating responseTimeUnstable...\n");
                jenBuildLog.info("Actual average_response_time="+testResult.getAverage()+" is higher than RESPONSE_TIME_UNSTABLE_treshold="+responseTimeUnstable+"\n");
                jenBuildLog.info("Marking build as unstable");
                result = Result.UNSTABLE;
            }

            if (errorUnstable >= 0 & testResult.getErrorPercentage() > errorUnstable) {
                jenBuildLog.info("Validating errorPercentageUnstable...\n");
                jenBuildLog.info("Actual error_percentage="+testResult.getErrorPercentage()+" is higher than ERROR_PERCENTAGE_UNSTABLE_treshold="+errorUnstable+"\n");
                jenBuildLog.info("Marking build as unstable");
                result = Result.UNSTABLE;
            }

            if (responseTimeFailed >= 0 & testResult.getAverage() >= responseTimeFailed) {
                jenBuildLog.info("Validating responseTimeFailed...\n");
                jenBuildLog.info("Actual average_response_time="+testResult.getAverage()+" is higher than RESPONSE_TIME_FAILED treshold="+responseTimeFailed+"\n");
                jenBuildLog.info("Marking build as failed");
                result = Result.FAILURE;
                return result;
            }

            if (errorFailed >= 0 & testResult.getErrorPercentage() >= errorFailed) {
                jenBuildLog.info("Validating errorPercentageFailed...\n");
                jenBuildLog.info("Actual error_percentage="+testResult.getErrorPercentage()+" is higher than ERROR_PERCENTAGE_FAILED treshold="+errorFailed+"\n");
                jenBuildLog.info("Marking build as failed");
                result = Result.FAILURE;
                return result;
            }

        } catch (Exception e) {
            jenBuildLog.info("Error occured while validating local tresholds. Check that test was finished correctly or turn to customer support");
        } finally {
            return result;
        }
}

    public static String getVersion() {
        Properties props = new Properties();
        try {
            props.load(BzmServiceManager.class.getResourceAsStream("version.properties"));
        } catch (IOException ex) {
            props.setProperty(Constants.VERSION, "N/A");
        }
        return props.getProperty(Constants.VERSION);
    }

    public static FormValidation validateUserKey(String userKey){
        BlazemeterApi bzm = APIFactory.getApiFactory().getAPI(userKey, ApiVersion.v3);
        try{
        net.sf.json.JSONObject user= net.sf.json.JSONObject.fromObject(bzm.getUser().toString());
        if (user.has("error")) {
            return FormValidation.errorWithMarkup("Invalid user key. Error - "+user.get("error").toString());
        } else {
            return FormValidation.ok("User Key Valid. Email - "+user.getString("mail"));
        }
        }catch (Exception e){
            return FormValidation.errorWithMarkup("Invalid user key. Unknown error");
        }
    }

    public static String getUserEmail(String userKey){
        BlazemeterApi bzm = APIFactory.getApiFactory().getAPI(userKey, ApiVersion.v3);
        try{
            net.sf.json.JSONObject user= net.sf.json.JSONObject.fromObject(bzm.getUser().toString());
            if (user.has("mail")) {
                return user.getString("mail");
            } else {
                return "";
            }
        }catch (Exception e){
            return "";
        }
    }
}