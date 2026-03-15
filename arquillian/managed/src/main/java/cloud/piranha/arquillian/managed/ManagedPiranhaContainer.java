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
package cloud.piranha.arquillian.managed;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.time.Duration;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.jboss.arquillian.container.spi.client.container.DeployableContainer;
import org.jboss.arquillian.container.spi.client.container.DeploymentException;
import org.jboss.arquillian.container.spi.client.protocol.ProtocolDescription;
import org.jboss.arquillian.container.spi.client.protocol.metadata.HTTPContext;
import org.jboss.arquillian.container.spi.client.protocol.metadata.ProtocolMetaData;
import org.jboss.arquillian.container.spi.client.protocol.metadata.Servlet;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.exporter.ZipExporter;

import static java.lang.System.Logger.Level.INFO;
import static java.lang.System.Logger.Level.WARNING;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

/**
 * The Managed Piranha container.
 *
 * @author Manfred Riem (mriem@manorrock.com)
 */
public class ManagedPiranhaContainer implements DeployableContainer<ManagedPiranhaContainerConfiguration> {

    /**
     * Stores the logger.
     */
    private static final System.Logger LOGGER = System.getLogger(ManagedPiranhaContainer.class.getName());

    /**
     * Stores the PID filename.
     */
    private static final String PID_FILENAME = "tmp/piranha.pid";

    /**
     * Stores the java.io.tmpdir constant.
     */
    private static final String TMP_DIR = "java.io.tmpdir";

    /**
     * Stores the 'Unable to create directories' message.
     */
    private static final String UNABLE_TO_CREATE_DIRECTORIES = "Unable to create directories";

    /**
     * Stores the configuration.
     */
    private ManagedPiranhaContainerConfiguration configuration;

    /**
     * Stores the local repository directory.
     */
    private File localRepositoryDir = new File(System.getProperty("user.home"), ".m2/repository");

    /**
     * Stores the Piranha process per deployment (keyed by app name).
     */
    private final java.util.Map<String, Process> processes = new java.util.LinkedHashMap<>();

    /**
     * Stores the HTTP port per deployment (keyed by app name).
     */
    private final java.util.Map<String, Integer> deploymentPorts = new java.util.LinkedHashMap<>();

    /**
     * Stores the Piranha instance log file for the current deployment.
     * Set during {@link #startPiranha} so it can be surfaced after undeploy.
     */
    private File logFile;

    /**
     * Default constructor.
     */
    public ManagedPiranhaContainer() {
    }

    @Override
    public Class<ManagedPiranhaContainerConfiguration> getConfigurationClass() {
        return ManagedPiranhaContainerConfiguration.class;
    }

    @Override
    public void setup(ManagedPiranhaContainerConfiguration configuration) {
        this.configuration = configuration;
    }

    @SuppressWarnings("exports")
    @Override
    public ProtocolDescription getDefaultProtocol() {
        return new ProtocolDescription(configuration.getProtocol());
    }

    @SuppressWarnings("exports")
    @Override
    public ProtocolMetaData deploy(Archive<?> archive) throws DeploymentException {
        LOGGER.log(INFO, "Deploying " + archive.getName());

        ProtocolMetaData metadata = new ProtocolMetaData();

        try {
            String warFileName = toWarFilename(archive);
            String appName = toAppName(warFileName);

            /*
             * Export the Archive into a WAR file.
             */
            File runtimeDirectory = new File(System.getProperty(TMP_DIR), appName);
            runtimeDirectory.mkdirs();

            File warFile = new File(runtimeDirectory, warFileName);
            archive.as(ZipExporter.class).exportTo(warFile, true);

            /*
             * Copy runtime JAR into the runtime directory.
             */
            String version = determineVersionToUse();
            File piranhaJarFile = getPiranhaJarFile(version);
            copyPiranhaJarFile(runtimeDirectory, piranhaJarFile);

            /*
             * Use the configured port for the first deployment; fall back to a
             * free port for any subsequent deployment so we do not kill the
             * already-running Piranha process.
             *
             * Workaround for TCK locator test classes (e.g.
             * ee.jakarta.tck.ws.rs.ee.rs.cookieparam.locator.JAXRSLocatorClientIT)
             * that inherit a second @Deployment(testable=false) from their parent
             * (e.g. JAXRSClientIT). Arquillian finds both @Deployment methods in
             * the class hierarchy and deploys both WARs into the same container.
             * The test only contacts the locator WAR on the configured port; the
             * inherited WAR is dead weight and just needs to land somewhere.
             */
            int port = deploymentPorts.containsValue(configuration.getHttpPort())
                    ? me.alexpanov.net.FreePortFinder.findFreeLocalPort()
                    : configuration.getHttpPort();
            deploymentPorts.put(appName, port);
            startPiranha(runtimeDirectory, warFile, port);
        } catch (IOException ioe) {
            ioe.printStackTrace();
            return null;
        }

        String appName = toAppName(toWarFilename(archive));
        int port = deploymentPorts.getOrDefault(appName, configuration.getHttpPort());
        HTTPContext httpContext = new HTTPContext("localhost", port);
        httpContext.add(new Servlet(
                "ArquillianServletRunnerEE9",
                archive.getName().substring(0, archive.getName().lastIndexOf("."))));
        metadata.addContext(httpContext);
        return metadata;
    }

