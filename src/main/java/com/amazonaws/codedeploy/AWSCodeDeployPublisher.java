/*
 * Copyright 2014 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */
package com.amazonaws.codedeploy;

import com.amazonaws.regions.Regions;
import com.amazonaws.services.codedeploy.model.ListApplicationsResult;
import com.amazonaws.services.codedeploy.model.ListDeploymentGroupsRequest;
import com.amazonaws.services.codedeploy.model.ListDeploymentGroupsResult;
import com.amazonaws.services.codedeploy.model.RevisionLocation;
import com.amazonaws.services.codedeploy.model.RevisionLocationType;
import com.amazonaws.services.s3.model.PutObjectResult;
import com.amazonaws.services.codedeploy.model.BundleType;
import com.amazonaws.services.codedeploy.model.CreateDeploymentRequest;
import com.amazonaws.services.codedeploy.model.CreateDeploymentResult;
import com.amazonaws.services.codedeploy.model.DeploymentInfo;
import com.amazonaws.services.codedeploy.model.DeploymentOverview;
import com.amazonaws.services.codedeploy.model.DeploymentStatus;
import com.amazonaws.services.codedeploy.model.GetDeploymentRequest;
import com.amazonaws.services.codedeploy.model.RegisterApplicationRevisionRequest;
import com.amazonaws.services.codedeploy.model.S3Location;

import hudson.AbortException;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Util;
import hudson.Extension;
import hudson.model.AbstractProject;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Publisher;
import hudson.util.DirScanner;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jenkins.tasks.SimpleBuildStep;
import net.sf.json.JSONObject;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.io.FileUtils;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.servlet.ServletException;

/**
 * The AWS CodeDeploy Publisher is a post-build plugin that adds the ability to start a new CodeDeploy deployment
 * with the project's workspace as the application revision.
 *
 * To configure, users must create an IAM role that allows "S3" and "CodeDeploy" actions and must be assumable by
 * the globally configured keys. This allows the plugin to get temporary credentials instead of requiring permanent
 * credentials to be configured for each project.
 */
public class AWSCodeDeployPublisher extends Publisher implements SimpleBuildStep {
    public static final long      DEFAULT_TIMEOUT_SECONDS           = 900;
    public static final long      DEFAULT_POLLING_FREQUENCY_SECONDS = 15;
    public static final String    ROLE_SESSION_NAME                 = "jenkins-codedeploy-plugin";
    private static final Regions[] AVAILABLE_REGIONS                 = {Regions.AP_NORTHEAST_1, Regions.AP_SOUTHEAST_1, Regions.AP_SOUTHEAST_2, Regions.EU_WEST_1, Regions.US_EAST_1, Regions.US_WEST_2, Regions.EU_CENTRAL_1, Regions.US_WEST_1, Regions.SA_EAST_1, Regions.AP_NORTHEAST_2, Regions.AP_SOUTH_1, Regions.US_EAST_2, Regions.CA_CENTRAL_1, Regions.EU_WEST_2, Regions.CN_NORTH_1};

    private final String  s3bucket;
    private final String  s3prefix;
    private final String  applicationName;
    private final String  deploymentGroupName; // TODO allow for deployment to multiple groups
    private final String  deploymentConfig;
    private final Long    pollingTimeoutSec;
    private final Long    pollingFreqSec;
    private final boolean deploymentGroupAppspec;
    private final boolean waitForCompletion;
    private final String  externalId;
    private final String  iamRoleArn;
    private final String region;
    private final String includes;
    private final String excludes;
    private final String subdirectory;
    private final String proxyHost;
    private final int proxyPort;

    private final String awsAccessKey;
    private final String awsSecretKey;
    private final String credentials;
    private final String deploymentMethod;
    private final String versionFileName;

