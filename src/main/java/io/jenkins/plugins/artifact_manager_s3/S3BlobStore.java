/*
 * The MIT License
 * 
 * Copyright 2018 CloudBees, Inc.
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package io.jenkins.plugins.artifact_manager_s3;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Date;
import java.util.NoSuchElementException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import edu.umd.cs.findbugs.annotations.NonNull;

import org.jclouds.ContextBuilder;
import org.jclouds.aws.domain.SessionCredentials;
import org.jclouds.aws.s3.AWSS3ProviderMetadata;
import org.jclouds.blobstore.BlobStoreContext;
import org.jclouds.blobstore.domain.Blob;
import org.jclouds.domain.Credentials;
import org.jclouds.osgi.ProviderRegistry;
import org.jclouds.providers.ProviderMetadata;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

import com.amazonaws.auth.AWSSessionCredentials;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;

import hudson.Extension;
import shaded.com.google.common.base.Supplier;

/**
 * Extension that customizes JCloudsBlobStore for AWS S3. Credentials are fetched from the environment, env vars, aws
 * profiles,...
 */
@Extension
@Restricted(NoExternalUse.class)
public class S3BlobStore extends JCloudsApiExtensionPoint {

    private static final Logger LOGGER = Logger.getLogger(S3BlobStore.class.getName());

    private static final long serialVersionUID = -8864075675579867370L;

    @Override
    public String id() {
        return "aws-s3";
    }

    @Override
    public ProviderMetadata getProvider() {
        return AWSS3ProviderMetadata.builder().build();
    }

    public BlobStoreContext getContext() throws IOException {
        LOGGER.log(Level.FINEST, "Building context for {0}", id());
        ProviderRegistry.registerProvider(AWSS3ProviderMetadata.builder().build());
        try {
            return ContextBuilder.newBuilder(id()).credentialsSupplier(getCredentialsSupplier())
                    .buildView(BlobStoreContext.class);
        } catch (NoSuchElementException x) {
            throw new IOException(x);
        }
    }

    @Override
    public Supplier<Credentials> getCredentialsSupplier() throws IOException {
        // get user credentials from env vars, profiles,...
        AmazonS3ClientBuilder builder = AmazonS3ClientBuilder.standard();
        // Assume we are using session credentials
        AWSSessionCredentials awsCredentials = (AWSSessionCredentials) builder.getCredentials().getCredentials();
        if (awsCredentials == null) {
            throw new IOException("Unable to get credentials from environment");
        }

        SessionCredentials sessionCredentials = SessionCredentials.builder()
                .accessKeyId(awsCredentials.getAWSAccessKeyId())
                .secretAccessKey(awsCredentials.getAWSSecretKey())
                .sessionToken(awsCredentials.getSessionToken())
                .build();

        return new Supplier<Credentials>() {
            @Override
            public Credentials get() {
                return sessionCredentials;
            }
        };
    }

    @NonNull
    @Override
    public URI toURI(@NonNull String container, @NonNull String key) {
        assert container != null;
        assert key != null;
        try {
            // TODO proper encoding
            return new URI(String.format("https://%s.s3.amazonaws.com/%s", container,
                    URLEncoder.encode(key, "UTF-8").replaceAll("%2F", "/").replaceAll("%3A", ":")));
        } catch (URISyntaxException | UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * @see <a href="https://docs.aws.amazon.com/AmazonS3/latest/dev/ShareObjectPreSignedURLJavaSDK.html">Generate a
     *      Pre-signed Object URL using AWS SDK for Java</a>
     */
    @Override
    public URL toExternalURL(@NonNull Blob blob, @NonNull HttpMethod httpMethod) throws IOException {
        assert blob != null;
        assert httpMethod != null;
        AmazonS3ClientBuilder builder = AmazonS3ClientBuilder.standard();
        Date expiration = new Date(System.currentTimeMillis() + TimeUnit.HOURS.toMillis(1));
        String container = blob.getMetadata().getContainer();
        String name = blob.getMetadata().getName();
        LOGGER.log(Level.FINE, "Generating presigned URL for {0} / {1} for method {2}",
                new Object[] { container, name, httpMethod });
        com.amazonaws.HttpMethod awsMethod;
        switch (httpMethod) {
        case PUT:
            awsMethod = com.amazonaws.HttpMethod.PUT;
            break;
        case GET:
            awsMethod = com.amazonaws.HttpMethod.GET;
            break;
        default:
            throw new IOException("HTTP Method " + httpMethod + " not supported for extension " + id());
        }
        return builder.build().generatePresignedUrl(container, name, expiration, awsMethod);
    }
}