    @Override
    public void undeploy(Archive<?> archive) throws DeploymentException {
        LOGGER.log(INFO, "Undeploying " + archive.getName());

        String appName = toAppName(archive);
        Process proc = processes.get(appName);
        if (proc == null) {
            return;
        }

        /*
         * Delete the PID file to trigger Piranha shutdown.
         */
        File runtimeDirectory = new File(System.getProperty(TMP_DIR), appName);
        File pidFile = new File(runtimeDirectory, PID_FILENAME);
        if (pidFile.exists()) {
            try {
                Files.delete(pidFile.toPath());
            } catch (IOException ioe) {
                LOGGER.log(WARNING, "Error deleting PID file", ioe);
            }
        }

        /*
         * Wait for the Piranha process to exit.
         */
        if (proc.isAlive()) {
            try {
                LOGGER.log(INFO, "Waiting for Piranha to be shutdown");

                long startTime = System.currentTimeMillis();
                proc.waitFor(30, TimeUnit.SECONDS);
                long finishTime = System.currentTimeMillis();

                LOGGER.log(INFO, "Piranha has shutdown\n It took {0} milliseconds", finishTime - startTime);
            } catch (InterruptedException ie) {
                LOGGER.log(WARNING, "Piranha did not shutdown within time alloted");
                LOGGER.log(WARNING, "Destroying Piranha process forcibly");
                proc.destroyForcibly();
            }
        }

        processes.remove(appName);
        deploymentPorts.remove(appName);

        /*
         * Log the Piranha instance output to help diagnose test failures.
         */
        if (logFile != null && logFile.exists()) {
            try {
                LOGGER.log(INFO, "Piranha log ({0}):\n{1}", logFile.getAbsolutePath(), Files.readString(logFile.toPath()));
            } catch (IOException ioe) {
                LOGGER.log(WARNING, "Could not read Piranha log file: {0}", ioe.getMessage());
            }
        }

        /*
         * Delete the WAR file.
         */
        File warFile = new File(runtimeDirectory, toWarFilename(archive));
        warFile.delete();
    }

    /**
     * Creates a WAR filename for the archive.
     *
     * @param archive the archive.
     * @return the WAR filename.
     */
    private String toWarFilename(Archive<?> archive) {
        String warFilename = archive.getName();

        if (isEmpty(archive.getName())) {
            warFilename = "ROOT.war";
        }

        return warFilename;
    }

    private String toAppName(String warFileName) {
        return warFileName.substring(0, warFileName.lastIndexOf("."));
    }

    private String toAppName(Archive<?> archive) {
        return toAppName(toWarFilename(archive));
    }

    /**
     * Get the Piranha JAR file.
     *
     * @param version the version.
     * @return the zip file.
     * @throws IOException when an I/O error occurs.
     */
    private File getPiranhaJarFile(String version) throws IOException {
        URL downloadUrl = createMavenCentralArtifactUrl(
                "cloud.piranha.dist",
                "piranha-dist-" + configuration.getDistribution(),
                version,
                "jar"
        );

        String artifactPath = createArtifactPath(
                "cloud.piranha.dist",
                "piranha-dist-" + configuration.getDistribution(),
                version,
                "jar"
        );

        File zipFile = new File(localRepositoryDir, artifactPath);
        if (!zipFile.exists() && !zipFile.getParentFile().mkdirs()) {
            LOGGER.log(WARNING, UNABLE_TO_CREATE_DIRECTORIES);
        }

        try (InputStream inputStream = downloadUrl.openStream()) {
            Files.copy(inputStream,
                    zipFile.toPath(),
                    REPLACE_EXISTING);
        } catch (IOException fnfe) {
            LOGGER.log(WARNING, "Could not download JAR file, defaulting back to local Maven repository");
        }

        return new File(localRepositoryDir, artifactPath);
    }

