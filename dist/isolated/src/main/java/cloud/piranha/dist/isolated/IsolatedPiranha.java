/*
 * Copyright (c) 2002-2025 Manorrock.com. All Rights Reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 *   1. Redistributions of source code must retain the above copyright notice,
 *      this list of conditions and the following disclaimer.
 *   2. Redistributions in binary form must reproduce the above copyright
 *      notice, this list of conditions and the following disclaimer in the
 *      documentation and/or other materials provided with the distribution.
 *   3. Neither the name of the copyright holder nor the names of its
 *      contributors may be used to endorse or promote products derived from
 *      this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package cloud.piranha.dist.isolated;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.lang.System.Logger.Level;
import java.lang.System.Logger;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.importer.ZipImporter;
import org.jboss.shrinkwrap.api.spec.WebArchive;

import cloud.piranha.core.api.Piranha;
import cloud.piranha.core.api.PiranhaConfiguration;
import cloud.piranha.core.impl.DefaultPiranhaConfiguration;
import cloud.piranha.feature.api.FeatureManager;
import cloud.piranha.feature.exitonstop.ExitOnStopFeature;
import cloud.piranha.feature.http.HttpFeature;
import cloud.piranha.feature.https.HttpsFeature;
import cloud.piranha.feature.impl.DefaultFeatureManager;
import cloud.piranha.feature.logging.LoggingFeature;
import cloud.piranha.http.api.HttpServer;
import cloud.piranha.http.webapp.HttpWebApplicationServer;
import cloud.piranha.micro.builder.MicroWebApplication;
import cloud.piranha.micro.loader.MicroConfiguration;
import cloud.piranha.micro.loader.MicroOuterDeployer;

import static java.lang.System.Logger.Level.INFO;
import static java.lang.System.Logger.Level.WARNING;
import java.nio.file.Files;

/**
 * The Isolated of Piranha.
 *
 * @author Manfred Riem (mriem@manorrock.com)
 * @author Arjan Tijms
 */
public class IsolatedPiranha implements Piranha, Runnable {

    /**
     * Stores the logger.
     */
    private static final Logger LOGGER = System.getLogger(IsolatedPiranha.class.getName());

    /**
     * Stores the one and only instance of the server.
     */
    private static IsolatedPiranha theOneAndOnlyInstance;

    /**
     * Stores the configuration.
     */
    private final PiranhaConfiguration configuration;

    /**
     * Stores the feature manager.
     */
    private FeatureManager featureManager;

    /**
     * Stores the HTTP feature.
     */
    private HttpFeature httpFeature;

    /**
     * Stores the HTTPS feature.
     */
    private HttpsFeature httpsFeature;

    /**
     * Stores the HTTP web application server.
     */
    private HttpWebApplicationServer webApplicationServer;

    /**
     * Constructor.
     */
    public IsolatedPiranha() {
        configuration = new DefaultPiranhaConfiguration();
        configuration.setInteger("httpPort", 8080);
        configuration.setInteger("httpsPort", -1);
        featureManager = new DefaultFeatureManager();
    }

    @Override
    public PiranhaConfiguration getConfiguration() {
        return configuration;
    }

    /**
     * Get the only instance.
     *
     * @return the instance.
     */
    public static IsolatedPiranha get() {
        return theOneAndOnlyInstance;
    }

    /**
     * Main method.
     *
     * @param arguments the arguments.
     */
    public static void main(String[] arguments) {
        theOneAndOnlyInstance = new IsolatedPiranha();
        theOneAndOnlyInstance.configuration.setBoolean("exitOnStop", true);
        if (!theOneAndOnlyInstance.processArguments(arguments)) {
            showHelp();
            return;
        }
        theOneAndOnlyInstance.run();
    }

    /**
     * Stores the result of applying an argument.
     *
     * @param <B> the target type.
     * @param builder the updated target.
     * @param advance the number of extra positions to advance i.
     */
    private record ArgumentResult<B>(B builder, int advance) {}

