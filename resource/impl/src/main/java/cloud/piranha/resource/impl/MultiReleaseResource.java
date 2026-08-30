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
package cloud.piranha.resource.impl;

import cloud.piranha.resource.api.Resource;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

import static java.lang.System.Logger.Level.INFO;
import static java.lang.System.Logger.Level.WARNING;

import java.net.URL;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;

/**
 * A resource wrapper that loads the versioned entries from META-INF/versions if
 * the resource contains a main attribute named "Multi-Release" in the
 * META-INF/MANIFEST.MF
 *
 * <p>
 * A multi-release resource is a resource that contains a set of "base" entries
 * and a set of "versioned" entries contained in subdirectories of
 * "META-INF/versions" directory
 * <p>
 * The versioned entries are partitioned by the major version of the Java
 * release. A versioned entry, with a version {@code n}, {@code 8 < n}, in the
 * "META-INF/versions/{n}" directory overrides the base entry as well as any
 * entry with a version number {@code i} where {@code 8 < i < n}
 */
public final class MultiReleaseResource implements Resource {

    /**
     * Stores the logger.
     */
    private static final System.Logger LOGGER = System.getLogger(MultiReleaseResource.class.getName());

    /**
     * Stores the META-INF constant
     */
    private static final String META_INF = "META-INF";

    /**
     * Stores the META-INF/versions/ constant
     */
    private static final String META_INF_VERSIONS = META_INF + "/versions/";

    /**
     * Stores the current version of the runtime
     */
    private static final int CURRENT_VERSION = Runtime.version().feature();

    /**
     * Stores the base release version
     */
    private static final int BASE_RELEASE_VERSION = 8;

    /**
     * Stores the resource
     */
    private final Resource resource;

    /**
     * Stores if the resource if a multi release
     */
    private final boolean isMultiRelease;

    /**
     * Constructor
     *
     * @param resource the resource
     */
    private final Map<Integer, Set<String>> versionedLocations = new ConcurrentHashMap<>();

    public MultiReleaseResource(Resource resource) {
        this.resource = resource;
        boolean isMultiReleaseTemp = false;

        // Read the MANIFEST.MF to determine if this is a multi-release resource
        try (InputStream resourceAsStream = resource.getResourceAsStream("META-INF/MANIFEST.MF")) {
            if (resourceAsStream != null) {
                isMultiReleaseTemp = Boolean.parseBoolean(new Manifest(resourceAsStream).getMainAttributes().getValue(Attributes.Name.MULTI_RELEASE));
            }
        } catch (IOException ioe) {
            LOGGER.log(WARNING, "I/O error occurred while getting manifest for multi release resource", ioe);
        }

        this.isMultiRelease = isMultiReleaseTemp;

        if (this.isMultiRelease) {
            long startTime = System.currentTimeMillis();
            LOGGER.log(INFO, () -> "MRR: Attempting fast scan for resource type: " + resource.getClass().getName());

            JarFile jarFileToScan = null;
            String resourceName = resource.getName();

            // ATTEMPT 1: JarResource (returns a File, which needs to be reopened as a JarFile)
            if (resource instanceof JarResource jarResource) {
                File file = jarResource.getJarFile();
                if (file != null) {
                    try {
                        jarFileToScan = new JarFile(file);
                        LOGGER.log(INFO, () -> "MRR: JarResource found. Using native JarFile: " + resourceName);
                    } catch (IOException e) {
                        LOGGER.log(WARNING, "MRR: Failed to reopen JarFile from JarResource", file.getAbsolutePath(), e);
                    }
                }
            }

            // ATTEMPT 2: PrefixJarResource (returns an already open JarFile)
            else if (resource instanceof PrefixJarResource prefixJarResource) {
                jarFileToScan = prefixJarResource.getJarFile();
                LOGGER.log(INFO, () -> "MRR: PrefixJarResource found. Using already open JarFile: " + resourceName);
            }

            // RUN THE FAST SCAN if jarFileToScan was obtained successfully
            if (jarFileToScan != null) {
                try (Stream<String> locations = jarFileToScan.stream().map(ZipEntry::getName)) {
                    locations.filter(location -> location.startsWith(META_INF_VERSIONS)).forEach(location -> {
                        parseAndCacheLocation(location);
                    });
                }

                // Close the JarFile if it was opened locally (JarResource)
                if (resource instanceof JarResource) {
                    try {
                        jarFileToScan.close();
                    } catch (IOException ignore) {} // Ignore close error
                }

                long endTime = System.currentTimeMillis();
                LOGGER.log(INFO, () -> "MRR: Fast scan completed in " + (endTime - startTime) + " ms.");
                return; // SUCCESS: exit the constructor
            }

            // SLOW FALLBACK: run the slow scan and populate the cache (if the fast path failed)
            LOGGER.log(WARNING, "MRR: Optimization failed. Using slow getAllLocations() as fallback for " + resourceName);
            resource.getAllLocations()
                    .filter(location -> location.startsWith(META_INF_VERSIONS))
                    .forEach(location -> {
                        parseAndCacheLocation(location); // Call the parsing function
                    });
            long endTime = System.currentTimeMillis();
            LOGGER.log(INFO, () -> "MRR: Slow scan completed in " + (endTime - startTime) + " ms.");
        }
    }

