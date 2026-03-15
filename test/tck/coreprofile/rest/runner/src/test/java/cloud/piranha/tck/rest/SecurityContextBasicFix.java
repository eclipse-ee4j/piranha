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
package cloud.piranha.tck.rest;

import org.jboss.arquillian.container.spi.event.container.BeforeDeploy;
import org.jboss.arquillian.core.api.annotation.Observes;
import org.jboss.arquillian.core.spi.LoadableExtension;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.Node;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.spec.WebArchive;

/**
 * Fix for the REST TCK security tests.
 * 
 * <p>
 * This extension adds the necessary Piranha-specific security configuration
 * (piranha-callers.xml) to enable BASIC authentication tests with multiple
 * users and roles as required by the Jakarta RESTful Web Services TCK.
 * </p>
 * 
 * <p>
 * The security configuration provides two test users:
 * </p>
 * <ul>
 *   <li>j2ee/j2ee with DIRECTOR role (for admin/director tests)</li>
 *   <li>javajoe/javajoe with OTHERROLE role (for standard user tests)</li>
 * </ul>
 * 
 * @author Manfred Riem (mriem@manorrock.com)
 */
public class SecurityContextBasicFix implements LoadableExtension {

    /**
     * Stores the piranha-callers.xml content.
     * 
     * This provides the identity store configuration for both DIRECTOR
     * and OTHERROLE security roles as required by the TCK security tests.
     */
    private static final String PIRANHA_CALLERS_XML = """
        <?xml version="1.0" encoding="UTF-8"?>
        <callers>
            <caller callername="j2ee" password="j2ee" groups="DIRECTOR"/>
            <caller callername="javajoe" password="javajoe" groups="OTHERROLE"/>
        </callers>
        """;

    @Override
    public void register(ExtensionBuilder builder) {
        builder.observer(SecurityContextBasicFix.class);
    }

    /**
     * Add piranha-callers.xml to all test deployments that don't already have it.
     * 
     * <p>
     * This ensures that security-aware tests have the necessary identity store
     * configuration available. Tests that don't use security will simply ignore
     * this file.
     * </p>
     * 
     * @param event the before deploy event.
     */
    public void addSecurityConfig(@Observes BeforeDeploy event) {
        Archive<?> archive = event.getDeployment().getArchive();
        if (archive instanceof WebArchive webArchive) {
            // Check if the archive already has piranha-callers.xml
            Node existingCallers = webArchive.get("/WEB-INF/piranha-callers.xml");
            if (existingCallers == null) {
                // Add piranha-callers.xml for all deployments
                webArchive.addAsWebInfResource(
                    new StringAsset(PIRANHA_CALLERS_XML), 
                    "piranha-callers.xml"
                );
            }
        }
    }
}
