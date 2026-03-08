/*
 * Copyright (c) 2024 Piranha Cloud
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0, which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the
 * Eclipse Public License v. 2.0 are satisfied: GNU General Public License,
 * version 2 with the GNU Classpath Exception, which is available at
 * https://www.gnu.org/software/classpath/license.html.
 *
 * SPDX-License-Identifier: EPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0
 */
package org.jboss.weld.tck.piranha;

import jakarta.enterprise.context.spi.Contextual;
import org.jboss.cdi.tck.spi.CreationalContexts;

/**
 * Piranha/Weld implementation of the CDI TCK {@link CreationalContexts} SPI.
 *
 * <p>This class is part of the Weld-backed CDI TCK integration for
 * Piranha Core Profile. It is intentionally placed in the
 * {@code org.jboss.weld.tck.piranha} package alongside the other
 * Weld-specific SPI bridges (e.g. {@code WeldBeansImpl},
 * {@code WeldContextImpl}). If this class fails to load at TCK startup,
 * check that {@code cdi-tck-api} is on the test classpath and that
 * {@code org.jboss.cdi.tck.spi.CreationalContexts} is listed in
 * {@code META-INF/cdi-tck.properties}.
 *
 * <p>Creates a spy {@link CreationalContexts.Inspectable} that records
 * {@code push()} and {@code release()} invocations for later assertion by
 * TCK tests. The implementation itself has no Weld-internal
 * dependencies — it is a plain CDI API adapter.
 */
public class WeldCreationalContextsImpl implements CreationalContexts {

    @Override
    public <T> CreationalContexts.Inspectable<T> create(Contextual<T> contextual) {
        return new InspectableImpl<>();
    }

    private static class InspectableImpl<T> implements CreationalContexts.Inspectable<T> {

        private boolean pushCalled;
        private Object lastBeanPushed;
        private boolean releaseCalled;

        @Override
        public void push(T incompleteInstance) {
            this.pushCalled = true;
            this.lastBeanPushed = incompleteInstance;
        }

        @Override
        public void release() {
            this.releaseCalled = true;
        }

        @Override
        public boolean isPushCalled() {
            return pushCalled;
        }

        @Override
        public Object getLastBeanPushed() {
            return lastBeanPushed;
        }

        @Override
        public boolean isReleaseCalled() {
            return releaseCalled;
        }
    }
}
