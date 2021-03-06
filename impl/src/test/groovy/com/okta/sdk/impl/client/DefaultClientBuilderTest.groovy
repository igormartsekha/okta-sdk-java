/*
 * Copyright 2014 Stormpath, Inc.
 * Modifications Copyright 2018 Okta, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.okta.sdk.impl.client

import com.okta.sdk.authc.credentials.TokenClientCredentials
import com.okta.sdk.client.AuthenticationScheme
import com.okta.sdk.client.ClientBuilder
import com.okta.sdk.client.Clients
import com.okta.sdk.impl.io.DefaultResourceFactory
import com.okta.sdk.impl.io.Resource
import com.okta.sdk.impl.io.ResourceFactory
import com.okta.sdk.impl.test.RestoreEnvironmentVariables
import com.okta.sdk.impl.test.RestoreSystemProperties
import com.okta.sdk.impl.util.BaseUrlResolver
import com.okta.sdk.impl.Util
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.testng.annotations.Listeners
import org.testng.annotations.Test

import static org.testng.Assert.*
import static org.mockito.Mockito.*
import static org.hamcrest.Matchers.*
import static org.hamcrest.MatcherAssert.assertThat

@Listeners([RestoreSystemProperties, RestoreEnvironmentVariables])
class DefaultClientBuilderTest {

    /**
     * This method MUST be called from each test in order to work with with the Listeners defined above.
     * If this method is invoked with an @BeforeMethod annotation the Listener will be invoked before and after this
     * method as well.
     */
    void clearOktaEnvAndSysProps() {
        System.clearProperty("okta.client.token")
        System.clearProperty("okta.client.orgUrl")

        RestoreEnvironmentVariables.setEnvironmentVariable("OKTA_CLIENT_TOKEN", null)
        RestoreEnvironmentVariables.setEnvironmentVariable("OKTA_CLIENT_ORGURL", null)
    }

    @Test
    void testBuilder() {
        assertTrue(Clients.builder() instanceof DefaultClientBuilder)
    }

    @Test
    void testConfigureApiKey() {
        clearOktaEnvAndSysProps()
        // remove key.txt from src/test/resources and this test will fail
        def client = new DefaultClientBuilder(noDefaultYamlResourceFactory()).build()
        assertEquals client.dataStore.getClientCredentials().getCredentials(), "13"
    }

    @Test
    void testConfigureBaseProperties() {
        clearOktaEnvAndSysProps()
        def builder = new DefaultClientBuilder(noDefaultYamlResourceFactory())
        DefaultClientBuilder clientBuilder = (DefaultClientBuilder) builder
        assertEquals clientBuilder.clientConfiguration.baseUrl, "https://api.okta.com/v42"
        assertEquals clientBuilder.clientConfiguration.connectionTimeout, 10
        assertEquals clientBuilder.clientConfiguration.authenticationScheme, AuthenticationScheme.SSWS
    }

    @Test
    void testConfigureProxy() {
        clearOktaEnvAndSysProps()
        def builder = Clients.builder()
        DefaultClientBuilder clientBuilder = (DefaultClientBuilder) builder
        assertThat clientBuilder.clientConfiguration.proxyHost, is("proxyyaml") // from yaml
        assertThat clientBuilder.clientConfiguration.proxyPort, is(9009) // from yaml
        assertThat clientBuilder.clientConfiguration.proxyUsername, is("fooyaml") // from yaml
        assertThat clientBuilder.clientConfiguration.proxyPassword, is("bar") // from properties

        def proxy = clientBuilder.clientConfiguration.getProxy()
        assertThat proxy.host, is("proxyyaml")
        assertThat proxy.port, is(9009)
        assertThat proxy.username, is("fooyaml")
        assertThat proxy.password, is("bar")
    }

    @Test
    void testConfigureProxyWithoutPassword() {
        clearOktaEnvAndSysProps()
        def builder = Clients.builder()
        DefaultClientBuilder clientBuilder = (DefaultClientBuilder) builder
        clientBuilder.clientConfiguration.setProxyPassword(null) // override config from properties
        clientBuilder.clientConfiguration.setProxyUsername(null)
        assertThat clientBuilder.clientConfiguration.proxyHost, is("proxyyaml") // from yaml
        assertThat clientBuilder.clientConfiguration.proxyPort, is(9009) // from yaml

        def proxy = clientBuilder.clientConfiguration.getProxy()
        assertThat proxy.host, is("proxyyaml")
        assertThat proxy.port, is(9009)
        assertThat proxy.username, nullValue()
        assertThat proxy.password, nullValue()
    }

    @Test
    void testConfigureBaseUrlResolver(){
        BaseUrlResolver baseUrlResolver = new BaseUrlResolver() {
            @Override
            String getBaseUrl() {
                return "test"
            }
        }

        def testClient = new DefaultClientBuilder().setBaseUrlResolver(baseUrlResolver).build()

        assertEquals(testClient.dataStore.baseUrlResolver.getBaseUrl(), "test")
    }

    @Test
    void testDefaultBaseUrlResolver(){
        clearOktaEnvAndSysProps()
        def client = new DefaultClientBuilder(noDefaultYamlResourceFactory()).build()
        assertEquals(client.dataStore.baseUrlResolver.getBaseUrl(), "https://api.okta.com/v42")
    }

    @Test
    void testNullBaseUrl() {
        clearOktaEnvAndSysProps()
        Util.expect(IllegalArgumentException) {
            new DefaultClientBuilder(noDefaultYamlNoAppYamlResourceFactory())
                .setClientCredentials(new TokenClientCredentials("some-token"))
                .build()
        }
    }

    @Test
    void testHttpBaseUrlForTesting() {
        clearOktaEnvAndSysProps()
        System.setProperty(ClientBuilder.DEFAULT_CLIENT_TESTING_DISABLE_HTTPS_CHECK_PROPERTY_NAME, "true")
        // shouldn't throw IllegalArgumentException
        new DefaultClientBuilder(noDefaultYamlNoAppYamlResourceFactory())
            .setOrgUrl("http://okta.example.com")
            .setClientCredentials(new TokenClientCredentials("some-token"))
            .build()
    }

    @Test
    void testHttpBaseUrlForTestingDisabled() {
        clearOktaEnvAndSysProps()
        System.setProperty(ClientBuilder.DEFAULT_CLIENT_TESTING_DISABLE_HTTPS_CHECK_PROPERTY_NAME, "false")
        Util.expect(IllegalArgumentException) {
            new DefaultClientBuilder(noDefaultYamlNoAppYamlResourceFactory())
                    .setOrgUrl("http://okta.example.com")
                    .setClientCredentials(new TokenClientCredentials("some-token"))
                    .build()
        }
    }

    @Test
    void testNullApiToken() {
        clearOktaEnvAndSysProps()
        Util.expect(IllegalArgumentException) {
            new DefaultClientBuilder(noDefaultYamlNoAppYamlResourceFactory())
                .setOrgUrl("https://okta.example.com")
                .build()
        }
    }

    static ResourceFactory noDefaultYamlNoAppYamlResourceFactory() {
        def resourceFactory = spy(new DefaultResourceFactory())
        doAnswer(new Answer<Resource>() {
            @Override
            Resource answer(InvocationOnMock invocation) throws Throwable {
                String arg = invocation.arguments[0].toString();
                if (arg.endsWith("/.okta/okta.yaml") || arg.equals("classpath:okta.yaml")) {
                    return mock(Resource)
                }
                else {
                    return invocation.callRealMethod()
                }
            }
        })
        .when(resourceFactory).createResource(anyString())

        return resourceFactory
    }

    static ResourceFactory noDefaultYamlResourceFactory() {
        def resourceFactory = spy(new DefaultResourceFactory())
        doAnswer(new Answer<Resource>() {
            @Override
            Resource answer(InvocationOnMock invocation) throws Throwable {
                if (invocation.arguments[0].toString().endsWith("/.okta/okta.yaml")) {
                    return mock(Resource)
                }
                else {
                    return invocation.callRealMethod()
                }
            }
        })
        .when(resourceFactory).createResource(anyString())

        return resourceFactory
    }
}
