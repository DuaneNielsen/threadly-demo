Installation steps:
1. Download the tar file logcollector_onprem_v1.1.tar.gz

2. Navigate to the download location & execute below command to extract the content of tar file:
   tar -xzvf logcollector_onprem_v1.1.tar.gz

3. Execute script installLogCollector.sh & provide following information as prompted:
   Logcollector_Onprem Install Location	/opt/ca/oi/logcollector	
   Enter OI SaaS Ingestion API Endpoint	
   Provide URL based on the SaaS environment to be used:

   For example in GCP Prod: https://logcollector-route-6060-ao-doi.app.gpus1.saas.broadcom.com

4. Do you wish to configure http proxy?(YES/NO)
   NOTE: Proxy support added with the latest build. Enter YES to configure proxy

   If YES, Provide Proxy server details :

   Enter Proxy Hostname
   Enter Proxy Port
   Enter Proxy Username
   Enter Proxy Password

   If No, Proceed with the installation


Syslog configuration:
   1. Refer Syslog Using rsyslog (Unix and Linux) documentation to configure rsyslog.conf (Step 1 through 3). Make sure of following points while referring the documentation:
	Documentation link: https://techdocs.broadcom.com/content/broadcom/techdocs/us/en/ca-enterprise-software/it-operations-management/agile-operations-analytics-base-platform/17-3/getting-started/log-analytics-deployment/set-up-log-analytics.html

   2. Execute below command to find rsyslog version & decide on executing Step 3 or Step 4
      rsyslogd -version

      In Step 3, 
      Uncomment either TCP or UDP line out of the following (only 1 line must be active)
      #For Connection with TCP Port 6514
       *.*; @@<LOG ANALYTICS HOST>:6514;ls_json 
      #For Connection with UDP Port 5140
       *.*; @@<LOG ANALYTICS HOST>:5140;ls_json
      Save rsyslog.conf & restart using below command
      service rsyslog restart
