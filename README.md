AWS CodeDeploy Jenkins Plugin
=============================

The AWS CodeDeploy Jenkins plugin provides a post-build step for your Jenkins
project. Upon a successful build, it will zip the workspace, upload to S3, and
start a new deployment. Optionally, you can set it to wait for the deployment to
finish, making the final success contingent on the success of the deployment.

Setting up
----------

After building and installing the plugin, some simple configuration is needed
for your project. 

**Freestyle**

1. Open up your project configuration
1. In the `Post-build Actions` section, select "Deploy an application to AWS
CodeDeploy"
1. Application Name, Deployment Group, Deployment Config, and region are all
required options.
1. For authentication, there are two options. Either option requires that the
associated role has, at minimum, a policy that permits `codedeploy:*` and
`s3:Put*`.
  1. Access/Secret key pair. For example, the keys associated with a specific
  IAM user. If left blank, the default chain of credentials will be checked.
  1. Temporary access keys. These will use the global keys from the Jenkins
  instance.

**Pipeline**

1.  Create a [Jenkins Pipeline](https://wiki.jenkins-ci.org/display/JENKINS/Pipeline+Plugin) project
1.  Use the Pipeline Snippet Generator
1.  For 'Sample Step', choose 'step: General Build Step'
1.  For 'Build Step', choose 'Deploy an application to AWS CodeDeploy'
1.  populate variables and then 'Generate Groovy'

Here is a rather blank example:

	step([$class: 'AWSCodeDeployPublisher', applicationName: '', awsAccessKey: '', awsSecretKey: '', credentials: 'awsAccessKey', deploymentGroupAppspec: false, deploymentGroupName: '', deploymentMethod: 'deploy', excludes: '', iamRoleArn: '', includes: '**', proxyHost: '', proxyPort: 0, region: 'ap-northeast-1', s3bucket: '', s3prefix: '', subdirectory: '', versionFileName: '', waitForCompletion: false])

License
-------

This plugin is licensed under Apache 2.0. See the LICENSE file for more information.
