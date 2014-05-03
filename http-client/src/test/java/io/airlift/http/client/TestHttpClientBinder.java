/*
 * Copyright 2010 Proofpoint, Inc.
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
package io.airlift.http.client;

import com.google.common.collect.ImmutableList;
import com.google.inject.Binder;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.Module;
import io.airlift.bootstrap.Bootstrap;
import io.airlift.bootstrap.LifeCycleManager;
import io.airlift.http.client.AsyncHttpClientModule.JettyIoPoolManager;
import io.airlift.http.client.HttpClientBinder.HttpClientBindingBuilder;
import io.airlift.http.client.jetty.JettyHttpClient;
import io.airlift.tracetoken.TraceTokenModule;
import org.testng.annotations.Test;

import javax.inject.Qualifier;

import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import static com.google.inject.name.Names.named;
import static io.airlift.http.client.CompositeQualifierImpl.compositeQualifier;
import static io.airlift.http.client.HttpClientBinder.httpClientBinder;
import static io.airlift.testing.Assertions.assertInstanceOf;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNotSame;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;

public class TestHttpClientBinder
{
    @Test
    public void testBindingMultipleFiltersAndClients()
            throws Exception
    {
        Injector injector = new Bootstrap(
                new Module()
                {
                    @Override
                    public void configure(Binder binder)
                    {
                        httpClientBinder(binder).bindHttpClient("foo", FooClient.class)
                                .withFilter(TestingRequestFilter.class)
                                .withFilter(AnotherHttpRequestFilter.class)
                                .withTracing();

                        HttpClientBindingBuilder builder = httpClientBinder(binder).bindHttpClient("bar", named("bar"));
                        builder.withFilter(TestingRequestFilter.class);
                        builder.addFilterBinding().to(AnotherHttpRequestFilter.class);
                    }
                },
                new TraceTokenModule())
                .quiet()
                .strictConfig()
                .initialize();

        assertFilterCount(injector.getInstance(Key.get(HttpClient.class, FooClient.class)), 3);
        assertFilterCount(injector.getInstance(Key.get(HttpClient.class, named("bar"))), 2);

        // a pool should not be registered for this Foo
        assertNull(injector.getExistingBinding(Key.get(JettyIoPoolManager.class, filterQualifier(FooClient.class))));
        assertNull(injector.getExistingBinding(Key.get(JettyIoPoolManager.class, named("bar"))));

        assertPoolsDestroyProperly(injector);
    }

    @Test
    public void testBindAsyncClientWithFilter()
            throws Exception
    {
        Injector injector = new Bootstrap(
                new Module()
                {
                    @Override
                    public void configure(Binder binder)
                    {
                        httpClientBinder(binder).bindAsyncHttpClient("foo", FooClient.class)
                                .withFilter(TestingRequestFilter.class)
                                .withFilter(AnotherHttpRequestFilter.class)
                                .withTracing();
                    }
                },
                new TraceTokenModule())
                .quiet()
                .strictConfig()
                .initialize();


        HttpClient httpClient = injector.getInstance(Key.get(HttpClient.class, FooClient.class));
        AsyncHttpClient asyncHttpClient = injector.getInstance(Key.get(AsyncHttpClient.class, FooClient.class));
        assertSame(httpClient, asyncHttpClient);
        assertFilterCount(httpClient, 3);
        assertFilterCount(asyncHttpClient, 3);

        // a pool should not be registered for this Foo
        assertNull(injector.getExistingBinding(Key.get(JettyIoPoolManager.class, filterQualifier(FooClient.class))));
        assertNull(injector.getExistingBinding(Key.get(JettyIoPoolManager.class, filterQualifier(BarClient.class))));

        assertPoolsDestroyProperly(injector);
    }

    @Test
    public void testWithoutFilters()
            throws Exception
    {
        Injector injector = new Bootstrap(
                new Module()
                {
                    @Override
                    public void configure(Binder binder)
                    {
                        httpClientBinder(binder).bindHttpClient("foo", FooClient.class);
                    }
                })
                .quiet()
                .strictConfig()
                .initialize();

        assertNotNull(injector.getInstance(Key.get(HttpClient.class, FooClient.class)));

        // a pool should not be registered for this Foo
        assertNull(injector.getExistingBinding(Key.get(JettyIoPoolManager.class, filterQualifier(FooClient.class))));

        assertPoolsDestroyProperly(injector);
    }

    @Test
    public void testAliases()
            throws Exception
    {
        Injector injector = new Bootstrap(
                new Module()
                {
                    @Override
                    public void configure(Binder binder)
                    {
                        httpClientBinder(binder).bindHttpClient("foo", FooClient.class)
                                .withAlias(FooAlias1.class)
                                .withAliases(ImmutableList.of(FooAlias2.class, FooAlias3.class))
                                .withAlias(named("fooAlias4"));
                    }
                }
        )
                .quiet()
                .strictConfig()
                .initialize();

        HttpClient client = injector.getInstance(Key.get(HttpClient.class, FooClient.class));
        assertSame(injector.getInstance(Key.get(HttpClient.class, FooAlias1.class)), client);
        assertSame(injector.getInstance(Key.get(HttpClient.class, FooAlias2.class)), client);
        assertSame(injector.getInstance(Key.get(HttpClient.class, FooAlias3.class)), client);
        assertSame(injector.getInstance(Key.get(HttpClient.class, named("fooAlias4"))), client);

        // a private pool should not be registered for these clients
        assertNull(injector.getExistingBinding(Key.get(JettyIoPoolManager.class, filterQualifier(FooClient.class))));
        assertNull(injector.getExistingBinding(Key.get(JettyIoPoolManager.class, filterQualifier(FooAlias1.class))));
        assertNull(injector.getExistingBinding(Key.get(JettyIoPoolManager.class, filterQualifier(FooAlias2.class))));
        assertNull(injector.getExistingBinding(Key.get(JettyIoPoolManager.class, filterQualifier(FooAlias3.class))));
        assertNull(injector.getExistingBinding(Key.get(JettyIoPoolManager.class, named("fooAlias4"))));

        assertPoolsDestroyProperly(injector);
    }

    @Test
    public void testAliasesBoundWithAnnotation()
            throws Exception
    {
        Injector injector = new Bootstrap(
                new Module()
                {
                    @Override
                    public void configure(Binder binder)
                    {
                        httpClientBinder(binder).bindHttpClient("foo", named("foo"))
                                .withAlias(FooAlias1.class)
                                .withAliases(ImmutableList.of(FooAlias2.class, FooAlias3.class))
                                .withAlias(named("fooAlias4"));
                    }
                })
                .quiet()
                .strictConfig()
                .initialize();

        HttpClient client = injector.getInstance(Key.get(HttpClient.class, named("foo")));
        assertSame(injector.getInstance(Key.get(HttpClient.class, FooAlias1.class)), client);
        assertSame(injector.getInstance(Key.get(HttpClient.class, FooAlias2.class)), client);
        assertSame(injector.getInstance(Key.get(HttpClient.class, FooAlias3.class)), client);
        assertSame(injector.getInstance(Key.get(HttpClient.class, named("fooAlias4"))), client);

        // a private pool should not be registered for these clients
        assertNull(injector.getExistingBinding(Key.get(JettyIoPoolManager.class, named("foo"))));
        assertNull(injector.getExistingBinding(Key.get(JettyIoPoolManager.class, filterQualifier(FooAlias1.class))));
        assertNull(injector.getExistingBinding(Key.get(JettyIoPoolManager.class, filterQualifier(FooAlias2.class))));
        assertNull(injector.getExistingBinding(Key.get(JettyIoPoolManager.class, filterQualifier(FooAlias3.class))));
        assertNull(injector.getExistingBinding(Key.get(JettyIoPoolManager.class, named("fooAlias4"))));

        assertPoolsDestroyProperly(injector);
    }

    @Test
    public void testBindAsyncClientWithAliases()
            throws Exception
    {
        Injector injector = new Bootstrap(
                new Module()
                {
                    @Override
                    public void configure(Binder binder)
                    {
                        httpClientBinder(binder).bindAsyncHttpClient("foo", FooClient.class)
                                .withAlias(FooAlias1.class)
                                .withAlias(FooAlias2.class)
                                .withAlias(named("alias4"));
                    }
                })
                .quiet()
                .strictConfig()
                .initialize();

        AsyncHttpClient client = injector.getInstance(Key.get(AsyncHttpClient.class, FooClient.class));
        assertSame(injector.getInstance(Key.get(AsyncHttpClient.class, FooAlias1.class)), client);
        assertSame(injector.getInstance(Key.get(AsyncHttpClient.class, FooAlias2.class)), client);

        assertSame(injector.getInstance(Key.get(HttpClient.class, FooClient.class)), client);
        assertSame(injector.getInstance(Key.get(HttpClient.class, FooAlias1.class)), client);
        assertSame(injector.getInstance(Key.get(HttpClient.class, FooAlias2.class)), client);

        // a private pool should not be registered for these clients
        assertNull(injector.getExistingBinding(Key.get(JettyIoPoolManager.class, filterQualifier(FooClient.class))));
        assertNull(injector.getExistingBinding(Key.get(JettyIoPoolManager.class, filterQualifier(FooAlias1.class))));
        assertNull(injector.getExistingBinding(Key.get(JettyIoPoolManager.class, filterQualifier(FooAlias2.class))));
        assertNull(injector.getExistingBinding(Key.get(JettyIoPoolManager.class, named("alias4"))));

        assertPoolsDestroyProperly(injector);
    }

    @Test
    public void testMultipleAsyncClients()
            throws Exception
    {
        Injector injector = new Bootstrap(
                new Module()
                {
                    @Override
                    public void configure(Binder binder)
                    {
                        httpClientBinder(binder).bindAsyncHttpClient("foo", FooClient.class);
                        httpClientBinder(binder).bindAsyncHttpClient("bar", BarClient.class);
                    }
                })
                .quiet()
                .strictConfig()
                .initialize();

        AsyncHttpClient fooClient = injector.getInstance(Key.get(AsyncHttpClient.class, FooClient.class));
        AsyncHttpClient barClient = injector.getInstance(Key.get(AsyncHttpClient.class, BarClient.class));
        assertNotSame(fooClient, barClient);

        // a private pool should not be registered for these clients
        assertNull(injector.getExistingBinding(Key.get(JettyIoPoolManager.class, filterQualifier(FooClient.class))));
        assertNull(injector.getExistingBinding(Key.get(JettyIoPoolManager.class, filterQualifier(BarClient.class))));

        assertPoolsDestroyProperly(injector);
    }

    @Test
    public void testPrivateThreadPool()
            throws Exception
    {
        Injector injector = new Bootstrap(
                new Module()
                {
                    @Override
                    public void configure(Binder binder)
                    {
                        binder.requireExplicitBindings();
                        binder.disableCircularProxies();
                        httpClientBinder(binder).bindAsyncHttpClient("foo", FooClient.class).withPrivateIoThreadPool();
                    }
                })
                .quiet()
                .strictConfig()
                .initialize();

        AsyncHttpClient fooClient = injector.getInstance(Key.get(AsyncHttpClient.class, FooClient.class));
        assertNotNull(fooClient);

        assertPrivatePools(injector, filterQualifier(FooClient.class));

        assertPoolsDestroyProperly(injector, filterQualifier(FooClient.class));
    }

    @Test
    public void testMultiplePrivateThreadPools()
            throws Exception
    {
        Injector injector = new Bootstrap(
                new Module()
                {
                    @Override
                    public void configure(Binder binder)
                    {
                        httpClientBinder(binder).bindHttpClient("foo", FooClient.class).withPrivateIoThreadPool();
                        httpClientBinder(binder).bindHttpClient("bar", named("bar")).withPrivateIoThreadPool();
                    }
                })
                .quiet()
                .strictConfig()
                .initialize();

        HttpClient fooClient = injector.getInstance(Key.get(HttpClient.class, FooClient.class));
        HttpClient barClient = injector.getInstance(Key.get(HttpClient.class, named("bar")));
        assertNotSame(fooClient, barClient);

        assertPrivatePools(injector, filterQualifier(FooClient.class), named("bar"));

        assertPoolsDestroyProperly(injector, filterQualifier(FooClient.class), named("bar"));
    }

    @Test
    public void testMultiplePrivateAndSharedThreadPools()
            throws Exception
    {
        Injector injector = new Bootstrap(
                new Module()
                {
                    @Override
                    public void configure(Binder binder)
                    {
                        httpClientBinder(binder).bindAsyncHttpClient("foo", FooClient.class);
                        httpClientBinder(binder).bindAsyncHttpClient("bar", BarClient.class).withPrivateIoThreadPool();
                    }
                })
                .quiet()
                .strictConfig()
                .initialize();

        AsyncHttpClient fooClient = injector.getInstance(Key.get(AsyncHttpClient.class, FooClient.class));
        AsyncHttpClient barClient = injector.getInstance(Key.get(AsyncHttpClient.class, BarClient.class));
        assertNotSame(fooClient, barClient);

        // a pool should not be registered for this Foo
        assertNull(injector.getExistingBinding(Key.get(JettyIoPoolManager.class, FooClient.class)));

        assertPrivatePools(injector, filterQualifier(BarClient.class));

        assertPoolsDestroyProperly(injector, filterQualifier(BarClient.class));
    }

    private static void assertPrivatePools(Injector injector, Annotation... privateClientAnnotations)
            throws Exception
    {
        JettyIoPoolManager sharedPool = injector.getInstance(Key.get(JettyIoPoolManager.class));
        // pool should not be destroyed yet
        assertFalse(sharedPool.isDestroyed());

        Set<JettyIoPoolManager> privatePools = Collections.newSetFromMap(new IdentityHashMap<JettyIoPoolManager, Boolean>());
        for (Annotation privateClientAnnotation : privateClientAnnotations) {
            assertNotNull(injector.getExistingBinding(Key.get(JettyIoPoolManager.class, privateClientAnnotation)));
            JettyIoPoolManager privatePool = injector.getInstance(Key.get(JettyIoPoolManager.class, privateClientAnnotation));

            // pool should not be the same as any other pool
            assertNotSame(privatePool, sharedPool);
            assertFalse(privatePools.contains(privatePool));
            privatePools.add(privatePool);
        }
    }

    private static void assertPoolsDestroyProperly(Injector injector, Annotation... privateClientAnnotations)
            throws Exception
    {
        JettyIoPoolManager sharedPool = injector.getInstance(Key.get(JettyIoPoolManager.class));
        assertFalse(sharedPool.isDestroyed());

        Set<JettyIoPoolManager> privatePools = Collections.newSetFromMap(new IdentityHashMap<JettyIoPoolManager, Boolean>());
        for (Annotation privateClientAnnotation : privateClientAnnotations) {
            JettyIoPoolManager privatePool = injector.getInstance(Key.get(JettyIoPoolManager.class, privateClientAnnotation));
            assertFalse(privatePool.isDestroyed());
        }

        injector.getInstance(LifeCycleManager.class).stop();

        assertTrue(sharedPool.isDestroyed());
        for (JettyIoPoolManager privatePool : privatePools) {
            assertTrue(privatePool.isDestroyed());
        }
    }

    private static void assertFilterCount(HttpClient httpClient, int filterCount)
    {
        assertNotNull(httpClient);
        assertInstanceOf(httpClient, JettyHttpClient.class);
        assertEquals(((JettyHttpClient) httpClient).getRequestFilters().size(), filterCount);
    }

    private static CompositeQualifier filterQualifier(Class<? extends Annotation> annotation)
    {
        return compositeQualifier(annotation, AsyncHttpClient.class);
    }

    @Retention(RUNTIME)
    @Target({ElementType.PARAMETER})
    @Qualifier
    public @interface FooClient
    {
    }

    @Retention(RUNTIME)
    @Target({ElementType.PARAMETER})
    @Qualifier
    public @interface FooAlias1
    {
    }

    @Retention(RUNTIME)
    @Target({ElementType.PARAMETER})
    @Qualifier
    public @interface FooAlias2
    {
    }

    @Retention(RUNTIME)
    @Target({ElementType.PARAMETER})
    @Qualifier
    public @interface FooAlias3
    {
    }

    @Retention(RUNTIME)
    @Target({ElementType.PARAMETER})
    @Qualifier
    public @interface BarClient
    {
    }

    public static class AnotherHttpRequestFilter
            implements HttpRequestFilter
    {
        @Override
        public Request filterRequest(Request request)
        {
            return request;
        }
    }
}
