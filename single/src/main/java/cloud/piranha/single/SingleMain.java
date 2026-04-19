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
package cloud.piranha.single;

import static java.lang.System.Logger.Level.WARNING;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

/**
 * The Single version of Main.
 *
 * @author Manfred Riem (mriem@manorrock.com)
 */
public class SingleMain {

    /**
     * Stores the logger.
     */
    private static final Logger LOGGER = System.getLogger(SingleMain.class.getName());

    /**
     * Constructor.
     */
    public SingleMain() {
    }

    /**
     * Main method.
     *
     * @param arguments the arguments.
     */
    public static void main(String[] arguments) {
        SinglePiranhaBuilder builder = new SingleMain().processArguments(arguments);
        if (builder != null) {
            builder.build().start();
        } else {
            showHelp();
        }
    }

    /**
     * Stores the result of applying an argument.
     *
     * @param <B> the builder type.
     * @param builder the updated builder.
     * @param advance the number of extra positions to advance i.
     */
    private record ArgumentResult<B>(B builder, int advance) {}

    /**
     * Process the arguments.
     *
     * @param arguments the arguments.
     * @return this.
     */
    protected SinglePiranhaBuilder processArguments(String[] arguments) {
        SinglePiranhaBuilder builder = new SinglePiranhaBuilder()
                .exitOnStop(true);
        final AtomicInteger httpPort = new AtomicInteger(0);
        final AtomicInteger httpsPort = new AtomicInteger(0);
        if (arguments != null) {
            for (int i = 0; i < arguments.length; i++) {
                if (arguments[i].equals("--enable-crac")) {
                    builder = builder.crac(true);
                    continue;
                }
                if (arguments[i].equals("--help")) {
                    return null;
                }
                if (arguments[i].equals("--jpms")) {
                    builder = builder.jpms(true);
                    continue;
                }
                if (arguments[i].equals("--verbose")) {
                    builder = builder.verbose(true);
                    continue;
                }
                if (arguments[i].equals("--write-pid")) {
                    builder = builder.pid(ProcessHandle.current().pid());
                    continue;
                }

                Optional<ArgumentResult<SinglePiranhaBuilder>> result;

                result = applyArgumentWithValueIfPresent(arguments, i, "--context-path", builder, SinglePiranhaBuilder::contextPath);
                if (result.isEmpty()) {
                    result = applyArgumentWithValueIfPresent(arguments, i, "--extension-class", builder, SinglePiranhaBuilder::extensionClass);
                }
                if (result.isEmpty()) {
                    result = applyArgumentWithValueIfPresent(arguments, i, "--http-port", builder, (b, v) -> {
                        int port = Integer.parseInt(v);
                        httpPort.setPlain(port);
                        return b.httpPort(port);
                    });
                }
                if (result.isEmpty()) {
                    result = applyArgumentWithValueIfPresent(arguments, i, "--https-port", builder, (b, v) -> {
                        int port = Integer.parseInt(v);
                        httpsPort.setPlain(port);
                        return b.httpsPort(port);
                    });
                }
                if (result.isEmpty()) {
                    result = applyArgumentWithValueIfPresent(arguments, i, "--http-server-class", builder, SinglePiranhaBuilder::httpServerClass);
                }
                if (result.isEmpty()) {
                    result = applyArgumentWithValueIfPresent(arguments, i, "--https-keystore-file", builder, SinglePiranhaBuilder::httpsKeystoreFile);
                }
                if (result.isEmpty()) {
                    result = applyArgumentWithValueIfPresent(arguments, i, "--https-keystore-password", builder, SinglePiranhaBuilder::httpsKeystorePassword);
                }
                if (result.isEmpty()) {
                    result = applyArgumentWithValueIfPresent(arguments, i, "--https-server-class", builder, SinglePiranhaBuilder::httpsServerClass);
                }
                if (result.isEmpty()) {
                    result = applyArgumentWithValueIfPresent(arguments, i, "--https-truststore-file", builder, SinglePiranhaBuilder::httpsTruststoreFile);
                }
                if (result.isEmpty()) {
                    result = applyArgumentWithValueIfPresent(arguments, i, "--https-truststore-password", builder, SinglePiranhaBuilder::httpsTruststorePassword);
                }
                if (result.isEmpty()) {
                    result = applyArgumentWithValueIfPresent(arguments, i, "--logging-level", builder, SinglePiranhaBuilder::loggingLevel);
                }
                if (result.isEmpty()) {
                    result = applyArgumentWithValueIfPresent(arguments, i, "--war-file", builder, SinglePiranhaBuilder::warFile);
                }
                if (result.isEmpty()) {
                    result = applyArgumentWithValueIfPresent(arguments, i, "--webapp-dir", builder, SinglePiranhaBuilder::webAppDir);
                }

                if (result.isPresent()) {
                    builder = result.get().builder();
                    i += result.get().advance();
                }
            }
            checkPorts(httpPort.getPlain(), httpsPort.getPlain());
        }
        return builder;
    }