    /**
     * Create artifact path.
     *
     * @param groupId the groupId.
     * @param artifactId the artifactId.
     * @param version the version
     * @param type the type.
     */
    private String createArtifactPath(String groupId, String artifactId, String version, String type) {
        String artifactPathFormat = "%s/%s/%s/%s-%s.%s";
        return String.format(artifactPathFormat,
                convertGroupIdToPath(groupId),
                artifactId,
                version,
                artifactId,
                version,
                type.toLowerCase());
    }

    /**
     * Convert the groupId to path.
     *
     * @param groupId the groupId.
     * @return the path.
     */
    private String convertGroupIdToPath(String groupId) {
        return groupId.replace('.', '/');
    }

    /**
     * Create the Maven central artifact URL
     *
     * @param groupId the groupId.
     * @param artifactId the artifactId.
     * @param version the version
     * @param type the type.
     * @return the URL.
     * @throws IOException when an I/O error occurs.
     */
    @SuppressWarnings("deprecation")
    private URL createMavenCentralArtifactUrl(String groupId, String artifactId, String version, String type) throws IOException {
        return new URL("https://repo1.maven.org/maven2/" + createArtifactPath(groupId, artifactId, version, type));
    }

    /**
     * Determine what version of Piranha to use.
     *
     * @return the version.
     */
    private String determineVersionToUse() {
        return getClass().getPackage().getImplementationVersion();
    }

    /**
     * Kill any process currently listening on the given port.
     *
     * @param port the port to free up.
     */
    private void killProcessOnPort(int port) {
        try {
            ProcessBuilder finder = new ProcessBuilder(
                    "sh", "-c",
                    "lsof -ti tcp:" + port + " -sTCP:LISTEN");
            finder.redirectErrorStream(true);
            Process findProcess = finder.start();
            String pids = new String(findProcess.getInputStream().readAllBytes()).trim();
            findProcess.waitFor(5, TimeUnit.SECONDS);
            if (!pids.isEmpty()) {
                for (String pid : pids.split("\\s+")) {
                    if (!pid.isEmpty()) {
                        LOGGER.log(WARNING, "Port {0} is still in use by PID {1}, killing it", port, pid);
                        new ProcessBuilder("kill", "-9", pid)
                                .start()
                                .waitFor(5, TimeUnit.SECONDS);
                    }
                }
                // Brief pause so the OS releases the port
                Thread.sleep(200);
            }
        } catch (IOException | InterruptedException e) {
            LOGGER.log(WARNING, "Could not clean up port {0}: {1}", port, e.getMessage());
        }
    }