    private PrintStream logger;
    private Map <String, String> envVars;
    // Fields in config.jelly must match the parameter names in the "DataBoundConstructor"
    @DataBoundConstructor
    public AWSCodeDeployPublisher(
            String s3bucket,
            String s3prefix,
            String applicationName,
            String deploymentGroupName,
            String deploymentConfig,
            String region,
            Boolean deploymentGroupAppspec,
            Boolean waitForCompletion,
            Long pollingTimeoutSec,
            Long pollingFreqSec,
            String credentials,
            String versionFileName,
            String deploymentMethod,
            String awsAccessKey,
            String awsSecretKey,
            String iamRoleArn,
            String externalId,
            String includes,
            String proxyHost,
            int proxyPort,
            String excludes,
            String subdirectory) {

        this.externalId = externalId;
        this.applicationName = applicationName;
        this.deploymentGroupName = deploymentGroupName;
        if (deploymentConfig != null && deploymentConfig.length() == 0) {
            this.deploymentConfig = null;
        } else {
            this.deploymentConfig = deploymentConfig;
        }
        this.region = region;
        this.includes = includes;
        this.excludes = excludes;
        this.subdirectory = subdirectory;
        this.proxyHost = proxyHost;
        this.proxyPort = proxyPort;
        this.credentials = credentials;
        this.deploymentMethod = deploymentMethod;
        this.versionFileName = versionFileName;
        this.awsAccessKey = awsAccessKey;
        this.awsSecretKey = awsSecretKey;
        this.iamRoleArn = iamRoleArn;
        this.deploymentGroupAppspec = deploymentGroupAppspec;

        if (waitForCompletion != null && waitForCompletion) {
            this.waitForCompletion = waitForCompletion;
            if (pollingTimeoutSec == null) {
                this.pollingTimeoutSec = DEFAULT_TIMEOUT_SECONDS;
            } else {
                this.pollingTimeoutSec = pollingTimeoutSec;
            }
            if (pollingFreqSec == null) {
                this.pollingFreqSec = DEFAULT_POLLING_FREQUENCY_SECONDS;
            } else {
                this.pollingFreqSec = pollingFreqSec;
            }
        } else {
            this.waitForCompletion = false;
            this.pollingTimeoutSec = null;
            this.pollingFreqSec = null;
        }

        this.s3bucket = s3bucket;
        if (s3prefix == null || s3prefix.equals("/") || s3prefix.length() == 0) {
            this.s3prefix = "";
        } else {
            this.s3prefix = s3prefix;
        }
    }

    @Override
    public void perform(@Nonnull Run<?,?> build, @Nonnull FilePath workspace, @Nonnull Launcher launcher, @Nonnull TaskListener listener) throws IOException, InterruptedException {
        this.logger = listener.getLogger();
        envVars = build.getEnvironment(listener);
        final boolean buildFailed = build.getResult() == Result.FAILURE;
        if (buildFailed) {
            logger.println("Skipping CodeDeploy publisher as build failed");
            return;
        }

        final AWSClients aws;
        if ("awsAccessKey".equals(credentials)) {
            if (StringUtils.isEmpty(this.awsAccessKey) && StringUtils.isEmpty(this.awsSecretKey)) {
                aws = AWSClients.fromDefaultCredentialChain(
                        this.region,
                        this.proxyHost,
                        this.proxyPort);
            } else {
                aws = AWSClients.fromBasicCredentials(
                        this.region,
                        this.awsAccessKey,
                        this.awsSecretKey,
                        this.proxyHost,
                        this.proxyPort);
            }
        } else {
            aws = AWSClients.fromIAMRole(
                this.region,
                this.iamRoleArn,
                this.getDescriptor().getExternalId(),
                this.proxyHost,
                this.proxyPort);
        }

        boolean success = false;

        try {

            verifyCodeDeployApplication(aws);

            final String projectName = build.getDisplayName();
            if (workspace == null) {
                throw new IllegalArgumentException("No workspace present for the build.");
            }
            final FilePath sourceDirectory = getSourceDirectory(workspace);
            final RevisionLocation revisionLocation = zipAndUpload(aws, projectName, sourceDirectory);

            registerRevision(aws, revisionLocation);
            if ("onlyRevision".equals(deploymentMethod)){
              success = true;
            } else {

              String deploymentId = createDeployment(aws, revisionLocation);

              success = waitForDeployment(aws, deploymentId);
            }

        } catch (Exception e) {

            this.logger.println("Failed CodeDeploy post-build step; exception follows.");
            this.logger.println(e.getMessage());
            e.printStackTrace(this.logger);
        }

        if (!success) {
            throw new AbortException();
        }
    }

    private FilePath getSourceDirectory(FilePath basePath) throws IOException, InterruptedException {
        String subdirectory = StringUtils.trimToEmpty(getSubdirectoryFromEnv());
        if (!subdirectory.isEmpty() && !subdirectory.startsWith("/")) {
            subdirectory = "/" + subdirectory;
        }
        FilePath sourcePath = basePath.withSuffix(subdirectory).absolutize();
        if (!sourcePath.isDirectory() || !isSubDirectory(basePath, sourcePath)) {
            throw new IllegalArgumentException("Provided path (resolved as '" + sourcePath
                    +"') is not a subdirectory of the workspace (resolved as '" + basePath + "')");
        }
        return sourcePath;
    }

