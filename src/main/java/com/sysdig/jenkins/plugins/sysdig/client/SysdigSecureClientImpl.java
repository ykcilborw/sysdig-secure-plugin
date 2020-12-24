/*
Copyright (C) 2016-2020 Sysdig

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/
package com.sysdig.jenkins.plugins.sysdig.client;

import com.google.common.base.Strings;
import com.sysdig.jenkins.plugins.sysdig.ImageScanningException;
import com.sysdig.jenkins.plugins.sysdig.log.SysdigLogger;
import hudson.ProxyConfiguration;
import jenkins.model.Jenkins;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.apache.http.HttpException;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.Credentials;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.conn.routing.HttpRoute;
import org.apache.http.conn.routing.HttpRoutePlanner;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.impl.client.*;
import org.apache.http.impl.conn.DefaultProxyRoutePlanner;
import org.apache.http.protocol.HttpContext;
import org.apache.http.ssl.SSLContextBuilder;
import org.apache.http.conn.ssl.TrustSelfSignedStrategy;
import org.apache.http.entity.StringEntity;
import org.apache.http.util.EntityUtils;

import java.io.IOException;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Pattern;

public class SysdigSecureClientImpl implements SysdigSecureClient {
  private final String token;
  private final String apiURL;
  private final boolean verifySSL;
  private final SysdigLogger logger;

  public SysdigSecureClientImpl(String token, String apiURL, boolean verifySSL, SysdigLogger logger) {
    this.token = token;
    this.apiURL = apiURL.replaceAll("/+$", "");
    this.verifySSL = verifySSL;
    this.logger = logger;
  }

  private String sendRequest(String url, String body) throws ImageScanningException, InterruptedException {
    try (CloseableHttpClient httpclient = makeHttpClient(verifySSL)) {

      HttpRequestBase httpRequest;
      if (body == null) {
        httpRequest = new HttpGet(url);
      } else {
        httpRequest = new HttpPost(url);
        ((HttpPost) httpRequest).setEntity(new StringEntity(body));
      }
      httpRequest.addHeader("Content-Type", "application/json");
      httpRequest.addHeader("Authorization", String.format("Bearer %s", token));

      logger.logDebug("Sending request: " + httpRequest.toString());
      logger.logDebug("Body:\n" + body);

      ExecutorService executor = Executors.newSingleThreadExecutor();
      //Run in a thread to allow aborting with httpRequest.abort()
      Future<String> scanTask = executor.submit(() -> {
        try (CloseableHttpResponse response = httpclient.execute(httpRequest)) {
          String responseBody = EntityUtils.toString(response.getEntity());
          logger.logDebug("Response: " + response.getStatusLine().toString());
          logger.logDebug("Response body:\n" + responseBody);

          int statusCode = response.getStatusLine().getStatusCode();

          if (statusCode != 200) {
            throw new ImageScanningException(String.format("Submit image - HTTP %d: %s", response.getStatusLine().getStatusCode(), responseBody));
          }

          return responseBody;
        }
      });


      try {
        return scanTask.get();
      } catch (InterruptedException e) {
        // This way the main thread can be interrupted and we abort the Http client cleanly
        httpRequest.abort();
        throw e;
      } catch (ExecutionException e) {
        // An exception was thrown inside the thread. Just propage
        if (e.getCause().getClass() == ImageScanningException.class) {
          throw (ImageScanningException) e.getCause();
        }

        throw new ImageScanningException("Error sending request to '" + url + "' - Unexpected error", e);
      }

    } catch (IOException | NoSuchAlgorithmException | KeyStoreException | KeyManagementException e) {
      throw new ImageScanningException("Error preparing Http client for '" + url + "'", e);
    }
  }

  @Override
  public String submitImageForScanning(String tag, String dockerFileContents, Map<String, String> annotations) throws ImageScanningException, InterruptedException {
    String imagesUrl = String.format("%s/api/scanning/v1/anchore/images", apiURL);

    JSONObject jsonBody = new JSONObject();
    jsonBody.put("tag", tag);

    if (null != dockerFileContents) {
      jsonBody.put("dockerfile", dockerFileContents);
    }

    if (null != annotations) {
      jsonBody.put("annotations", annotations);
    }

    String responseBody = sendRequest(imagesUrl, jsonBody.toString());
    return JSONObject.fromObject(JSONArray.fromObject(responseBody).get(0)).getString("imageDigest");
  }

  @Override
  public JSONObject retrieveImageScanningVulnerabilities(String imageDigest) throws ImageScanningException, InterruptedException {
    String url = String.format("%s/api/scanning/v1/anchore/images/%s/vuln/all", apiURL, imageDigest);
    return JSONObject.fromObject(sendRequest(url, null));
  }

  @Override
  public JSONArray retrieveImageScanningResults(String tag, String imageDigest) throws ImageScanningException, InterruptedException {
    String url = String.format("%s/api/scanning/v1/anchore/images/%s/check?tag=%s&detail=true", apiURL, imageDigest, tag);
    return JSONArray.fromObject(sendRequest(url, null));
  }

  private static CloseableHttpClient makeHttpClient(boolean verifySSL) throws KeyStoreException, NoSuchAlgorithmException, KeyManagementException {
    HttpClientBuilder clientBuilder = HttpClients.custom();


    // Option to skip TLS certificate verification
    if (!verifySSL) {
      SSLContextBuilder sslContextBuilder = new SSLContextBuilder();
      sslContextBuilder.loadTrustMaterial(null, new TrustSelfSignedStrategy());
      SSLConnectionSocketFactory sslsf = new SSLConnectionSocketFactory(
        sslContextBuilder.build(),
        NoopHostnameVerifier.INSTANCE);
      clientBuilder.setSSLSocketFactory(sslsf);
    }

    clientBuilder.useSystemProperties();

    // Add proxy configuration to the client
    ProxyConfiguration proxyConfiguration = Jenkins.get().proxy;
    if (proxyConfiguration != null && !Strings.isNullOrEmpty(proxyConfiguration.name)) {
      HttpHost proxy = new HttpHost(proxyConfiguration.name, proxyConfiguration.port, "http");

      if (proxyConfiguration.getNoProxyHostPatterns().size() > 0) {
        HttpRoutePlanner routePlanner = new DefaultProxyRoutePlanner(proxy) {

          @Override
          public HttpRoute determineRoute(
            final HttpHost host,
            final HttpRequest request,
            final HttpContext context) throws HttpException {
            String hostname = host.getHostName();
            for (Pattern p : proxyConfiguration.getNoProxyHostPatterns()) {
              if (p.matcher(hostname).matches()) {
                // Return direct route
                return new HttpRoute(host);
              }
            }
            return super.determineRoute(host, request, context);
          }
        };

        clientBuilder.setRoutePlanner(routePlanner);
      }

      clientBuilder.setProxy(proxy);

      if (!Strings.isNullOrEmpty(proxyConfiguration.getUserName())) {
        Credentials credentials = new UsernamePasswordCredentials(proxyConfiguration.getUserName(), proxyConfiguration.getPassword());
        CredentialsProvider credsProvider = new BasicCredentialsProvider();
        credsProvider.setCredentials(new AuthScope(proxyConfiguration.name, proxyConfiguration.port), credentials);
        clientBuilder.setDefaultCredentialsProvider(credsProvider);
        clientBuilder.setProxyAuthenticationStrategy(new ProxyAuthenticationStrategy());
      }
    }
    return clientBuilder.build();
  }
}