    /**
     * Start Piranha.
     *
     * @param runtimeDirectory the runtime directory.
     * @param warFile the WAR filename.
     * @param httpPort the HTTP port to use for this deployment.
     */
    private void startPiranha(File runtimeDirectory, File warFile, int httpPort) throws IOException, DeploymentException {
        killProcessOnPort(httpPort);
        File stalePidFile = new File(runtimeDirectory, PID_FILENAME);
        if (stalePidFile.exists()) {
            try {
                Files.delete(stalePidFile.toPath());
            } catch (IOException ioe) {
                LOGGER.log(WARNING, "Could not delete stale PID file: {0}", ioe.getMessage());
            }
        }
        List<String> commands = new ArrayList<>();
        StringBuilder classpath = new StringBuilder();
        commands.add("java");
        String[] jvmArgs = configuration.getJvmArguments().split("\\s+");
        if (jvmArgs.length > 0) {
            for(int i=0; i<jvmArgs.length; i++) {
                if (jvmArgs[i] != null && !jvmArgs[i].trim().equals("")) {
                    commands.add(jvmArgs[i]);
                }
                if (jvmArgs[i] != null && jvmArgs[i].trim().equals("-cp")) {
                    // ignore this one.
                }
                if (i > 0 && jvmArgs[i] != null && jvmArgs[i-1].trim().equals("-cp")) {
                    classpath.append(jvmArgs[i].trim()).append(File.pathSeparatorChar);
                }
            }
        }

        if (!configuration.getCallerName().isEmpty()) {
            commands.add("-Dio.piranha.identitystore.callers=<callers><caller callername=\""
                    + configuration.getCallerName() + "\" password=\""
                    + configuration.getCallerPassword() + "\" groups=\""
                    + configuration.getCallerGroups() + "\"/></callers>");
        }

        if (configuration.isDebug()) {
            commands.add("-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=localhost:9009");
        }

        if (configuration.isSuspend()) {
            commands.add("-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=localhost:9009");
        }

        if (classpath.isEmpty()) {
            commands.add("-jar");
            commands.add("piranha-" + configuration.getDistribution() + ".jar");
        } else {
            commands.add("-cp");
            commands.add(classpath.toString() + "piranha-" + configuration.getDistribution() + ".jar");
            if (configuration.getDistribution().equals("coreprofile")) {
                commands.add("cloud.piranha.dist.coreprofile.CoreProfilePiranhaMain");
            }
            if (configuration.getDistribution().equals("webprofile")) {
                commands.add("cloud.piranha.dist.webprofile.WebProfilePiranhaMain");
            }
        }
        commands.add("--http-port");
        commands.add(Integer.toString(httpPort));
        commands.add("--war-file");
        commands.add(warFile.getName());
        commands.add("--write-pid");

        String appName = toAppName(warFile.getName());
        String appURL = "http://localhost:" + Integer.toString(httpPort) + "/" + appName;
        File logFile = new File(runtimeDirectory, appName + ".log");

        LOGGER.log(INFO,
            """


            Starting Piranha

            Classpath:  {0}
            Directory:  {1}
            Log:        {2}
            URL:        {3}


            """,

            classpath.toString(),
            runtimeDirectory,
            logFile.getAbsolutePath(),
            appURL);

        Process proc = new ProcessBuilder()
                .directory(runtimeDirectory)
                .command(commands)
                .redirectErrorStream(true)
                .redirectOutput(logFile)
                .start();
        processes.put(appName, proc);

        File pidFile = new File(runtimeDirectory, PID_FILENAME);
        int count = 0;
        LOGGER.log(INFO, "Waiting for Piranha to be ready");
        while (!pidFile.exists() && proc.isAlive()) {
            try {
                Thread.sleep(100);
                count++;
            } catch (InterruptedException ie) {
            }

            if (configuration.isSuspend()) {
                if (count % 500 == 0) {
                    LOGGER.log(INFO, "Still waiting (infinite, because suspend on port 9009)");
                }
                continue;
            }

            if (count % 20 == 0) {
                LOGGER.log(INFO, "Still waiting... ({0} of {1})", (count / 20), (1200 / 20));
            }

            if (count == 1200) {
                LOGGER.log(WARNING, "Warning, PID file not seen!");
                break;
            }
        }

        if (!proc.isAlive()) {
            LOGGER.log(WARNING, "Piranha terminated during startup.");

            String msg = "Cannot start Piranha. \n";
            if (logFile.exists()) {
                msg += Files.readString(logFile.toPath());
            }

            throw new DeploymentException(msg);
        }

        /*
         * HTTP probe: if Piranha returns 503, the WAR failed to deploy.
         * The response body carries the deployment exception message.
         */
        try {
            HttpClient httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();
            HttpRequest probeRequest = HttpRequest.newBuilder()
                    .uri(URI.create(appURL))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> probeResponse = httpClient.send(
                    probeRequest, HttpResponse.BodyHandlers.ofString());
            if (probeResponse.statusCode() == 503) {
                throw new DeploymentException(probeResponse.body());
            }
        } catch (DeploymentException de) {
            throw de;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            LOGGER.log(WARNING, "HTTP probe interrupted");
        } catch (Exception e) {
            LOGGER.log(WARNING, "HTTP probe failed, proceeding: {0}", e.getMessage());
        }

        LOGGER.log(INFO,
            "\n" +
            "Application is available at: " + appURL);
    }

    /**
     * Copy the Piranha JAR file.
     *
     * @param runtimeDirectory the runtime directory.
     * @param zipFile the zip file.
     */
    private void copyPiranhaJarFile(File runtimeDirectory, File zipFile) throws IOException {
        if (!runtimeDirectory.exists() && !runtimeDirectory.mkdirs()) {
            System.err.println(UNABLE_TO_CREATE_DIRECTORIES);
        }

        Files.copy(zipFile.toPath(),
                Path.of(runtimeDirectory + "/piranha-" + configuration.getDistribution() + ".jar"),
                REPLACE_EXISTING);
    }

    /**
     * Is the string null or empty.
     *
     * @param string the string
     * @return true if it is, false otherwise.
     */
    private boolean isEmpty(String string) {
        return string == null || string.isEmpty();
    }
}