    private boolean isSubDirectory(FilePath parent, FilePath child) {
        FilePath parentFolder = child;
        while (parentFolder!=null) {
            if (parent.equals(parentFolder)) {
                return true;
            }
            parentFolder = parentFolder.getParent();
        }
        return false;
    }

    private void verifyCodeDeployApplication(AWSClients aws) throws IllegalArgumentException {
        // Check that the application exists
        ListApplicationsResult applications = aws.codedeploy.listApplications();
        String applicationName = getApplicationNameFromEnv();
        String deploymentGroupName = getDeploymentGroupNameFromEnv();

        if (!applications.getApplications().contains(applicationName)) {
            throw new IllegalArgumentException("Cannot find application named '" + applicationName + "'");
        }

        // Check that the deployment group exists
        ListDeploymentGroupsResult deploymentGroups = aws.codedeploy.listDeploymentGroups(
                new ListDeploymentGroupsRequest()
                        .withApplicationName(applicationName)
        );

        if (!deploymentGroups.getDeploymentGroups().contains(deploymentGroupName)) {
            throw new IllegalArgumentException("Cannot find deployment group named '" + deploymentGroupName + "'");
        }
    }

    private RevisionLocation zipAndUpload(AWSClients aws, String projectName, FilePath sourceDirectory) throws IOException, InterruptedException, IllegalArgumentException {

        File zipFile = null;
        File versionFile;
        versionFile = new File(sourceDirectory + "/" + versionFileName);

        InputStreamReader reader = null;
        String version = null;
        try {
          reader = new InputStreamReader(new FileInputStream(versionFile), "UTF-8");
          char[] chars = new char[(int) versionFile.length() -1];
          reader.read(chars);
          version = new String(chars);
          reader.close();
        } catch (IOException e) {
          e.printStackTrace();
        } finally {
          if(reader !=null){reader.close();}
        }

        if (version != null){
          zipFile = new File("/tmp/" + projectName + "-" + version + ".zip");
          final boolean fileCreated = zipFile.createNewFile();
          if (!fileCreated) {
            logger.println("File already exists, overwriting: " + zipFile.getPath());
          }
        } else {
          zipFile = File.createTempFile(projectName + "-", ".zip");
        }

        String key;
        File appspec;
        File dest;
        String deploymentGroupName = getDeploymentGroupNameFromEnv();
        String prefix = getS3PrefixFromEnv();
        String bucket = getS3BucketFromEnv();

        if(bucket.indexOf("/") > 0){
            throw new IllegalArgumentException("S3 Bucket field cannot contain any subdirectories.  Bucket name only!");
        }

        try {
            if (this.deploymentGroupAppspec) {
                appspec = new File(sourceDirectory + "/appspec." + deploymentGroupName + ".yml");
                if (appspec.exists()) {
                    dest = new File(sourceDirectory + "/appspec.yml");
                    FileUtils.copyFile(appspec, dest);
                    logger.println("Use appspec." + deploymentGroupName + ".yml");
                }
                if (!appspec.exists()) {
                    throw new IllegalArgumentException("/appspec." + deploymentGroupName + ".yml file does not exist" );
                }

            }

            logger.println("Zipping files into " + zipFile.getAbsolutePath());

            FileOutputStream outputStream = new FileOutputStream(zipFile);
            try {
                sourceDirectory.zip(
                        outputStream,
                        new DirScanner.Glob(this.includes, this.excludes)
                );
            } finally {
                outputStream.close();
            }

            if (prefix.isEmpty()) {
                key = zipFile.getName();
            } else {
                key = Util.replaceMacro(prefix, envVars);
                if (prefix.endsWith("/")) {
                    key += zipFile.getName();
                } else {
                    key += "/" + zipFile.getName();
                }
            }
            logger.println("Uploading zip to s3://" + bucket + "/" + key);
            PutObjectResult s3result = aws.s3.putObject(bucket, key, zipFile);

            S3Location s3Location = new S3Location();
            s3Location.setBucket(bucket);
            s3Location.setKey(key);
            s3Location.setBundleType(BundleType.Zip);
            s3Location.setETag(s3result.getETag());

            RevisionLocation revisionLocation = new RevisionLocation();
            revisionLocation.setRevisionType(RevisionLocationType.S3);
            revisionLocation.setS3Location(s3Location);

            return revisionLocation;
        } finally {
            final boolean deleted = zipFile.delete();
            if (!deleted) {
                logger.println("Failed to clean up file " + zipFile.getPath());
            }
        }
    }

