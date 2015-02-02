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

import com.amazonaws.AmazonServiceException;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.auth.BasicSessionCredentials;
import com.amazonaws.regions.Region;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.codedeploy.AmazonCodeDeployClient;
import com.amazonaws.services.identitymanagement.AmazonIdentityManagementClient;
import com.amazonaws.services.identitymanagement.model.GetUserResult;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.securitytoken.AWSSecurityTokenServiceClient;
import com.amazonaws.services.securitytoken.model.AssumeRoleRequest;
import com.amazonaws.services.securitytoken.model.AssumeRoleResult;
import com.amazonaws.services.securitytoken.model.Credentials;
import com.amazonaws.services.codedeploy.model.GetApplicationRequest;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.UUID;

import static org.apache.commons.lang.StringUtils.isEmpty;

/**
 * @author gibbon
 */
public class AWSClients {
    /**
     * Index in the colon-separated ARN that contains the account id
     * Sample ARN: arn:aws:iam::123456789012:user/David
     **/
    private static final int ARN_ACCOUNT_ID_INDEX = 4;

    public final AmazonCodeDeployClient codedeploy;
    public final AmazonS3Client         s3;

    private final String externalId;
    private final String region;


    public AWSClients(String region, AWSCredentials credentials, String externalId) {
        this.region = region;
        this.externalId = externalId;
        this.s3 = credentials != null ? new AmazonS3Client(credentials) : new AmazonS3Client();
        this.codedeploy = credentials != null ? new AmazonCodeDeployClient(credentials): new AmazonCodeDeployClient();
        codedeploy.setRegion(Region.getRegion(Regions.fromName(this.region)));
    }

    public AWSClients(String region, String iamRole, String externalId) {
        this(region, getCredentials(iamRole, externalId), externalId);
    }

    public AWSClients(String region, String awsAccessKey, String awsSecretKey, String externalId) {
        this(region, new BasicAWSCredentials(awsAccessKey, awsSecretKey), externalId);
    }

    /**
     * Via the default provider chain (i.e., global keys for this Jenkins instance),  return the account ID for the
     * currently authenticated user.
     * @return 12-digit account id
     */
    public static String getAccountId() {

        String arn = "";
        try {
            AmazonIdentityManagementClient iam = new AmazonIdentityManagementClient();
            GetUserResult user = iam.getUser();
            arn = user.getUser().getArn();
        } catch (AmazonServiceException e) {
            if (e.getErrorCode().compareTo("AccessDenied") == 0) {
                String msg = e.getMessage();
                int arnIdx = msg.indexOf("arn:aws");
                if (arnIdx != -1) {
                    int arnSpace = msg.indexOf(" ", arnIdx);
                    arn = msg.substring(arnIdx, arnSpace);
                }
            }
        }

        String accountId = arn.split(":")[ARN_ACCOUNT_ID_INDEX];
        return accountId;
    }

    public void testConnection(String s3bucket, String codeDeployApplication) throws Exception {
        String testKey = "tmp-" + UUID.randomUUID() + ".txt";
        s3.putObject(s3bucket, testKey, createTestFile());

        codedeploy.getApplication(new GetApplicationRequest().withApplicationName(codeDeployApplication));
    }

    private File createTestFile() throws IOException {
        File file = File.createTempFile("codedeploy-jenkins-plugin", ".txt");
        file.deleteOnExit();

        Writer writer = new OutputStreamWriter(new FileOutputStream(file));
        writer.write("");
        writer.close();

        return file;
    }

    private static AWSCredentials getCredentials(String iamRole, String externalId) {
        if (isEmpty(iamRole)) return null;

        AWSSecurityTokenServiceClient sts = new AWSSecurityTokenServiceClient();

        int credsDuration = (int) (AWSCodeDeployPublisher.DEFAULT_TIMEOUT_SECONDS
                        * AWSCodeDeployPublisher.DEFAULT_POLLING_FREQUENCY_SECONDS);

        if (credsDuration > 3600) {
            credsDuration = 3600;
        }

        AssumeRoleResult assumeRoleResult = sts.assumeRole(new AssumeRoleRequest()
                        .withRoleArn(iamRole)
                        .withExternalId(externalId)
                        .withDurationSeconds(credsDuration)
                        .withRoleSessionName(AWSCodeDeployPublisher.ROLE_SESSION_NAME)
        );

        Credentials stsCredentials = assumeRoleResult.getCredentials();
        BasicSessionCredentials credentials = new BasicSessionCredentials(
                stsCredentials.getAccessKeyId(),
                stsCredentials.getSecretAccessKey(),
                stsCredentials.getSessionToken()
        );

        return credentials;
    }
}