    /**
     * Parses a location string and adds it to the versionedLocations cache.
     * @param location The path within the JAR (e.g., META-INF/versions/9/com/example/MyClass.class)
     */
    private void parseAndCacheLocation(String location) {
        try {
            String[] parts = location.split("/");
            // parts[2] should be the version number (e.g. 9, 10, 11)
            if (parts.length >= 3) {
                int version = Integer.parseInt(parts[2]);
                // Ensure the index doesn't overflow if the string is too short
                int baseLocationStart = META_INF_VERSIONS.length() + parts[2].length();
                if (location.length() > baseLocationStart + 1) {
                    String baseLocation = location.substring(baseLocationStart + 1);
                    versionedLocations
                            .computeIfAbsent(version, k -> ConcurrentHashMap.newKeySet())
                            .add(baseLocation);
                }
            }
        } catch (NumberFormatException e) {
            LOGGER.log(WARNING, "Invalid version folder name found in multi-release: " + location, e);
        }
    }

    @Override
    public URL getResource(String location) {
        if (!isMultiRelease) {
            return resource.getResource(location);
        }
        return versionedEntry(location);
    }

    /**
     * Searches in the META-INF/versions for a versioned entry of some resource.
     *
     * <p>
     * It performs a search in META-INF/versions from the current Java release
     * until the 9 version (the first version supporting multi-release
     * resources).
     *
     * @param location the location of a resource
     * @return the URL of the versioned entry if present otherwise the base
     * entry
     */
    private URL versionedEntry(String location) {
        if (location.startsWith(META_INF)) {
            return resource.getResource(location);
        }
        for (int version = CURRENT_VERSION; version > BASE_RELEASE_VERSION; version--) {
            Set<String> locationsForVersion = versionedLocations.get(version);

            if (locationsForVersion != null && locationsForVersion.contains(location)) {
                return resource.getResource(META_INF_VERSIONS + version + "/" + location);
            }
        }
        return resource.getResource(location);
    }

    @Override
    public InputStream getResourceAsStream(String location) {
        if (!isMultiRelease) {
            return resource.getResourceAsStream(location);
        }
        try {
            URL url = versionedEntry(location);
            if (url != null) {
                return url.openStream();
            }
        } catch (IOException ioe) {
            LOGGER.log(WARNING, "I/O error occurred while getting multi release resource", ioe);
        }
        return null;
    }

    @Override
    public Stream<String> getAllLocations() {
        return resource.getAllLocations();
    }

    @Override
    public String getName() {
        return resource.getName();
    }

    @Override
    public String toString() {
        return getName() + " " + super.toString();
    }
}