    private void registerRevision(AWSClients aws, RevisionLocation revisionLocation) {

        String applicationName = getApplicationNameFromEnv();
        this.logger.println("Registering revision for application '" + applicationName + "'");

        aws.codedeploy.registerApplicationRevision(
                new RegisterApplicationRevisionRequest()
                        .withApplicationName(applicationName)
                        .withRevision(revisionLocation)
                        .withDescription("Application revision registered via Jenkins")
        );
    }

    private String createDeployment(AWSClients aws, RevisionLocation revisionLocation) throws Exception {

        this.logger.println("Creating deployment with revision at " + revisionLocation);

        CreateDeploymentResult createDeploymentResult = aws.codedeploy.createDeployment(
                new CreateDeploymentRequest()
                        .withDeploymentConfigName(getDeploymentConfigFromEnv())
                        .withDeploymentGroupName(getDeploymentGroupNameFromEnv())
                        .withApplicationName(getApplicationNameFromEnv())
                        .withRevision(revisionLocation)
                        .withDescription("Deployment created by Jenkins")
        );

        return createDeploymentResult.getDeploymentId();
    }

    private boolean waitForDeployment(AWSClients aws, String deploymentId) throws InterruptedException {

        if (!this.waitForCompletion) {
            return true;
        }

        logger.println("Monitoring deployment with ID " + deploymentId + "...");
        GetDeploymentRequest deployInfoRequest = new GetDeploymentRequest();
        deployInfoRequest.setDeploymentId(deploymentId);

        DeploymentInfo deployStatus = aws.codedeploy.getDeployment(deployInfoRequest).getDeploymentInfo();

        long startTimeMillis;
        if (deployStatus == null || deployStatus.getStartTime() == null) {
            startTimeMillis = new Date().getTime();
        } else {
            startTimeMillis = deployStatus.getStartTime().getTime();
        }

        boolean success = true;
        long pollingTimeoutMillis = this.pollingTimeoutSec * 1000L;
        long pollingFreqMillis = this.pollingFreqSec * 1000L;

        while (deployStatus == null || deployStatus.getCompleteTime() == null) {

            if (deployStatus == null) {
                logger.println("Deployment status: unknown.");
            } else {
                DeploymentOverview overview = deployStatus.getDeploymentOverview();
                logger.println("Deployment status: " + deployStatus.getStatus() + "; instances: " + overview);
            }

            deployStatus = aws.codedeploy.getDeployment(deployInfoRequest).getDeploymentInfo();
            Date now = new Date();

            if (now.getTime() - startTimeMillis >= pollingTimeoutMillis) {
                this.logger.println("Exceeded maximum polling time of " + pollingTimeoutMillis + " milliseconds.");
                success = false;
                break;
            }

            Thread.sleep(pollingFreqMillis);
        }

        logger.println("Deployment status: " + deployStatus.getStatus() + "; instances: " + deployStatus.getDeploymentOverview());

        if (!deployStatus.getStatus().equals(DeploymentStatus.Succeeded.toString())) {
            this.logger.println("Deployment did not succeed. Final status: " + deployStatus.getStatus());
            success = false;
        }

        return success;
    }

