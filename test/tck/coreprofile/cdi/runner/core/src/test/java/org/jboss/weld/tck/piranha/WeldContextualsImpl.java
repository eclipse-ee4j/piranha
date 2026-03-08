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

import jakarta.enterprise.context.spi.Context;
import jakarta.enterprise.context.spi.CreationalContext;
import org.jboss.cdi.tck.spi.Contextuals;

/**
 * Piranha/Weld implementation of the CDI TCK {@link Contextuals} SPI.
 *
 * <p>This class is part of the Weld-backed CDI TCK integration for
 * Piranha Core Profile. It is intentionally placed in the
 * {@code org.jboss.weld.tck.piranha} package alongside the other
 * Weld-specific SPI bridges (e.g. {@code WeldBeansImpl},
 * {@code WeldContextImpl}). If this class fails to load at TCK startup,
 * check that {@code cdi-tck-api} is on the test classpath and that
 * {@code org.jboss.cdi.tck.spi.Contextuals} is listed in
 * {@code META-INF/cdi-tck.properties}.
 *
 * <p>Creates a spy {@link Contextuals.Inspectable} that records the
 * arguments passed to {@code create()} and {@code destroy()} for later
 * assertion by TCK tests. The implementation itself has no Weld-internal
 * dependencies — it is a plain CDI API adapter.
 */
public class WeldContextualsImpl implements Contextuals {

    @Override
    public <T> Contextuals.Inspectable<T> create(T instance, Context context) {
        return new InspectableImpl<>(instance);
    }

    private static class InspectableImpl<T> implements Contextuals.Inspectable<T> {

        private final T instance;
        private CreationalContext<T> creationalContextPassedToCreate;
        private T instancePassedToDestroy;
        private CreationalContext<T> creationalContextPassedToDestroy;

        InspectableImpl(T instance) {
            this.instance = instance;
        }

        @Override
        public T create(CreationalContext<T> creationalContext) {
            this.creationalContextPassedToCreate = creationalContext;
            return instance;
        }

        @Override
        public void destroy(T inst, CreationalContext<T> creationalContext) {
            this.instancePassedToDestroy = inst;
            this.creationalContextPassedToDestroy = creationalContext;
        }

        @Override
        public CreationalContext<T> getCreationalContextPassedToCreate() {
            return creationalContextPassedToCreate;
        }

        @Override
        public T getInstancePassedToDestroy() {
            return instancePassedToDestroy;
        }

        @Override
        public CreationalContext<T> getCreationalContextPassedToDestroy() {
            return creationalContextPassedToDestroy;
        }
    }
}