    /**
     * Process the arguments.
     *
     * @param arguments the arguments.
     * @return false if --help was requested, true otherwise.
     */
    private boolean processArguments(String[] arguments) {
        if (arguments != null) {
            final AtomicInteger httpPort = new AtomicInteger(0);
            final AtomicInteger httpsPort = new AtomicInteger(0);
            for (int i = 0; i < arguments.length; i++) {
                if (arguments[i].equals("--help")) {
                    return false;
                }

                Optional<ArgumentResult<PiranhaConfiguration>> result;

                result = applyArgumentWithValueIfPresent(arguments, i, "--http-port", configuration, (c, v) -> {
                    int port = Integer.parseInt(v);
                    httpPort.setPlain(port);
                    c.setInteger("httpPort", port);
                    return c;
                });
                if (result.isEmpty()) {
                    result = applyArgumentWithValueIfPresent(arguments, i, "--https-port", configuration, (c, v) -> {
                        int port = Integer.parseInt(v);
                        httpsPort.setPlain(port);
                        c.setInteger("httpsPort", port);
                        return c;
                    });
                }
                if (result.isEmpty()) {
                    result = applyArgumentWithValueIfPresent(arguments, i, "--http-server-class", configuration, (c, v) -> {
                        c.setString("httpServerClass", v);
                        return c;
                    });
                }
                if (result.isEmpty()) {
                    result = applyArgumentWithValueIfPresent(arguments, i, "--https-server-class", configuration, (c, v) -> {
                        c.setString("httpsServerClass", v);
                        return c;
                    });
                }
                if (result.isEmpty()) {
                    result = applyArgumentWithValueIfPresent(arguments, i, "--logging-level", configuration, (c, v) -> {
                        c.setString("loggingLevel", v);
                        return c;
                    });
                }

                if (result.isPresent()) {
                    i += result.get().advance();
                }
            }
            checkPorts(httpPort.getPlain(), httpsPort.getPlain());
        }
        return true;
    }

    /**
     * Show help.
     */
    private static void showHelp() {
        LOGGER.log(Level.INFO, "");
        LOGGER.log(Level.INFO,
                """
  --help                                          - Show this help
  --http-port <integer>                           - Set the HTTP port
  --http-port=<integer>
  --http-server-class <className>                 - Set the HTTP server class to use
  --http-server-class=<className>
  --https-port <integer>                          - Set the HTTPS port (disabled by default)
  --https-port=<integer>
  --https-server-class <className>                - Set the HTTPS server class to use
  --https-server-class=<className>
  --logging-level <string>                        - Set the java.util.logging.Level
  --logging-level=<string>
                """);
    }

    /**
     * Check the HTTP and HTTPS port.
     *
     * @param httpPort the HTTP port.
     * @param httpsPort the HTTPS port.
     */
    private void checkPorts(int httpPort, int httpsPort) {
        if (httpsPort != 0 && httpPort == httpsPort) {
            LOGGER.log(WARNING, "The http and the https ports are the same. Please use different ports");
            System.exit(-1);
        }
    }

    /**
     * Apply an argument with a value to the target, supporting both "--key value" and "--key=value" forms.
     *
     * @param <B> the target type.
     * @param arguments the arguments.
     * @param i the current index.
     * @param key the argument key.
     * @param target the target object.
     * @param action the action to apply.
     * @return the result, or empty if the key does not match.
     */
    private static <B> Optional<ArgumentResult<B>> applyArgumentWithValueIfPresent(
            String[] arguments, int i, String key, B target, BiFunction<B, String, B> action) {
        if (arguments[i].equals(key)) {
            return Optional.of(new ArgumentResult<>(action.apply(target, arguments[i + 1]), 1));
        }
        String prefix = key + "=";
        if (arguments[i].startsWith(prefix)) {
            return Optional.of(new ArgumentResult<>(action.apply(target, arguments[i].substring(prefix.length())), 0));
        }
        return Optional.empty();
    }