    // Overridden for better type safety.
    // If your plugin doesn't really define any property on Descriptor,
    // you don't have to do this.
    @Override
    public DescriptorImpl getDescriptor() {

        return (DescriptorImpl) super.getDescriptor();
    }

    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.NONE;
    }

    /**
     *
     * Descriptor for {@link AWSCodeDeployPublisher}. Used as a singleton.
     * The class is marked as public so that it can be accessed from views.
     *
     * See <tt>src/main/resources/com/amazonaws/codedeploy/AWSCodeDeployPublisher/*.jelly</tt>
     * for the actual HTML fragment for the configuration screen.
     */
    @Extension // This indicates to Jenkins that this is an implementation of an extension point.
    public static final class DescriptorImpl extends BuildStepDescriptor<Publisher> {

        private String externalId;
        private String awsAccessKey;
        private String awsSecretKey;
        private String proxyHost;
        private int proxyPort;

        /**
         * In order to load the persisted global configuration, you have to
         * call load() in the constructor.
         */
        public DescriptorImpl() {
            load();

            if (externalId == null) {
                setExternalId(UUID.randomUUID().toString());
            }
        }

        public FormValidation doCheckName(@QueryParameter String value)
                throws IOException, ServletException {
            if (value.length() == 0)
                return FormValidation.error("Please add the appropriate values");
            return FormValidation.ok();
        }

        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            // Indicates that this builder can be used with all kinds of project types
            return true;
        }

        /**
         * This human readable name is used in the configuration screen.
         */
        public String getDisplayName() {
            return "Deploy an application to AWS CodeDeploy";
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject formData) throws FormException {

            awsAccessKey = formData.getString("awsAccessKey");
            awsSecretKey = formData.getString("awsSecretKey");
            proxyHost = formData.getString("proxyHost");
            proxyPort = Integer.parseInt(formData.getString("proxyPort"));

            req.bindJSON(this, formData);
            save();
            return super.configure(req, formData);
        }

        public String getExternalId() {
            return externalId;
        }

        public void setExternalId(String externalId) {
            this.externalId = externalId;
        }

        public void setProxyHost(String proxyHost) {
            this.proxyHost = proxyHost;
        }

        public String getProxyHost() {
            return proxyHost;
        }

        public void setProxyPort(int proxyPort) {
            this.proxyPort = proxyPort;
        }

        public int getProxyPort() {
            return proxyPort;
        }

        public String getAccountId() {
            return AWSClients.getAccountId(getProxyHost(), getProxyPort());
        }

        public FormValidation doTestConnection(
                @QueryParameter String s3bucket,
                @QueryParameter String applicationName,
                @QueryParameter String region,
                @QueryParameter String iamRoleArn,
                @QueryParameter String proxyHost,
                @QueryParameter int proxyPort) {

            System.out.println("Testing connection with parameters: "
                    + s3bucket + ","
                    + applicationName + ","
                    + region + ","
                    + iamRoleArn + ","
                    + this.externalId + ","
                    + proxyHost + ","
                    + proxyPort
            );

            try {
                AWSClients awsClients = AWSClients.fromIAMRole(region, iamRoleArn, this.externalId, proxyHost, proxyPort);
                awsClients.testConnection(s3bucket, applicationName);
            } catch (Exception e) {
                return FormValidation.error("Connection test failed with error: " + e.getMessage());
            }

            return FormValidation.ok("Connection test passed.");
        }

        public ListBoxModel doFillRegionItems() {
            ListBoxModel items = new ListBoxModel();
            for (Regions region : AVAILABLE_REGIONS) {
                items.add(region.toString(), region.getName());
            }
            return items;
        }

        public String getAwsSecretKey()
        {
            return awsSecretKey;
        }

        public void setAwsSecretKey(String awsSecretKey)
        {
            this.awsSecretKey = awsSecretKey;
        }

        public String getAwsAccessKey()
        {
            return awsAccessKey;
        }

        public void setAwsAccessKey(String awsAccessKey)
        {
            this.awsAccessKey = awsAccessKey;
        }

    }

    public String getApplicationName() {
        return applicationName;
    }

    public String getDeploymentGroupName() {
        return deploymentGroupName;
    }

    public String getDeploymentConfig() {
        return deploymentConfig;
    }

    public String getS3bucket() {
        return s3bucket;
    }

    public String getS3prefix() {
        return s3prefix;
    }

    public Long getPollingTimeoutSec() {
        return pollingTimeoutSec;
    }

    public String getIamRoleArn() {
        return iamRoleArn;
    }

    public String getAwsAccessKey() {
        return awsAccessKey;
    }

    public String getAwsSecretKey() {
        return awsSecretKey;
    }

    public Long getPollingFreqSec() {
        return pollingFreqSec;
    }

    public String getExternalId() {
        return externalId;
    }

    public String getDeploymentMethod() {
        return deploymentMethod;
    }

    public String getVersionFileName() {
        return versionFileName;
    }

    public boolean getWaitForCompletion() {
        return waitForCompletion;
    }

    public boolean getDeploymentGroupAppspec() {
        return deploymentGroupAppspec;
    }

    public String getCredentials() {
        return credentials;
    }

    public String getIncludes() {
        return includes;
    }

    public String getExcludes() {
        return excludes;
    }

    public String getSubdirectory() {
        return subdirectory;
    }

    public String getRegion() {
        return region;
    }

    public String getProxyHost() {
        return proxyHost;
    }

    public int getProxyPort() {
        return proxyPort;
    }

    public String getApplicationNameFromEnv() {
        return Util.replaceMacro(this.applicationName, envVars);
    }

    public String getDeploymentGroupNameFromEnv() {
        return Util.replaceMacro(this.deploymentGroupName, envVars);
    }

    public String getDeploymentConfigFromEnv() {
        return Util.replaceMacro(this.deploymentConfig, envVars);
    }

    public String getS3BucketFromEnv() {
        return Util.replaceMacro(this.s3bucket, envVars);
    }

    public String getS3PrefixFromEnv() {
        return Util.replaceMacro(this.s3prefix, envVars);
    }

    public String getSubdirectoryFromEnv() {
        return Util.replaceMacro(this.subdirectory, envVars);
    }
}
