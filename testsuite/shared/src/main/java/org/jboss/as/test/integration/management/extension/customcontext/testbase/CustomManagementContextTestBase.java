/*
Copyright 2016 Red Hat, Inc.

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

package org.jboss.as.test.integration.management.extension.customcontext.testbase;

import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.EXTENSION;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OPERATION_HEADERS;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OUTCOME;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.ROLLBACK_ON_RUNTIME_FAILURE;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.SUBSYSTEM;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.SUCCESS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;

import org.apache.http.HttpResponse;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.jboss.as.controller.PathAddress;
import org.jboss.as.controller.PathElement;
import org.jboss.as.controller.operations.common.Util;
import org.jboss.as.test.http.Authentication;
import org.jboss.as.test.integration.management.extension.EmptySubsystemParser;
import org.jboss.as.test.integration.management.extension.ExtensionUtils;
import org.jboss.as.test.integration.management.extension.customcontext.CustomContextExtension;
import org.jboss.dmr.ModelNode;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.wildfly.core.testrunner.ManagementClient;

/**
 * Base class for tests of integrating a custom management context on the http interface.
 * We install an extension that does this kind of integration and see the result.
 *
 * @author Brian Stansberry
 */
public abstract class CustomManagementContextTestBase {

    protected static final PathAddress EXT = PathAddress.pathAddress(EXTENSION, CustomContextExtension.EXTENSION_NAME);

    protected static final PathElement SUB = PathElement.pathElement(SUBSYSTEM, CustomContextExtension.SUBSYSTEM_NAME);

    @BeforeClass
    public static void setup() throws IOException {
        ExtensionUtils.createExtensionModule(CustomContextExtension.EXTENSION_NAME, CustomContextExtension.class,
                EmptySubsystemParser.class.getPackage());
    }

    @After
    public void teardown() throws IOException {

        IOException ioe = null;
        AssertionError ae = null;
        RuntimeException re = null;
        ManagementClient managementClient = getManagementClient();
        PathAddress[] cleanUp = {getSubsystemAddress(), getExtensionAddress()};
        for (int i = 0; i < cleanUp.length; i++) {
            try {
                ModelNode op = Util.createRemoveOperation(cleanUp[i]);
                op.get(OPERATION_HEADERS, ROLLBACK_ON_RUNTIME_FAILURE).set(false);
                executeOp(op, managementClient);
            } catch (IOException e) {
                if (ioe == null) {
                    ioe = e;
                }
            } catch (AssertionError e) {
                if (i > 0 && ae == null) {
                    ae = e;
                } // else ignore because in a failed test SUB may not exist, causing remove to fail
            } catch (RuntimeException e) {
                if (re == null) {
                    re = e;
                }
            }
        }

        if (ioe != null) {
            throw ioe;
        }

        if (ae != null) {
            throw ae;
        }

        if (re != null) {
            throw re;
        }

    }

    @AfterClass
    public static void cleanupExtension() {
        //if (true) return;
        ExtensionUtils.deleteExtensionModule(CustomContextExtension.EXTENSION_NAME);
    }

    protected abstract PathAddress getExtensionAddress();
    protected abstract PathAddress getSubsystemAddress();

    protected abstract ManagementClient getManagementClient();

    @Test
    public void test() throws IOException {
        test(getManagementClient());
    }

    private void test(final ManagementClient managementClient) throws IOException {
        //if (true) return;

        final String urlBase = "http://" + managementClient.getMgmtAddress() + ":9990/";
        final String remapUrl = urlBase + "remap/foo";
        final String badRemapUrl = urlBase + "remap/bad";
        final String staticUrl = urlBase + "static/hello.txt";
        final String badStaticUrl = urlBase + "static/bad.txt";

        // Sanity check

        try (CloseableHttpClient client = HttpClients.createDefault()) {
            HttpResponse resp = client.execute(new HttpGet(remapUrl));
            assertEquals(404, resp.getStatusLine().getStatusCode());
            resp = client.execute(new HttpGet(staticUrl));
            assertEquals(404, resp.getStatusLine().getStatusCode());
        }

        // Add extension and subsystem

        executeOp(Util.createAddOperation(getExtensionAddress()), managementClient);
        executeOp(Util.createAddOperation(getSubsystemAddress()), managementClient);

        // Unauthenticated check

        try (CloseableHttpClient client = HttpClients.createDefault()) {
            HttpResponse resp = client.execute(new HttpGet(remapUrl));
            assertEquals(401, resp.getStatusLine().getStatusCode());
            resp = client.execute(new HttpGet(staticUrl));
            assertEquals(200, resp.getStatusLine().getStatusCode());
        }

        try (CloseableHttpClient client = createAuthenticatingClient(managementClient)) {

            // Authenticated check

            HttpResponse resp = client.execute(new HttpGet(remapUrl));
            assertEquals(200, resp.getStatusLine().getStatusCode());
            ModelNode respNode = ModelNode.fromJSONString(EntityUtils.toString(resp.getEntity()));
            assertEquals(respNode.toString(), CustomContextExtension.EXTENSION_NAME, respNode.get("module").asString());
            assertTrue(respNode.toString(), respNode.hasDefined("subsystem"));
            assertTrue(respNode.toString(), respNode.get("subsystem").has(CustomContextExtension.SUBSYSTEM_NAME));

            resp = client.execute(new HttpGet(staticUrl));
            assertEquals(200, resp.getStatusLine().getStatusCode());
            String text = EntityUtils.toString(resp.getEntity());
            assertTrue(text, text.startsWith("Hello"));

            // POST check
            resp = client.execute(new HttpPost(remapUrl));
            assertEquals(405, resp.getStatusLine().getStatusCode());
            resp = client.execute(new HttpPost(staticUrl));
            assertEquals(200, resp.getStatusLine().getStatusCode());

            // Bad URL check

            resp = client.execute(new HttpGet(badRemapUrl));
            assertEquals(404, resp.getStatusLine().getStatusCode());
            resp = client.execute(new HttpGet(badStaticUrl));
            assertEquals(404, resp.getStatusLine().getStatusCode());

            // Remove subsystem

            executeOp(Util.createRemoveOperation(getSubsystemAddress()), managementClient);

            // Requests fail

            resp = client.execute(new HttpGet(remapUrl));
            assertEquals(404, resp.getStatusLine().getStatusCode());
            resp = client.execute(new HttpGet(staticUrl));
            assertEquals(404, resp.getStatusLine().getStatusCode());
        }
    }

    private static CloseableHttpClient createAuthenticatingClient(ManagementClient managementClient) {
        CredentialsProvider credsProvider = new BasicCredentialsProvider();
        credsProvider.setCredentials(new AuthScope(managementClient.getMgmtAddress(), 9990),
                new UsernamePasswordCredentials(Authentication.USERNAME, Authentication.PASSWORD));

        return HttpClients.custom()
                .setDefaultCredentialsProvider(credsProvider)
                .setMaxConnPerRoute(10)
                .build();
    }

    private static ModelNode executeOp(ModelNode op, ManagementClient managementClient) throws IOException {
        ModelNode response = managementClient.getControllerClient().execute(op);
        assertTrue(response.toString(), response.hasDefined(OUTCOME));
        assertEquals(response.toString(), SUCCESS, response.get(OUTCOME).asString());
        return response;
    }
}