    /**
     * Start method.
     */
    @Override
    public void run() {
        long startTime = System.currentTimeMillis();

        LoggingFeature loggingFeature = new LoggingFeature();
        featureManager.addFeature(loggingFeature);
        loggingFeature.setLevel(configuration.getString("loggingLevel"));
        loggingFeature.init();
        loggingFeature.start();

        LOGGER.log(INFO, () -> "Starting Piranha");

        webApplicationServer = new HttpWebApplicationServer();
        webApplicationServer.start();

        File[] webapps = new File("webapps").listFiles();
        if (webapps != null) {
            if (webapps.length != 0) {
                // Limit threads used by Weld, since default is Runtime.getRuntime().availableProcessors(), which is per deployment.
                int threadsPerApp = Math.max(2, Runtime.getRuntime().availableProcessors() / webapps.length);

                System.setProperty("org.jboss.weld.executor.threadPoolSize", threadsPerApp + "");
            }

            File deployingFile = createDeployingFile();

            Arrays.stream(webapps)
                    .parallel()
                    .filter(warFile -> warFile.getName().toLowerCase().endsWith(".war"))
                    .forEach(warFile -> deploy(warFile, webApplicationServer));

            try {
                Files.delete(deployingFile.toPath());
            } catch (IOException ioe) {
                LOGGER.log(WARNING, "Unable to delete deploying file", ioe);
            }
        }

        HttpServer httpServer = null;

        /*
         * Construct, initialize and start HTTP endpoint (if applicable).
         */
        if (configuration.getInteger("httpPort") > 0) {
            httpFeature = new HttpFeature();
            httpFeature.setHttpServerClass(configuration.getString("httpServerClass"));
            httpFeature.setPort(configuration.getInteger("httpPort"));
            httpFeature.init();
            httpFeature.getHttpServer().setHttpServerProcessor(webApplicationServer);
            httpFeature.start();
            httpServer = httpFeature.getHttpServer();
        }

        /*
         * Construct, initialize and start HTTPS endpoint (if applicable).
         */
        if (configuration.getInteger("httpsPort") > 0) {
            httpsFeature = new HttpsFeature();
            httpsFeature.setHttpsServerClass(configuration.getString("httpsServerClass"));
            httpsFeature.setPort(configuration.getInteger("httpsPort"));
            httpsFeature.init();
            httpsFeature.getHttpsServer().setHttpServerProcessor(webApplicationServer);
            httpsFeature.start();
            if (httpServer == null) {
                httpServer = httpsFeature.getHttpsServer();
            }
        }

        if (configuration.getBoolean("exitOnStop", false)) {
            ExitOnStopFeature exitOnStopFeature = new ExitOnStopFeature();
            featureManager.addFeature(exitOnStopFeature);
            exitOnStopFeature.init();
            exitOnStopFeature.start();
        }

        long finishTime = System.currentTimeMillis();
        LOGGER.log(INFO, "Started Piranha");
        LOGGER.log(INFO, "It took {0} milliseconds", finishTime - startTime);

        File startedFile = createStartedFile();
        File pidFile = new File("tmp/piranha.pid");

        if (httpServer != null) {
            while (httpServer.isRunning()) {
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }

                if (!pidFile.exists()) {
                    webApplicationServer.stop();
                    httpServer.stop();
                    try {
                        Files.delete(startedFile.toPath());
                    } catch (IOException ioe) {
                        LOGGER.log(WARNING, "Unable to delete PID file", ioe);
                    }
                }
            }
        }

        finishTime = System.currentTimeMillis();
        LOGGER.log(INFO, "Stopped Piranha");
        LOGGER.log(INFO, "We ran for {0} milliseconds", finishTime - startTime);

        featureManager.stop();
    }

    private void deploy(File warFile, HttpWebApplicationServer webApplicationServer) {
        String contextPath = getContextPath(warFile);

        MicroConfiguration configuration = new MicroConfiguration();
        configuration.setContextPath(contextPath);
        configuration.setHttpStart(false);

        try {
            MicroWebApplication microWebApplication = new MicroWebApplication();
            microWebApplication.setContextPath(contextPath);
            microWebApplication.setDeployedApplication(
                    new MicroOuterDeployer(configuration.postConstruct())
                            .deploy(ShrinkWrap.create(ZipImporter.class, warFile.getName()).importFrom(warFile).as(WebArchive.class))
                            .getDeployedApplication());

            webApplicationServer.addWebApplication(microWebApplication);
        } catch (Exception e) {
            LOGGER.log(Level.ERROR, () -> "Failed to initialize app " + contextPath, e);
        }
    }

    private String getContextPath(File warFile) {
        String contextPath = warFile.getName().substring(0, warFile.getName().length() - 4);

        if (contextPath.equalsIgnoreCase("ROOT")) {
            contextPath = "";
        } else if (!contextPath.startsWith("/")) {
            contextPath = "/" + contextPath;
        }

        return contextPath;
    }

    private File createDeployingFile() {
        File deployingFile = new File("webapps/deploying");
        try {
            if (!deployingFile.createNewFile()) {
                LOGGER.log(Level.WARNING, "Unable to create deploying file");
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "I/O error occurred creating deploying file", e);
        }
        return deployingFile;
    }

    private File createStartedFile() {
        File startedFile = new File("webapps/started");

        try {
            if (!startedFile.createNewFile()) {
                LOGGER.log(Level.WARNING, "Unable to create started file");
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "I/O error occurred creating started file", e);
        }

        return startedFile;
    }
}