    /**
     * Apply an argument with a value to the builder.
     *
     * @param <B> the builder type.
     * @param arguments the arguments.
     * @param i the current index.
     * @param key the argument key.
     * @param builder the builder.
     * @param action the action to apply.
     * @return the result, or empty if the key does not match.
     */
    private static <B> Optional<ArgumentResult<B>> applyArgumentWithValueIfPresent(
            String[] arguments, int i, String key, B builder, BiFunction<B, String, B> action) {
        if (arguments[i].equals(key)) {
            return Optional.of(new ArgumentResult<>(action.apply(builder, arguments[i + 1]), 1));
        }
        String prefix = key + "=";
        if (arguments[i].startsWith(prefix)) {
            return Optional.of(new ArgumentResult<>(action.apply(builder, arguments[i].substring(prefix.length())), 0));
        }
        return Optional.empty();
    }

    /**
     * Check the HTTP and HTTPS port.
     *
     * @param httpPort the HTTP port.
     * @param httpsPort the HTTPS port.
     */
    private void checkPorts(int httpPort, int httpsPort) {
        if(httpsPort != 0 && httpPort == httpsPort) {
            LOGGER.log(WARNING, "The http and the https ports are the same. Please use different ports");
            System.exit(-1);
        }
    }

    /**
     * Show help.
     */
    protected static void showHelp() {
        LOGGER.log(Level.INFO, "");
        LOGGER.log(Level.INFO,
                """
  --enable-crac                                   - Enable Project CRaC support
  --extension-class <className>                   - Set the extension to use
  --extension-class=<className>
  --context-path <string>                         - Set the Servlet context path
  --context-path=<string>
  --help                                          - Show this help
  --http-port <integer>                           - Set the HTTP port (use -1 to disable)
  --http-port=<integer>
  --http-server-class <className>                 - Set the HTTP server class to use
  --http-server-class=<className>
  --https-keystore-file <file>                    - Set the HTTPS keystore file (applies to
  --https-keystore-file=<file>                      the whole JVM)
  --https-keystore-password <string>              - Set the HTTPS keystore password
  --https-keystore-password=<string>                (applies to the whole JVM)
  --https-port <integer>                          - Set the HTTPS port (disabled by default)
  --https-port=<integer>
  --https-server-class <className>                - Set the HTTPS server class to use
  --https-server-class=<className>
  --https-truststore-file <file>                  - Set the HTTPS truststore file (applies to
  --https-truststore-file=<file>                    the whole JVM)
  --https-truststore-password <string>            - Set the HTTPS truststore password
  --https-truststore-password=<string>              (applies to the whole JVM)
  --jpms                                          - Enable Java Platform Module System
  --logging-level <string>                        - Set the java.util.logging.Level
  --logging-level=<string>
  --verbose                                       - Shows the runtime parameters
  --war-file <file>                               - The WAR file to deploy
  --war-file=<file>
  --webapp-dir <directory>                        - The directory to use for the web application
  --webapp-dir=<directory>                          (auto creates when it does not exist, if
                                                    omitted runtime will use the filename
                                                    portion of --war-file)
  --write-pid                                     - Write out a PID file
                """);
    }
}
